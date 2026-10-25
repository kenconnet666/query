import { Decimal } from "decimal.js";
import { CalendarDate, parseDate } from "@internationalized/date";
import assert from "node:assert/strict";
import { before, it } from "node:test";
import pg from "pg";
import {
  arrayAgg,
  arrayContains,
  arrayOverlaps,
  bigint,
  Bound,
  cardinality,
  column,
  corr,
  count,
  cube,
  date,
  daysPreceding,
  decimal,
  deleteFrom,
  distinctOn,
  exceptAll,
  forShare,
  forUpdateOf,
  from,
  greatest,
  grouping,
  groupingSets,
  id,
  insertInto,
  intersectAll,
  least,
  lit,
  mode,
  nullOf,
  percentileCont,
  percentileDisc,
  pgDriver,
  PostgresDatabase,
  PostgresDialect,
  range,
  recursiveCte,
  table,
  textMatches,
  textRank,
  webMatches,
  postgres,
} from "@zerodep-dev/query-postgres";
import { Tagged } from "../../fixtures/dialect-tables.js";
import { Event, Lips, Mara } from "../../fixtures/erp.js";
import { Note } from "../../fixtures/writes.js";
import { liveSuite } from "../support/live.js";

/** 按日期范围分区的日志：分区键 DAY 必须在主键里；分区本身要另外建。 */
@table("PART_LOG", { schema: "QUERY_IT", options: [{ dialect: PostgresDialect, sql: 'PARTITION BY RANGE ("DAY")' }] })
class PartLog {
  @id @column("ID", bigint) id!: bigint;
  @id @column("DAY", date) day!: CalendarDate;
  @column("AMOUNT", decimal, { precision: 10, scale: 2 }) amount!: Decimal | null;
}

/**
 * PostgreSQL 专属的写法（`@zerodep-dev/query-postgres`）和它的驱动行为：DISTINCT ON、ILIKE、分组集合、跳过 NULL 的 GREATEST、
 * INTERSECT ALL、RANGE 窗口帧、递归 CTE、只锁部分表、FULL JOIN、百分位数与众数、RETURNING、数组、JSONB、全文检索、分区表，
 * 以及美元引号里的原生 SQL。数据见 support/sample-data.ts。与 Java 的 PostgresTest 对应。
 */
liveSuite("PostgreSQL 专属写法", { only: ["postgres"] }, (live) => {
  /** 数组、JSONB 和全文检索用的 PG_TAGGED（三行），RETURNING 用的便签表。 */
  before(async () => {
    await live.execute(
      '-DROP TABLE "QUERY_IT"."PG_TAGGED"',
      'CREATE TABLE "QUERY_IT"."PG_TAGGED" ("ID" INTEGER, "TAGS" TEXT[], "DOC" JSONB, "BODY" TEXT)',
      `INSERT INTO "QUERY_IT"."PG_TAGGED" VALUES (1, ARRAY['red', 'blue'], '{"status":"open","owner":{"name":"ann"}}', 'the pump is running')`,
      `INSERT INTO "QUERY_IT"."PG_TAGGED" VALUES (2, ARRAY['red'], '{"status":"closed"}', 'a valve without a leak')`,
      `INSERT INTO "QUERY_IT"."PG_TAGGED" VALUES (3, ARRAY[]::TEXT[], '{"status":"open"}', 'valve leak found')`,
    );
    await live.recreate(Note);
  });

  it("关闭 standard_conforming_strings 的会话里含反斜杠的字面量仍原样往返", async () => {
    // 旧式会话里反斜杠在字符串字面量里是转义符：方言要按会话的设置写字面量。
    const { config } = live;
    const client = new pg.Client({ host: config.host, port: config.port, database: config.database, user: config.user, password: config.password, options: "-c standard_conforming_strings=off" });
    await client.connect();
    try {
      const legacy = new PostgresDatabase(pgDriver(client));
      for (const text of ["back\\slash", "\\'; SELECT 1; --", "ends with \\"]) assert.equal(await legacy.one(from(Lips).limit(1).select(() => lit(text))), text);
    } finally {
      await client.end();
    }
  });

  it("DISTINCT ON 取每个物料最新的交货", async () => {
    assert.deepEqual(await live.db.list(from(Lips).with((l) => distinctOn(l.material)).orderBy((l) => [l.material.asc(), l.createdOn.desc()]).select((l) => l.delivery)), ["D5", "D3"]);
  });

  it("聚合 FILTER 与 ILIKE 以及 IS DISTINCT FROM", async () => {
    assert.equal(Number(await live.db.one(from(Lips).select((l) => l.quantity.sum().filter(() => l.material.eq("M1"))))), 6);
    assert.equal(await live.db.one(from(Lips).where((l) => postgres(l.unit).ilike("ea")).select(() => count())), 5);
    assert.equal(await live.db.one(from(Lips).where((l) => l.material.isDistinctFrom(lit("M1"))).select(() => count())), 2);
  });

  it("带排序的 STRING_AGG", async () => {
    const rows = await live.db.list(
      from(Lips)
        .groupBy((l) => l.material)
        .orderBy((l) => l.material)
        .select((l) => ({ material: l.material, deliveries: l.delivery.stringAgg(",", l.delivery.asc()) }))
        .map((r) => `${r.material}=${r.deliveries}`),
    );
    assert.deepEqual(rows, ["M1=D1,D2,D5", "M2=D3,D4"]);
  });

  it("分组集合与 CUBE", async () => {
    const sets = await live.db.list(
      from(Lips)
        .groupBy((l) => groupingSets([l.material], []))
        .orderBy((l) => [grouping(l.material), l.material])
        .select((l) => ({ material: l.material, total: l.quantity.sum() })),
    );
    assert.deepEqual(sets.map((r) => `${r.material}=${Number(r.total)}`), ["M1=6", "M2=12", "null=18"]);
    assert.equal((await live.db.list(from(Lips).groupBy((l) => cube(l.material, l.unit)).select(() => count()))).length, 6, "material x unit, material, unit and the grand total");
  });

  it("原生 SQL 不在美元引号里找参数", async () => {
    assert.deepEqual(await live.db.sql("SELECT $$:not_a_parameter$$ || $tag$ it's :also $tag$ || :x AS v").bind("x", "!").values(), [":not_a_parameter it's :also !"]);
  });

  it("GREATEST 与 LEAST 跳过 NULL", async () => {
    const rows = await live.db.list(from(Lips).orderBy((l) => l.delivery).select((l) => ({ delivery: l.delivery, high: greatest(l.quantity, new Decimal("4")), low: least(l.quantity, new Decimal("4")) })));
    assert.deepEqual(rows.map((r) => `${r.delivery}:${Number(r.high)}:${Number(r.low)}`), ["D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"]);
    assert.equal(Number(await live.db.one(from(Lips).where((l) => l.delivery.eq("D1")).select((l) => greatest(l.quantity, nullOf(decimal))))), 3, "HANA 和 MySQL 在这里返回 NULL");
  });

  it("INTERSECT ALL 与 EXCEPT ALL 按次数保留重复的行", async () => {
    const each = from(Lips).where((l) => l.unit.eq("EA")).select((l) => l.material); // M1 三次、M2 两次
    const master = from(Mara).select((m) => m.material); // M1 到 M4 各一次
    assert.deepEqual((await live.db.list(intersectAll(each, master))).sort(), ["M1", "M2"]);
    assert.deepEqual((await live.db.list(exceptAll(each, master))).sort(), ["M1", "M1", "M2"]);
  });

  it("RANGE 窗口帧按排序值的距离取行", async () => {
    // EVENTS 的两行相隔十几年：近 7 天的窗口各只有自己；按编号距离 1 以内时 2 号也数上 1 号。
    const rows = await live.db.list(
      from(Event)
        .orderBy((e) => e.id)
        .select((e) => ({
          id: e.id,
          lastWeek: count().over((w) => w.orderBy(e.onDay).frame(range(daysPreceding(7), Bound.currentRow()))),
          near: count().over((w) => w.orderBy(e.id).frame(range(Bound.preceding(1), Bound.following(0)))),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.id}:${r.lastWeek}:${r.near}`), ["1:1:1", "2:1:2"]);
  });

  it("递归 CTE 逐层展开到终止条件", async () => {
    const steps = recursiveCte(
      "steps",
      from(Lips).where((l) => l.delivery.eq("D1")).select((l) => ({ delivery: l.delivery, depth: lit(1) })),
      (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: s.depth.plus(lit(1)) })),
    );
    assert.deepEqual(await live.db.list(from(steps).orderBy((s) => s.depth).select((s) => s.depth)), [1, 2, 3]);
  });

  it("共享锁与只锁部分表的加锁读", async () => {
    await live.db.transaction(async () => {
      assert.equal((await live.db.list(from(Lips).join(Mara, (l, m) => m.material.eq(l.material)).with((l) => forUpdateOf("skipLocked", l)).select((l) => l.delivery))).length, 5);
      assert.equal((await live.db.list(from(Lips).with(forShare("nowait")).select((l) => l.delivery))).length, 5);
    });
  });

  it("FULL JOIN 保留两侧没有匹配的行且有全局过滤条件时报错", async () => {
    const rows = await live.db.list(from(Lips).unfiltered().fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l, m) => ({ delivery: l.delivery, material: m.material })));
    assert.equal(rows.length, 12);
    assert.equal(rows.filter((r) => r.delivery === null).length, 2);
    await assert.rejects(live.db.list(from(Lips).fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l) => l.delivery)), /FULL JOIN cannot keep/, "a global filter has no side to go to");
  });

  it("百分位数众数与相关系数", async () => {
    const rows = await live.db.list(
      from(Lips)
        .groupBy((l) => l.material)
        .orderBy((l) => l.material)
        .select((l) => ({ material: l.material, median: percentileCont(0.5, l.quantity), lower: percentileDisc(0.5, l.quantity), unit: mode(l.unit), self: corr(l.quantity, l.quantity) })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.median}:${Number(r.lower)}:${r.unit}:${Math.round(r.self!)}`), ["M1:2:2:EA:1", "M2:6:5:EA:1"]);
  });

  it("RETURNING 读回插入和删除的行", async () => {
    const db = live.db as PostgresDatabase;
    const row = { id: "RET1", tenant: "T9", title: "returned", qty: 3, status: "open" };
    assert.deepEqual(await db.returning(insertInto(Note).values(row)), [row]);
    assert.deepEqual(await db.returning(deleteFrom(Note).where((n) => n.id.eq("RET1"))), [row]);
    assert.deepEqual(await db.returning(deleteFrom(Note).where((n) => n.id.eq("RET1"))), []);
  });

  it("数组参数与数组列", async () => {
    const rows = await live.db.list(from(Tagged).where((t) => postgres(t.id).eqAny([1, 2, 3])).orderBy((t) => t.id).select((t) => ({ id: t.id, tags: t.tags, count: cardinality(t.tags) })));
    assert.deepEqual(rows.map((r) => `${r.id}:${JSON.stringify(r.tags)}:${r.count}`), ['1:["red","blue"]:2', '2:["red"]:1', "3:[]:0"]);
    assert.deepEqual(await live.db.list(from(Tagged).where((t) => arrayContains(t.tags, ["red"])).orderBy((t) => t.id).select((t) => t.id)), [1, 2]);
    assert.deepEqual(await live.db.list(from(Tagged).where((t) => arrayOverlaps(t.tags, ["blue", "green"])).select((t) => t.id)), [1]);
    assert.deepEqual(await live.db.list(from(Tagged).where((t) => postgres(t.id).eqAny([])).select((t) => t.id)), [], "an empty array matches nothing");
    assert.deepEqual(await live.db.one(from(Lips).where((l) => l.material.eq("M2")).select((l) => arrayAgg(l.delivery, l.delivery.desc()))), ["D4", "D3"]);
  });

  it("JSONB 的包含、键与路径文本", async () => {
    const rows = await live.db.list(from(Tagged).where((t) => {
      postgres(t.doc).jsonContains('{"status":"open"}');
      postgres(t.doc).jsonHasKey("owner");
    }).select((t) => ({ id: t.id, owner: postgres(t.doc).jsonText("owner", "name") })));
    assert.deepEqual(rows.map((r) => `${r.id}:${r.owner}`), ["1:ann"]);
  });

  it("全文检索与相关度", async () => {
    assert.deepEqual(await live.db.list(from(Tagged).where((t) => textMatches("simple", t.body, "pump")).select((t) => t.id)), [1]);
    assert.deepEqual(await live.db.list(from(Tagged).where((t) => webMatches("simple", t.body, "valve -found")).select((t) => t.id)), [2]);
    assert.deepEqual(await live.db.list(from(Tagged).orderBy((t) => [textRank("simple", t.body, "pump").desc(), t.id]).select((t) => t.id)), [1, 2, 3]);
  });

  it("按 options 建原生分区表且查询不变", async () => {
    await live.recreate(PartLog);
    // PostgreSQL 的分区要另外建，通常写在迁移里。
    await live.execute(
      `CREATE TABLE "QUERY_IT"."PART_LOG_2025" PARTITION OF "QUERY_IT"."PART_LOG" FOR VALUES FROM ('2025-01-01') TO ('2026-01-01')`,
      'CREATE TABLE "QUERY_IT"."PART_LOG_REST" PARTITION OF "QUERY_IT"."PART_LOG" DEFAULT',
    );
    await live.db.execute(insertInto(PartLog).values({ id: 1n, day: parseDate("2025-06-01"), amount: new Decimal("1") }, { id: 2n, day: parseDate("2026-06-01"), amount: new Decimal("10") }));
    assert.deepEqual(await live.db.list(from(PartLog).where((p) => p.day.ge(parseDate("2026-01-01"))).select((p) => p.id)), [2n]);
    const result = await live.driver.query(
      "SELECT COUNT(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'QUERY_IT' AND c.relname = 'PART_LOG'",
      [],
    );
    assert.equal(Number(result.rows[0]![0]), 2, "partitions of PART_LOG");
  });
});

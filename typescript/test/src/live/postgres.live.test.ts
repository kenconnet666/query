import assert from "node:assert/strict";
import { it } from "node:test";
import pg from "pg";
import { count, cube, decimal, distinctOn, from, greatest, grouping, groupingSets, ilike, least, lit, nullOf, pgDriver, PostgresDatabase, stringAgg, sum } from "@zerodep-dev/query-postgres";
import * as pgx from "@zerodep-dev/query-postgres";
import { all, Bound, deleteFrom, insertInto, plus } from "@zerodep-dev/query-postgres";
import { Event, Lips, Mara } from "../fixtures/erp.js";
import { Note } from "../fixtures/writes.js";
import { PartLogPg, Tagged } from "../fixtures/dialect-tables.js";
import { liveScenarios } from "./live.js";

liveScenarios(
  {
    name: "PG",
    async connect(config) {
      const pool = new pg.Pool({ host: config.host, port: config.port, database: config.database, user: config.user, password: config.password, max: 4 });
      return { driver: pgDriver(pool), close: () => pool.end() };
    },
    database: (driver, options) => new PostgresDatabase(driver, options),
    text: (length) => `VARCHAR(${length})`,
    slowStatement: "SELECT pg_sleep(3)",
    setup: taggedTable(),
  },
  (live) => {
    it("关闭 standard_conforming_strings 的会话里含反斜杠的字面量仍原样往返", async () => {
      const { config } = live();
      const client = new pg.Client({ host: config.host, port: config.port, database: config.database, user: config.user, password: config.password, options: "-c standard_conforming_strings=off" });
      await client.connect();
      try {
        const legacy = new PostgresDatabase(pgDriver(client));
        for (const text of ["back\\slash", "\\'; SELECT 1; --", "ends with \\"]) {
          assert.equal(await legacy.one(from(Lips).limit(1).select(() => lit(text))), text);
        }
      } finally {
        await client.end();
      }
    });

    it("DISTINCT ON 取每个物料最新的交货", async () => {
      const rows = await live().db.list(
        from(Lips)
          .with((l) => distinctOn(l.material))
          .orderBy((l) => [l.material.asc(), l.createdOn.desc()])
          .select((l) => l.delivery),
      );
      assert.deepEqual(rows, ["D5", "D3"]);
    });

    it("条件聚合与 ILIKE 以及 IS DISTINCT FROM", async () => {
      const { db } = live();
      assert.equal(Number(await db.one(from(Lips).select((l) => sum(l.quantity).filter(l.material.eq("M1"))))), 6);
      assert.equal(
        await db.one(
          from(Lips)
            .where((l) => ilike(l.unit, "ea"))
            .select(() => count()),
        ),
        5,
      );
      assert.equal(
        await db.one(
          from(Lips)
            .where((l) => l.material.isDistinctFrom(lit("M1")))
            .select(() => count()),
        ),
        2,
      );
    });

    it("带排序的 STRING_AGG", async () => {
      const rows = await live().db.list(
        from(Lips)
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({ material: l.material, deliveries: stringAgg(l.delivery, ",", l.delivery.asc()) }))
          .map((r) => `${r.material}=${r.deliveries}`),
      );
      assert.deepEqual(rows, ["M1=D1,D2,D5", "M2=D3,D4"]);
    });

    it("分组集合与 CUBE", async () => {
      const { db } = live();
      const sets = await db.list(
        from(Lips)
          .groupBy((l) => groupingSets([l.material], []))
          .orderBy((l) => [grouping(l.material), l.material])
          .select((l) => ({ material: l.material, total: sum(l.quantity) })),
      );
      assert.deepEqual(
        sets.map((r) => `${r.material}=${Number(r.total)}`),
        ["M1=6", "M2=12", "null=18"],
      );
      const cells = await db.list(
        from(Lips)
          .groupBy((l) => cube(l.material, l.unit))
          .select(() => count()),
      );
      assert.equal(cells.length, 6, "material x unit, material, unit and the grand total");
    });

    it("原生 SQL 不在美元引号里找参数", async () => {
      const rows = await live().db.sql("SELECT $$:not_a_parameter$$ || $tag$ it's :also $tag$ || :x AS v").bind("x", "!").values();
      assert.deepEqual(rows, [":not_a_parameter it's :also !"]);
    });

    it("GREATEST 与 LEAST 跳过 NULL", async () => {
      const { db } = live();
      const rows = await db.list(
        from(Lips)
          .orderBy((l) => l.delivery)
          .select((l) => ({ delivery: l.delivery, high: greatest(l.quantity, "4"), low: least(l.quantity, "4") })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.delivery}:${Number(r.high)}:${Number(r.low)}`),
        ["D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"],
      );
      const withNull = await db.one(
        from(Lips)
          .where((l) => l.delivery.eq("D1"))
          .select((l) => greatest(l.quantity, nullOf(decimal))),
      );
      assert.equal(Number(withNull), 3);
    });

    it("INTERSECT ALL 与 EXCEPT ALL 按次数保留重复的行", async () => {
      const { db } = live();
      const each = from(Lips).where((l) => l.unit.eq("EA")).select((l) => l.material);
      const master = from(Mara).select((m) => m.material);
      assert.deepEqual((await db.list(pgx.intersectAll(each, master))).sort(), ["M1", "M2"]);
      assert.deepEqual((await db.list(pgx.exceptAll(each, master))).sort(), ["M1", "M1", "M2"]);
    });

    it("RANGE 窗口帧按排序值的距离取行", async () => {
      const rows = await live().db.list(
        from(Event)
          .orderBy((e) => e.id)
          .select((e) => ({
            id: e.id,
            lastWeek: count().over((w) => w.orderBy(e.onDay).frame(pgx.range(pgx.daysPreceding(7), Bound.currentRow()))),
            near: count().over((w) => w.orderBy(e.id).frame(pgx.range(Bound.preceding(1), Bound.following(0)))),
          })),
      );
      assert.deepEqual(rows.map((r) => `${r.id}:${r.lastWeek}:${r.near}`), ["1:1:1", "2:1:2"]);
    });

    it("递归 CTE 逐层展开到终止条件", async () => {
      const steps = pgx.recursiveCte(
        "steps",
        from(Lips).where((l) => l.delivery.eq("D1")).select((l) => ({ delivery: l.delivery, depth: lit(1) })),
        (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: plus(s.depth, lit(1)) })),
      );
      assert.deepEqual(await live().db.list(from(steps).orderBy((s) => s.depth).select((s) => s.depth)), [1, 2, 3]);
    });

    it("共享锁与只锁部分表的加锁读", async () => {
      const { db } = live();
      await db.transaction(async () => {
        assert.equal((await db.list(from(Lips).join(Mara, (l, m) => m.material.eq(l.material)).with((l) => pgx.forUpdateOf("skipLocked", l)).select((l) => l.delivery))).length, 5);
        assert.equal((await db.list(from(Lips).with(pgx.forShare("nowait")).select((l) => l.delivery))).length, 5);
      });
    });

    it("FULL JOIN 保留两侧没有匹配的行且有全局过滤条件时报错", async () => {
      const { db } = live();
      const rows = await db.list(from(Lips).unfiltered().fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l, m) => ({ delivery: l.delivery, material: m.material })));
      assert.equal(rows.length, 12);
      assert.equal(rows.filter((r) => r.delivery === null).length, 2);
      await assert.rejects(db.list(from(Lips).fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l) => l.delivery)), /FULL JOIN cannot keep/);
    });

    it("百分位数众数与相关系数", async () => {
      const rows = await live().db.list(
        from(Lips)
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({ material: l.material, median: pgx.percentileCont(0.5, l.quantity), lower: pgx.percentileDisc(0.5, l.quantity), unit: pgx.mode(l.unit), self: pgx.corr(l.quantity, l.quantity) })),
      );
      assert.deepEqual(rows.map((r) => `${r.material}:${r.median}:${Number(r.lower)}:${r.unit}:${Math.round(r.self!)}`), ["M1:2:2:EA:1", "M2:6:5:EA:1"]);
    });

    it("RETURNING 读回插入和删除的行", async () => {
      const db = live().db as PostgresDatabase;
      const row = { id: "RET1", tenant: "T9", title: "returned", qty: 3, status: "open" };
      assert.deepEqual(await db.returning(insertInto(Note).values(row)), [row]);
      assert.deepEqual(await db.returning(deleteFrom(Note).where((n) => n.id.eq("RET1"))), [row]);
      assert.deepEqual(await db.returning(deleteFrom(Note).where((n) => n.id.eq("RET1"))), []);
    });

    it("数组参数与数组列", async () => {
      const { db } = live();
      const rows = await db.list(from(Tagged).where((t) => pgx.eqAny(t.id, [1, 2, 3])).orderBy((t) => t.id).select((t) => ({ id: t.id, tags: t.tags, count: pgx.cardinality(t.tags) })));
      assert.deepEqual(rows.map((r) => `${r.id}:${JSON.stringify(r.tags)}:${r.count}`), ['1:["red","blue"]:2', '2:["red"]:1', "3:[]:0"]);
      assert.deepEqual(await db.list(from(Tagged).where((t) => pgx.arrayContains(t.tags, ["red"])).orderBy((t) => t.id).select((t) => t.id)), [1, 2]);
      assert.deepEqual(await db.list(from(Tagged).where((t) => pgx.arrayOverlaps(t.tags, ["blue", "green"])).select((t) => t.id)), [1]);
      assert.deepEqual(await db.list(from(Tagged).where((t) => pgx.eqAny(t.id, [])).select((t) => t.id)), []);
      assert.deepEqual(await db.one(from(Lips).where((l) => l.material.eq("M2")).select((l) => pgx.arrayAgg(l.delivery, l.delivery.desc()))), ["D4", "D3"]);
    });

    it("JSONB 的包含、键与路径文本", async () => {
      const rows = await live().db.list(from(Tagged).where((t) => all(pgx.jsonContains(t.doc, '{"status":"open"}'), pgx.jsonHasKey(t.doc, "owner"))).select((t) => ({ id: t.id, owner: pgx.jsonText(t.doc, "owner", "name") })));
      assert.deepEqual(rows.map((r) => `${r.id}:${r.owner}`), ["1:ann"]);
    });

    it("全文检索与相关度", async () => {
      const { db } = live();
      assert.deepEqual(await db.list(from(Tagged).where((t) => pgx.textMatches("simple", t.body, "pump")).select((t) => t.id)), [1]);
      assert.deepEqual(await db.list(from(Tagged).where((t) => pgx.webMatches("simple", t.body, "valve -found")).select((t) => t.id)), [2]);
      assert.deepEqual(await db.list(from(Tagged).orderBy((t) => [pgx.textRank("simple", t.body, "pump").desc(), t.id]).select((t) => t.id)), [1, 2, 3]);
    });

    it("按 options 建原生分区表且查询不变", async () => {
      const { db, driver } = live();
      await driver.query("DROP TABLE \"QUERY_IT\".\"PART_LOG\"", []).catch(() => undefined);
      await (await db.schema().plan(PartLogPg)).apply();
      // PostgreSQL 的分区要另外建，通常写在迁移里。
      await driver.query('CREATE TABLE "QUERY_IT"."PART_LOG_2025" PARTITION OF "QUERY_IT"."PART_LOG" FOR VALUES FROM (\'2025-01-01\') TO (\'2026-01-01\')', []);
      await driver.query('CREATE TABLE "QUERY_IT"."PART_LOG_REST" PARTITION OF "QUERY_IT"."PART_LOG" DEFAULT', []);
      await db.execute(insertInto(PartLogPg).values({ id: 1n, day: "2025-06-01", amount: "1" }, { id: 2n, day: "2026-06-01", amount: "10" }));
      assert.deepEqual(await db.list(from(PartLogPg).where((p) => p.day.ge("2026-01-01")).select((p) => p.id)), [2n]);
      const result = await driver.query("SELECT COUNT(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'QUERY_IT' AND c.relname = 'PART_LOG'", []);
      assert.equal(Number(result.rows[0]![0]), 2, "partitions of PART_LOG");
    });
  },
);

/** 建数组、JSONB 和全文检索用的表。 */
function taggedTable(): string[] {
  return [
    '-DROP TABLE "QUERY_IT"."PG_TAGGED"',
    'CREATE TABLE "QUERY_IT"."PG_TAGGED" ("ID" INTEGER, "TAGS" TEXT[], "DOC" JSONB, "BODY" TEXT)',
    `INSERT INTO "QUERY_IT"."PG_TAGGED" VALUES (1, ARRAY['red', 'blue'], '{"status":"open","owner":{"name":"ann"}}', 'the pump is running')`,
    `INSERT INTO "QUERY_IT"."PG_TAGGED" VALUES (2, ARRAY['red'], '{"status":"closed"}', 'a valve without a leak')`,
    `INSERT INTO "QUERY_IT"."PG_TAGGED" VALUES (3, ARRAY[]::TEXT[], '{"status":"open"}', 'valve leak found')`,
  ];
}

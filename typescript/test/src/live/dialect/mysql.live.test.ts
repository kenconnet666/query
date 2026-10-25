import assert from "node:assert/strict";
import { before, it } from "node:test";
import {
  bigint,
  Bound,
  column,
  count,
  date,
  daysBetween,
  daysPreceding,
  decimal,
  deleteFrom,
  exceptAll,
  forShare,
  forUpdateOf,
  from,
  greatest,
  id,
  insertInto,
  intersectAll,
  jsonContains,
  jsonText,
  least,
  lit,
  match,
  matchBoolean,
  matchScore,
  maxExecutionTime,
  MysqlDialect,
  noIndex,
  nullOf,
  plus,
  range,
  recursiveCte,
  stringAgg,
  table,
  update,
} from "@zerodep-dev/query-mysql";
import { Article } from "../../fixtures/dialect-tables.js";
import { Event, Lips, Mara } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/** 按日期范围分区的日志：分区键 DAY 必须在主键里。 */
@table("PART_LOG", {
  schema: "QUERY_IT",
  options: [{ dialect: MysqlDialect, sql: "PARTITION BY RANGE COLUMNS(`DAY`) (PARTITION p2025 VALUES LESS THAN ('2026-01-01'), PARTITION p2026 VALUES LESS THAN ('2027-01-01'), PARTITION pmax VALUES LESS THAN (MAXVALUE))" }],
})
class PartLog {
  @id @column("ID", bigint) id!: bigint;
  @id @column("DAY", date) day!: string;
  @column("AMOUNT", decimal, { precision: 10, scale: 2 }) amount!: string | null;
}

/**
 * MySQL 专属的写法（`@zerodep-dev/query-mysql`），PolarDB 兼容 MySQL，所以两个库都跑：DATEDIFF、GROUP_CONCAT、反引号与反斜杠转义的原生 SQL、
 * NULL 时为 NULL 的 GREATEST、INTERSECT ALL、RANGE 窗口帧、递归 CTE、只锁部分表、优化器提示、JSON、全文检索和分区表。
 * 数据见 support/sample-data.ts。与 Java 的 MysqlTest 对应。
 */
liveSuite("MySQL 专属写法", { only: ["mysql", "polardb"] }, (live) => {
  /** JSON 和全文检索用的 MY_ARTICLE：三篇文章。 */
  before(() =>
    live.execute(
      "-DROP TABLE `QUERY_IT`.`MY_ARTICLE`",
      "CREATE TABLE `QUERY_IT`.`MY_ARTICLE` (`ID` INTEGER, `DOC` JSON, `TITLE` VARCHAR(100), `BODY` TEXT, FULLTEXT ft_article (`TITLE`, `BODY`)) ENGINE=InnoDB",
      `INSERT INTO \`QUERY_IT\`.\`MY_ARTICLE\` VALUES (1, '{"status":"open","owner":{"name":"ann"}}', 'pump manual', 'the pump is running')`,
      `INSERT INTO \`QUERY_IT\`.\`MY_ARTICLE\` VALUES (2, '{"status":"closed"}', 'valve notes', 'valve without leak')`,
      `INSERT INTO \`QUERY_IT\`.\`MY_ARTICLE\` VALUES (3, '{"status":"open"}', 'valve report', 'valve leak found')`,
    ),
  );

  it("DATEDIFF 与 GROUP_CONCAT", async () => {
    assert.equal(await live.db.one(from(Lips).limit(1).select(() => daysBetween(lit("2026-01-01", date), lit("2026-02-01", date)))), 31);
    const rows = await live.db.list(
      from(Lips)
        .with(maxExecutionTime(5000))
        .groupBy((l) => l.material)
        .orderBy((l) => l.material)
        .select((l) => ({ material: l.material, deliveries: stringAgg(l.delivery, ",", l.delivery.asc()) }))
        .map((r) => `${r.material}=${r.deliveries}`),
    );
    assert.deepEqual(rows, ["M1=D1,D2,D5", "M2=D3,D4"]);
  });

  it("原生 SQL 不在反引号和反斜杠转义的字符串里找参数", async () => {
    assert.deepEqual(await live.db.sql("SELECT CONCAT('it\\'s :no', :x) AS `v:w`").bind("x", "!").values(), ["it's :no!"]);
  });

  it("GREATEST 与 LEAST 遇到 NULL 时返回 NULL", async () => {
    const rows = await live.db.list(from(Lips).orderBy((l) => l.delivery).select((l) => ({ delivery: l.delivery, high: greatest(l.quantity, "4"), low: least(l.quantity, "4") })));
    assert.deepEqual(rows.map((r) => `${r.delivery}:${Number(r.high)}:${Number(r.low)}`), ["D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"]);
    assert.equal(await live.db.one(from(Lips).where((l) => l.delivery.eq("D1")).select((l) => greatest(l.quantity, nullOf(decimal)))), null, "PostgreSQL 跳过 NULL，HANA 和 MySQL 返回 NULL");
  });

  it("INTERSECT ALL 与 EXCEPT ALL 按次数保留重复的行", async () => {
    const each = from(Lips).where((l) => l.unit.eq("EA")).select((l) => l.material); // M1 三次、M2 两次
    const master = from(Mara).select((m) => m.material); // M1 到 M4 各一次
    assert.deepEqual((await live.db.list(intersectAll(each, master))).sort(), ["M1", "M2"]);
    assert.deepEqual((await live.db.list(exceptAll(each, master))).sort(), ["M1", "M1", "M2"]);
  });

  it("RANGE 窗口帧按排序值的距离取行", async () => {
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
      (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: plus(s.depth, lit(1)) })),
    );
    assert.deepEqual(await live.db.list(from(steps).orderBy((s) => s.depth).select((s) => s.depth)), [1, 2, 3]);
  });

  it("共享锁与只锁部分表的加锁读", async () => {
    await live.db.transaction(async () => {
      assert.equal((await live.db.list(from(Lips).join(Mara, (l, m) => m.material.eq(l.material)).with((l) => forUpdateOf("skipLocked", l)).select((l) => l.delivery))).length, 5);
      assert.equal((await live.db.list(from(Lips).with(forShare("nowait")).select((l) => l.delivery))).length, 5);
    });
  });

  it("MySQL 没有 FULL JOIN", async () => {
    await assert.rejects(live.db.list(from(Lips).unfiltered().fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l) => l.delivery)), /has no FULL JOIN/);
  });

  it("优化器提示合并进一个注释", async () => {
    const rows = await live.db.list(
      from(Lips)
        .join(Mara, (l, m) => m.material.eq(l.material))
        .with(() => maxExecutionTime(5000))
        .with((_l, m) => noIndex(m))
        .orderBy((l) => l.delivery)
        .select((l) => l.delivery),
    );
    assert.deepEqual(rows, ["D1", "D2", "D3", "D4", "D5"]);
  });

  it("UPDATE 与 DELETE 带优化器提示", async () => {
    // 在事务里改共享的样例数据，再回滚。
    await assert.rejects(
      live.db.transaction(async (tx) => {
        assert.equal(await tx.execute(update(Lips).with((l) => noIndex(l)).set(() => ({ unit: "KG" })).where((l) => l.delivery.eq("D1"))), 1);
        assert.equal(await tx.one(from(Lips).where((l) => l.delivery.eq("D1")).select((l) => l.unit)), "KG");
        assert.equal(await tx.execute(deleteFrom(Lips).with((l) => noIndex(l)).where((l) => l.delivery.eq("D9"))), 0);
        throw new Error("roll back the change");
      }),
      /roll back the change/,
    );
    assert.equal(await live.db.one(from(Lips).where((l) => l.delivery.eq("D1")).select((l) => l.unit)), "EA");
  });

  it("JSON 的包含与路径文本", async () => {
    const rows = await live.db.list(from(Article).where((a) => jsonContains(a.doc, '{"status":"open"}')).orderBy((a) => a.id).select((a) => ({ id: a.id, owner: jsonText(a.doc, "$.owner.name") })));
    assert.deepEqual(rows.map((r) => `${r.id}:${r.owner}`), ["1:ann", "3:null"]);
  });

  it("全文检索的自然语言与布尔模式", async () => {
    assert.deepEqual(await live.db.list(from(Article).where((a) => match("pump", a.title, a.body)).select((a) => a.id)), [1]);
    assert.deepEqual(await live.db.list(from(Article).where((a) => matchBoolean("+valve", a.title, a.body)).orderBy((a) => a.id).select((a) => a.id)), [2, 3]);
    assert.deepEqual(await live.db.list(from(Article).where((a) => matchBoolean("+valve -found", a.title, a.body)).select((a) => a.id)), [2]);
    assert.equal((await live.db.list(from(Article).orderBy((a) => [matchScore("pump", a.title, a.body).desc(), a.id]).select((a) => a.id)))[0], 1);
  });

  it("按 options 建原生分区表且查询不变", async () => {
    await live.recreate(PartLog);
    await live.db.execute(insertInto(PartLog).values({ id: 1n, day: "2025-06-01", amount: "1" }, { id: 2n, day: "2026-06-01", amount: "10" }));
    assert.deepEqual(await live.db.list(from(PartLog).where((p) => p.day.ge("2026-01-01")).select((p) => p.id)), [2n]);
    const result = await live.driver.query("SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = 'QUERY_IT' AND TABLE_NAME = 'PART_LOG'", []);
    assert.equal(Number(result.rows[0]![0]), 3, "partitions of PART_LOG");
  });
});

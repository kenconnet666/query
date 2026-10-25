import assert from "node:assert/strict";
import { before, it } from "node:test";
import {
  addDays,
  addMonths,
  ancestors,
  bigint,
  column,
  containsFuzzy,
  contains,
  corr,
  count,
  cube,
  date,
  daysBetween,
  decimal,
  deleteFrom,
  descendants,
  from,
  fromFunction,
  greatest,
  grouping,
  groupingSets,
  HanaDialect,
  hierarchy,
  hint,
  id,
  insertInto,
  jsonValue,
  least,
  lit,
  median,
  nullOf,
  percentileCont,
  percentileDisc,
  QueryExecutionError,
  score,
  stringAgg,
  substring,
  sum,
  table,
  toInteger,
  toVarchar,
  updateJoin,
} from "@zerodep-dev/query-hana";
import { LipsByUnit, orgSource } from "../../fixtures/dialect-tables.js";
import { Lips, Mara } from "../../fixtures/erp.js";
import { Doc, Note } from "../../fixtures/writes.js";
import { liveSuite } from "../support/live.js";

/** 按日期范围分区的日志：分区键 DAY 必须在主键里。 */
@table("PART_LOG", { schema: "QUERY_IT", options: [{ dialect: HanaDialect, sql: "PARTITION BY RANGE (DAY) (PARTITION '2025-01-01' <= VALUES < '2026-01-01', PARTITION '2026-01-01' <= VALUES < '2027-01-01', PARTITION OTHERS)" }] })
class PartLog {
  @id @column("ID", bigint) id!: bigint;
  @id @column("DAY", date) day!: string;
  @column("AMOUNT", decimal, { precision: 10, scale: 2 }) amount!: string | null;
}

/**
 * HANA 专属的写法（`@zerodep-dev/query-hana`）：日期函数、提示子句、分组集合、NULL 时为 NULL 的 GREATEST、FULL JOIN、中位数与百分位数、
 * 层级、表函数、全文检索与 JSON、分区表，联表更新一行匹配多行时报错，以及超时按整秒交给客户端。数据见 support/sample-data.ts。
 * 与 Java 的 HanaTest 对应。
 */
liveSuite("HANA 专属写法", { only: ["hana"] }, (live) => {
  /** 层级用的组织树 A → B → C、A → D，和返回某个单位的交货行的表函数，都手写；联表更新用 fixtures/writes.ts 的便签和文档表。 */
  before(async () => {
    await live.execute(
      "-DROP TABLE QUERY_IT.H_ORG",
      "CREATE COLUMN TABLE QUERY_IT.H_ORG (ID NVARCHAR(10), PARENT_ID NVARCHAR(10), NAME NVARCHAR(20))",
      "INSERT INTO QUERY_IT.H_ORG VALUES ('A', NULL, 'root')",
      "INSERT INTO QUERY_IT.H_ORG VALUES ('B', 'A', 'b')",
      "INSERT INTO QUERY_IT.H_ORG VALUES ('C', 'B', 'c')",
      "INSERT INTO QUERY_IT.H_ORG VALUES ('D', 'A', 'd')",
      "-DROP FUNCTION QUERY_IT.FN_LIPS",
      "CREATE FUNCTION QUERY_IT.FN_LIPS (P_UNIT NVARCHAR(3)) RETURNS TABLE (VBELN NVARCHAR(255), LFIMG DECIMAL(13,3)) LANGUAGE SQLSCRIPT AS BEGIN RETURN SELECT VBELN, LFIMG FROM QUERY_IT.LIPS WHERE VRKME = :P_UNIT; END",
    );
    await live.recreate(Note, Doc);
  });

  it("日期函数", async () => {
    const day = lit("2026-01-01", date);
    const one = from(Lips).limit(1);
    assert.equal(await live.db.one(one.select(() => daysBetween(day, addMonths(day, 1)))), 31);
    assert.equal(await live.db.one(one.select(() => toVarchar(addDays(day, 4), "YYYYMMDD"))), "20260105");
  });

  it("超时按整秒交给客户端，短于超时的语句照常完成", async () => {
    // 存储过程 SLOW_ROW 由 support/targets.ts 的 HANA 准备语句建出。
    const start = Date.now();
    await live.db.sql("CALL QUERY_IT.SLOW_ROW(1)").timeout(2500).list();
    assert.ok(Date.now() - start >= 1000, "the procedure slept its second");
  });

  it("带排序的 STRING_AGG 与提示子句", async () => {
    const rows = await live.db.list(
      from(Lips)
        .with(hint("NO_CS_JOIN"))
        .groupBy((l) => l.material)
        .orderBy((l) => l.material)
        .select((l) => ({ material: l.material, deliveries: stringAgg(l.delivery, ",", l.delivery.asc()) }))
        .map((r) => `${r.material}=${r.deliveries}`),
    );
    assert.deepEqual(rows, ["M1=D1,D2,D5", "M2=D3,D4"]);
  });

  it("分组集合与 CUBE", async () => {
    const sets = await live.db.list(
      from(Lips)
        .groupBy((l) => groupingSets([l.material], [])) // 按物料，加上总计
        .orderBy((l) => [grouping(l.material), l.material])
        .select((l) => ({ material: l.material, total: sum(l.quantity) })),
    );
    assert.deepEqual(sets.map((r) => `${r.material}=${Number(r.total)}`), ["M1=6", "M2=12", "null=18"]);
    assert.equal((await live.db.list(from(Lips).groupBy((l) => cube(l.material, l.unit)).select(() => count()))).length, 6, "material x unit, material, unit and the grand total");
  });

  it("GREATEST 与 LEAST 遇到 NULL 时返回 NULL", async () => {
    const rows = await live.db.list(from(Lips).orderBy((l) => l.delivery).select((l) => ({ delivery: l.delivery, high: greatest(l.quantity, "4"), low: least(l.quantity, "4") })));
    assert.deepEqual(rows.map((r) => `${r.delivery}:${Number(r.high)}:${Number(r.low)}`), ["D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"]);
    assert.equal(await live.db.one(from(Lips).where((l) => l.delivery.eq("D1")).select((l) => greatest(l.quantity, nullOf(decimal)))), null, "PostgreSQL 跳过 NULL，HANA 和 MySQL 返回 NULL");
  });

  it("TO_INTEGER 把文本转成整数", async () => {
    assert.deepEqual(await live.db.list(from(Lips).orderBy((l) => l.delivery).select((l) => toInteger(substring(l.delivery, 2)))), [1, 2, 3, 4, 5]);
  });

  it("联表更新一行匹配多行时整条语句报错且没有行被改", async () => {
    await live.db.execute(deleteFrom(Note).all());
    await live.db.execute(deleteFrom(Doc).all());
    await live.db.execute(insertInto(Note).values({ id: "N1", tenant: "T1", title: "first", qty: 1, status: "open" }, { id: "N2", tenant: "T1", title: null, qty: 2, status: "open" }));
    await live.db.execute(insertInto(Doc).values({ id: "D1", title: "first", version: 1 }, { id: "D2", title: "first", version: 1 }));
    // N1 的标题 first 对上两份文档；PostgreSQL、MySQL 会任取一行，HANA 的 MERGE 报 689
    await assert.rejects(live.db.execute(updateJoin(Note, Doc, (n, d) => n.title.eq(d.title)).set((_n, d) => ({ status: d.id })).all()), QueryExecutionError);
    assert.deepEqual(await live.db.list(from(Note).orderBy((n) => n.id).select((n) => n.status)), ["open", "open"]);
  });

  it("FULL JOIN 保留两侧没有匹配的行且有全局过滤条件时报错", async () => {
    // 不带过滤器时：6 张交货行各配上物料（客户端 100 和 200 的 M1 都配上），再加上没有交货的 M3、M4。
    const rows = await live.db.list(from(Lips).unfiltered().fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l, m) => ({ delivery: l.delivery, material: m.material })));
    assert.equal(rows.length, 12);
    assert.equal(rows.filter((r) => r.delivery === null).length, 2);
    await assert.rejects(live.db.list(from(Lips).fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l) => l.delivery)), /FULL JOIN cannot keep/, "a global filter has no side to go to");
  });

  it("中位数与窗口式的百分位数", async () => {
    const windows = await live.db.list(
      from(Lips)
        .distinct()
        .orderBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          median: percentileCont(0.5, l.quantity).over((w) => w.partitionBy(l.material)),
          lower: percentileDisc(0.5, l.quantity).over((w) => w.partitionBy(l.material)),
        })),
    );
    assert.deepEqual(windows.map((r) => `${r.material}:${r.median}:${Number(r.lower)}`), ["M1:2:2", "M2:6:5"]);
    const grouped = await live.db.list(
      from(Lips)
        .groupBy((l) => l.material)
        .orderBy((l) => l.material)
        .select((l) => ({ material: l.material, median: median(l.quantity), self: corr(l.quantity, l.quantity) })),
    );
    assert.deepEqual(grouped.map((r) => `${r.material}:${r.median}:${Math.round(r.self!)}`), ["M1:2:1", "M2:6:1"]);
  });

  it("HANA 没有 INTERSECT ALL", async () => {
    const q = from(Lips).select((l) => l.material);
    await assert.rejects(live.db.list(q.combine("INTERSECT ALL", q)), /HANA has no INTERSECT ALL/);
  });

  it("层级与它的后代和祖先", async () => {
    const tree = await live.db.list(from(hierarchy(orgSource())).orderBy((n) => n.hierarchyRank).select((n) => ({ nodeId: n.nodeId, level: n.hierarchyLevel, parent: n.hierarchyParentRank })));
    assert.deepEqual(tree.map((r) => `${r.nodeId}:${r.level}:${r.parent}`), ["A:1:0", "B:2:1", "C:3:2", "D:2:1"]);
    const below = await live.db.list(from(descendants(orgSource(), ["B"])).orderBy((n) => n.nodeId).select((n) => ({ nodeId: n.nodeId, distance: n.hierarchyDistance })));
    assert.deepEqual(below.map((r) => `${r.nodeId}:${r.distance}`), ["B:0", "C:1"]);
    const above = await live.db.list(from(ancestors(orgSource(), ["C"])).orderBy((n) => n.nodeId).select((n) => ({ nodeId: n.nodeId, distance: n.hierarchyDistance })));
    assert.deepEqual(above.map((r) => `${r.nodeId}:${r.distance}`), ["A:-2", "B:-1", "C:0"]);
  });

  it("表函数按命名实参调用", async () => {
    // 表函数不经过全局过滤器，所以客户端 200 的 D6 也在。
    assert.deepEqual(await live.db.list(fromFunction(LipsByUnit, { P_UNIT: "EA" }).orderBy((f) => f.delivery).select((f) => f.delivery)), ["D1", "D2", "D3", "D4", "D5", "D6"]);
  });

  it("全文检索与 JSON 取值", async () => {
    assert.deepEqual(await live.db.list(from(Lips).where((l) => contains("M1", l.material)).orderBy((l) => l.delivery).select((l) => l.delivery)), ["D1", "D2", "D5"]);
    const fuzzy = await live.db.list(from(Lips).where((l) => containsFuzzy(0.8, "D1", l.delivery)).select((l) => ({ delivery: l.delivery, score: score() })));
    assert.equal(fuzzy[0]!.delivery, "D1");
    assert.equal(fuzzy[0]!.score, 1);
    assert.equal(await live.db.one(from(Lips).limit(1).select(() => jsonValue(lit('{"a":{"b":"x"}}'), "$.a.b"))), "x");
  });

  it("按 options 建原生分区表且查询不变", async () => {
    await live.recreate(PartLog);
    await live.db.execute(insertInto(PartLog).values({ id: 1n, day: "2025-06-01", amount: "1" }, { id: 2n, day: "2026-06-01", amount: "10" }));
    assert.deepEqual(await live.db.list(from(PartLog).where((p) => p.day.ge("2026-01-01")).select((p) => p.id)), [2n]);
    const result = await live.driver.query("SELECT COUNT(*) FROM SYS.TABLE_PARTITIONS WHERE SCHEMA_NAME = 'QUERY_IT' AND TABLE_NAME = 'PART_LOG'", []);
    assert.equal(Number(result.rows[0]![0]), 3, "partitions of PART_LOG");
  });
});

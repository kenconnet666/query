import assert from "node:assert/strict";
import { Decimal } from "decimal.js";
import { describe, it } from "node:test";
import { decimal, Dialect, from, identifier, lit, sql, sqlCond, sqlTable, text } from "@zerodep-dev/query-core";
import { Lips, Mara } from "../fixtures/erp.js";
import { TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

describe("原生 SQL 片段", () => {
  it("参数可以混用表达式和值且 SELECT 列表里的值写成字面量", () => {
    const r = from(Lips)
      .where((l) => sql(text)`LPAD(${l.material}, ${18}, '0')`.eq("000000000000000001"))
      .select((l) => ({ padded: sql(text)`LPAD(${l.material}, ${18}, '0')` }))
      .toSql(DIALECT);
    assert.equal(r.sql, `SELECT LPAD(LIPS.MATNR, 18, '0') AS "padded"\nFROM QUERY_IT.LIPS LIPS\nWHERE LPAD(LIPS.MATNR, ?, '0') = ?`);
    assert.deepEqual(r.parameters, [18, "000000000000000001"]);
  });

  it("数组参数展开成多个占位符", () => {
    const r = from(Lips)
      .where((l) => sqlCond`${l.unit} IN (${["EA", "KG"]})`)
      .select((l) => l.delivery)
      .toSql(DIALECT);
    assert.equal(r.sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME IN (?, ?)');
    assert.deepEqual(r.parameters, ["EA", "KG"]);
  });

  it("按方言选最接近的写法", () => {
    abstract class Other extends Dialect {}
    const e = sql(text)`DEFAULT(${lit("x")})`
      .on(Other)`OTHER(${lit("x")})`
      .on(Dialect)`ANY(${lit("x")})`
      .on(TestDialect)`TEST(${lit("x")})`;
    assert.equal(from(Lips).select(() => ({ value: e })).toSql(DIALECT).sql.split("\n")[0], `SELECT TEST('x') AS "value"`);
    // sqlCond 一写下就在条件块里，.on(...) 改的是块里的这个条件
    assert.ok(from(Lips).where(() => sqlCond`1 = 1`.on(Dialect)`2 = 2`).select((l) => l.delivery).toSql(DIALECT).sql.endsWith("WHERE 2 = 2"));
  });

  it("sqlTable 作为数据源并可与表连接", () => {
    const totals = sqlTable({ material: text, total: decimal })`SELECT ${identifier("MATNR")} AS ${identifier("material")}, SUM(LFIMG) AS ${identifier("total")} FROM QUERY_IT.LIPS WHERE VRKME = ${"EA"} GROUP BY ${identifier("MATNR")}`;
    const r = from(totals)
      .join(Mara, (t, m) => m.material.eq(t.material))
      .where((t) => t.total.gt(new Decimal(1)))
      .select((t, m) => ({ partNumber: m.partNumber, total: t.total }))
      .toSql(DIALECT);
    assert.equal(
      r.sql,
      `SELECT MARA.MFRPN AS "partNumber", T."total" AS "total"
FROM (SELECT MATNR AS "material", SUM(LFIMG) AS "total" FROM QUERY_IT.LIPS WHERE VRKME = ? GROUP BY MATNR) T
JOIN QUERY_IT.MARA MARA ON MARA.MATNR = T."material"
WHERE T."total" > ?`,
    );
    assert.deepEqual(r.parameters, ["EA", "1"]);
  });

  it("空参数报错", () => {
    assert.throws(() => from(Lips).where(() => sqlCond`${lit(1)} IN (${[]})`).select((l) => l.delivery).toSql(DIALECT), /empty array/);
    assert.throws(() => identifier(" "));
  });
});

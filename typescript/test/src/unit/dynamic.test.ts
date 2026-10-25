import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { from, rowNumber } from "@zerodep-dev/query-core";
import { Lips } from "../fixtures/erp.js";
import { answering, TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

/** 按物料、单位、交货单前缀汇总数量。 */
const totals = () =>
  from(Lips)
    .groupBy((l) => [l.material, l.unit, l.delivery.substring(1, 1)])
    .orderBy((l) => [l.material.asc(), l.unit.asc()])
    .select((l) => ({ material: l.material, unit: l.unit, prefix: l.delivery.substring(1, 1), total: l.quantity.sum() }));

describe("动态列与排序", () => {
  it("排除的列写成 NULL 且只被它用到的关系不再连接", () => {
    const q = from(Lips)
      .select((l) => ({ delivery: l.delivery, partNumber: l.mara.partNumber, text: l.text.text }))
      .exclude("partNumber");
    assert.equal(
      q.toSql(DIALECT).sql,
      `SELECT LIPS.VBELN AS "delivery", NULL AS "partNumber", MAKT.MAKTX AS "text"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?`,
    );
    assert.deepEqual(q.labels, ["delivery", "partNumber", "text"]);
  });

  it("排除分组的维度时分组键和按它的排序一起去掉", () => {
    assert.equal(
      totals().exclude("unit", "prefix").toSql(DIALECT).sql,
      `SELECT LIPS.MATNR AS "material", NULL AS "unit", NULL AS "prefix", SUM(LIPS.LFIMG) AS "total"
FROM QUERY_IT.LIPS LIPS
GROUP BY LIPS.MATNR
ORDER BY LIPS.MATNR ASC`,
    );
  });

  it("only 按用户选的列取值且空列表保持不变", () => {
    const q = totals();
    const sql = q.only(["PREFIX", "total"]).toSql(DIALECT).sql;
    assert.ok(sql.endsWith("\nGROUP BY SUBSTRING(LIPS.VBELN, 1, 1)"), sql);
    assert.equal(q.only([]), q);
    assert.equal(q.only(null), q);
    assert.throws(() => q.only(["material", "plant"]), { message: "only(...): unknown column 'plant'; the query has [material, unit, prefix, total]" });
  });

  it("excludeIf 只在条件成立时排除", () => {
    const q = totals();
    assert.equal(q.excludeIf(false, "unit"), q);
    assert.ok(q.excludeIf(true, "unit").toSql(DIALECT).sql.startsWith('SELECT LIPS.MATNR AS "material", NULL AS "unit"'));
  });

  it("排除嵌套对象的键去掉它下面的全部列，读出来是 null", async () => {
    const q = from(Lips)
      .select((l) => ({ delivery: l.delivery, stock: { quantity: l.quantity, unit: l.unit } }))
      .exclude("stock");
    assert.ok(q.toSql(DIALECT).sql.startsWith('SELECT LIPS.VBELN AS "delivery", NULL AS "stock.quantity", NULL AS "stock.unit"'));
    const { db } = answering([], ["D1", null, null]);
    assert.deepEqual(await db.list(q), [{ delivery: "D1", stock: { quantity: null, unit: null } }]);
  });

  it("sortBy 按结果列替换原来的排序", () => {
    const sql = totals().sortBy("total desc, Material").toSql(DIALECT).sql;
    assert.ok(sql.endsWith("ORDER BY SUM(LIPS.LFIMG) DESC, LIPS.MATNR ASC"), sql);
    const q = totals();
    assert.equal(q.sortBy(" "), q);
    assert.equal(q.sortBy(undefined), q);
  });

  it("sortBy 认减号表示倒序", () => {
    const sql = totals().sortBy("-total,material").toSql(DIALECT).sql;
    assert.ok(sql.endsWith("ORDER BY SUM(LIPS.LFIMG) DESC, LIPS.MATNR ASC"), sql);
  });

  it("filterBy 按结果列筛选：逐行计算的列进 WHERE，聚合的列进 HAVING", () => {
    const sql = totals().filterBy({ total: ["10.5", " "], Material: ["M1", "M2"], unit: [] }).toSql(DIALECT).sql;
    assert.ok(sql.includes("\nWHERE LIPS.MATNR IN (?, ?)\nGROUP BY "), sql);
    assert.ok(sql.includes("\nHAVING SUM(LIPS.LFIMG) IN (10.5)\n"), sql);
    const q = totals();
    assert.equal(q.filterBy(undefined), q);
    assert.equal(q.filterBy({ unit: [" "] }).toSql(DIALECT).sql, q.toSql(DIALECT).sql);
  });

  it("拼接文字的聚合也进 HAVING；窗口函数的列不能筛选", () => {
    const grouped = from(Lips)
      .groupBy((l) => [l.material])
      .select((l) => ({ material: l.material, deliveries: l.delivery.stringAgg(",") }));
    const sql = grouped.filterBy({ deliveries: ["D1,D2"] }).toSql(DIALECT).sql;
    assert.ok(sql.endsWith("HAVING STRING_AGG(LIPS.VBELN, ',') IN ('D1,D2')"), sql);
    const ranked = from(Lips).select((l) => ({ delivery: l.delivery, rn: rowNumber().over((w) => w.orderBy(l.delivery.asc())) }));
    assert.throws(() => ranked.filterBy({ rn: ["1"] }).toSql(DIALECT), /window function/);
  });

  it("searchBy 按文字列的关键字筛选且保留它用到的关系", () => {
    const q = from(Lips).select((l) => ({ delivery: l.delivery, partNumber: l.mara.partNumber, quantity: l.quantity }));
    const r = q.searchBy({ partNumber: "50%" }).toSql(DIALECT);
    assert.ok(r.sql.endsWith("LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT\nWHERE MARA.MFRPN LIKE ? ESCAPE '!'"), r.sql);
    assert.deepEqual(r.parameters, ["%50!%%"]);
    assert.throws(() => q.searchBy({ quantity: "1" }), /not a text column/);
    assert.throws(() => q.filterBy({ plant: ["1000"] }), /unknown column 'plant'/);
    assert.throws(() => q.exclude("quantity").filterBy({ quantity: ["1"] }), /excluded/);
    assert.throws(() => q.filterBy({ quantity: ["abc"] }), /"abc" is not a decimal number/);
  });

  it("sortBy 只认结果列和 asc 与 desc", () => {
    const q = totals();
    for (const bad of ["plant", "total; DROP TABLE x", "total sideways", "total desc nulls", "-total desc", "-", "--total"]) assert.throws(() => q.sortBy(bad), Error, bad);
    assert.throws(() => q.exclude("total").sortBy("total"), /excluded/);
    const units = () => from(Lips).select((l) => ({ unit: l.unit }));
    assert.throws(() => units().unionAll(units()).sortBy("unit"), /single SELECT/);
  });
});

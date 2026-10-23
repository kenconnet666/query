import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { from, substring, sum } from "@zerodep-dev/query-core";
import { Lips } from "../fixtures/erp.js";
import { answering, TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

/** 按物料、单位、交货单前缀汇总数量。 */
const totals = () =>
  from(Lips)
    .groupBy((l) => [l.material, l.unit, substring(l.delivery, 1, 1)])
    .orderBy((l) => [l.material.asc(), l.unit.asc()])
    .select((l) => ({ material: l.material, unit: l.unit, prefix: substring(l.delivery, 1, 1), total: sum(l.quantity) }));

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

  it("sortBy 只认结果列和 asc 与 desc", () => {
    const q = totals();
    for (const bad of ["plant", "total; DROP TABLE x", "total sideways", "total desc nulls"]) assert.throws(() => q.sortBy(bad), Error, bad);
    assert.throws(() => q.exclude("total").sortBy("total"), /excluded/);
    const units = () => from(Lips).select((l) => ({ unit: l.unit }));
    assert.throws(() => units().unionAll(units()).sortBy("unit"), /single SELECT/);
  });
});

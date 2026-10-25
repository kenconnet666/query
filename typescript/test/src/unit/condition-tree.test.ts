import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { from, parseConditionTree, parseTableRequest, type ConditionTree } from "@zerodep-dev/query-core";
import { Lips } from "../fixtures/erp.js";
import { TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

/** 交货单的几列：数量是小数列，单位、交货单是文字列，件号经多对一关系 */
const rows = () =>
  from(Lips).select((l) => ({ delivery: l.delivery, unit: l.unit, quantity: l.quantity, partNumber: l.mara.partNumber }));

/** 按物料汇总：total 是聚合列 */
const totals = () =>
  from(Lips)
    .groupBy((l) => [l.material])
    .select((l) => ({ material: l.material, total: l.quantity.sum() }));

const sqlOf = (tree: ConditionTree | string) => rows().conditionBy(tree).toSql(DIALECT);

describe("条件树（conditionBy）", () => {
  it("最外层的 all 拆成几条进 WHERE；值按列的类型读；用到的关系照常连接", () => {
    const r = sqlOf({
      all: [
        { field: "quantity", op: "gte", value: "10.5" },
        { field: "partNumber", op: "startsWith", value: "50" },
      ],
    });
    assert.ok(r.sql.includes("LEFT JOIN QUERY_IT.MARA MARA"), r.sql);
    assert.ok(r.sql.endsWith("WHERE LIPS.LFIMG >= ? AND MARA.MFRPN LIKE ? ESCAPE '!'"), r.sql);
    assert.equal(String(r.parameters[0]), "10.5");
    assert.deepEqual(r.parameters.slice(1), ["50%"]);
  });

  it("不等于、不在其中、不包含把空值算作满足（同前端的 matchesCondition）", () => {
    const r = sqlOf({
      any: [
        { field: "unit", op: "ne", value: "EA" },
        { field: "unit", op: "notIn", value: ["KG", "M"] },
        { field: "delivery", op: "notContains", value: "D" },
      ],
    });
    assert.ok(r.sql.includes("((LIPS.VRKME <> ?) OR (LIPS.VRKME IS NULL))"), r.sql);
    assert.ok(r.sql.includes("((LIPS.VRKME NOT IN (?, ?)) OR (LIPS.VRKME IS NULL))"), r.sql);
    assert.ok(r.sql.includes("((NOT (LIPS.VBELN LIKE ? ESCAPE '!')) OR (LIPS.VBELN IS NULL))"), r.sql);
  });

  it("有值、为空：文字列把空字符串也算作空；not 写成 NOT (…)", () => {
    const r = sqlOf({ all: [{ field: "unit", op: "exists" }, { not: { field: "delivery", op: "empty" } }] });
    assert.ok(r.sql.endsWith("WHERE LIPS.VRKME IS NOT NULL AND LIPS.VRKME <> ? AND NOT (((LIPS.VBELN IS NULL) OR (LIPS.VBELN = ?)))"), r.sql);
    assert.deepEqual(r.parameters, ["", ""]);
  });

  it("介于：两端都含，空的一端不加条件；是其中之一写成 IN", () => {
    const r = sqlOf({
      all: [
        { field: "quantity", op: "between", value: [1, null] },
        { field: "unit", op: "in", value: ["EA", " ", "KG"] },
      ],
    });
    assert.ok(r.sql.endsWith("WHERE LIPS.LFIMG >= ? AND LIPS.VRKME IN (?, ?)"), r.sql);
  });

  it("没填完整的比较、空的组不加条件；整棵都空时保持不变", () => {
    const q = rows();
    const base = q.toSql(DIALECT).sql;
    for (const tree of [
      { all: [] },
      { any: [{ field: "unit", op: "eq", value: " " }] },
      { all: [{ field: "quantity", op: "between", value: [null, ""] }, { field: "unit", op: "in", value: [] }] },
      { not: { all: [] } },
    ] as ConditionTree[]) {
      assert.equal(q.conditionBy(tree).toSql(DIALECT).sql, base, JSON.stringify(tree));
    }
    assert.equal(q.conditionBy(undefined), q);
    assert.equal(q.conditionBy(" "), q);
  });

  it("聚合列进 HAVING；一个分支里混着逐行的列与聚合的列时报错", () => {
    const sql = totals().conditionBy({ all: [{ field: "material", op: "eq", value: "M1" }, { field: "total", op: "gt", value: 5 }] }).toSql(DIALECT).sql;
    assert.ok(sql.includes("\nWHERE LIPS.MATNR = ?\nGROUP BY "), sql);
    // 分组查询里的常量写成字面量（HANA 给不了参数类型，见 0016）
    assert.ok(sql.endsWith("HAVING SUM(LIPS.LFIMG) > 5"), sql);
    const mixed = totals().conditionBy({ any: [{ field: "material", op: "eq", value: "M1" }, { field: "total", op: "gt", value: 5 }] });
    assert.throws(() => mixed.toSql(DIALECT), /mixes plain and aggregated columns/);
  });

  it("只认结果列；文字比较只能用在文字列上；形状或比较方式不对时报 TypeError 并指出位置", () => {
    assert.throws(() => sqlOf({ field: "plant", op: "eq", value: "1000" }), /unknown column 'plant'/);
    assert.throws(() => sqlOf({ field: "quantity", op: "contains", value: "1" }), /needs a text column/);
    assert.throws(() => parseConditionTree({ all: [{ field: "unit", op: "like", value: "%" }] }), /condition\.all\[0\]\.op: unknown operator "like"/);
    assert.throws(() => parseConditionTree({ any: "x" }), /condition\.any: expected an array/);
    assert.throws(() => parseConditionTree([]), /condition: expected an object/);
    let deep: ConditionTree = { field: "unit", op: "exists" };
    for (let i = 0; i < 40; i++) deep = { not: deep };
    assert.throws(() => parseConditionTree(deep), /nested more than 32 levels/);
    assert.throws(() => sqlOf("{not json"), SyntaxError);
  });

  it("parseTableRequest 读 condition 参数（JSON 文字）", () => {
    const condition = { any: [{ field: "unit", op: "eq", value: "EA" }] };
    const request = parseTableRequest({ page: "1", condition: JSON.stringify(condition) });
    assert.deepEqual(request.condition, condition);
    assert.equal(parseTableRequest({}).condition, undefined);
    assert.throws(() => parseTableRequest({ condition: '{"field":"unit","op":"nope"}' }), TypeError);
  });
});

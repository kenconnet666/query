import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { all, contains, endsWith, from, ignore, startsWith, where } from "@zerodep-dev/query-core";
import { Lips } from "../fixtures/erp.js";
import { TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

/** 交货查询的请求参数。 */
class DeliverySearch {
  material?: string[];
  @where("ge", "createdOn") from?: string;
  @where("lt", "createdOn") to?: string;
  @where("contains", "mara.partNumber", "text.text") keyword?: string;
  @where("ne") unit?: string;
  @ignore sort?: string;
}

const search = (values: Partial<DeliverySearch>) => Object.assign(new DeliverySearch(), values);
const render = (s: DeliverySearch) => from(Lips).where(s).select((l) => l.delivery).toSql(DIALECT);

describe("条件对象", () => {
  it("每个字段按装饰器变成条件且关系按需连接", () => {
    const r = render(search({ material: ["M1", "M2"], from: "2026-01-01", to: "2026-07-01", keyword: "50%", unit: "KG", sort: "x" }));
    assert.equal(
      r.sql,
      `SELECT LIPS.VBELN AS "delivery"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
WHERE LIPS.MATNR IN (?, ?) AND LIPS.ERDAT >= ? AND LIPS.ERDAT < ? AND ((MARA.MFRPN LIKE ? ESCAPE '!') OR (MAKT.MAKTX LIKE ? ESCAPE '!')) ` +
        `AND LIPS.VRKME <> ?`,
    );
    assert.deepEqual(r.parameters, ["1", "M1", "M2", "20260101", "20260701", "%50!%%", "%50!%%", "KG"]);
  });

  it("值为空的字段不产生条件也不连接关系", () => {
    assert.equal(render(search({ material: [], keyword: " " })).sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS');
  });

  it("普通对象按同名列比较，可与回调条件一起用", () => {
    const sql = from(Lips)
      .where({ unit: ["EA", "KG"], salesOrder: "S1" })
      .where((l) => l.quantity.gt("1"))
      .select((l) => l.delivery)
      .toSql(DIALECT).sql;
    assert.equal(sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME IN (?, ?) AND LIPS.VGBEL = ? AND LIPS.LFIMG > ?');
  });

  it("字段名找不到列时无论有没有值都报错", () => {
    assert.throws(() => from(Lips).where({ plant: undefined }), { message: "filter.plant: the query's tables have no plant" });
    class ToRelation {
      @where("eq", "mara") mara?: string;
    }
    assert.throws(() => from(Lips).where(Object.assign(new ToRelation(), { mara: "x" })), { message: "ToRelation.mara: mara is a relation, not a column" });
    class RangeOfList {
      @where("ge") unit?: string[];
    }
    assert.throws(() => from(Lips).where(Object.assign(new RangeOfList(), { unit: ["EA"] })), /an array can only be compared/);
  });

  it("contains 等转义通配符且空值按 skipEmpty 处理", () => {
    const r = from(Lips)
      .skipEmpty()
      .where((l) => all(startsWith(l.unit, "a_b"), endsWith(l.unit, "c!"), contains(l.unit, null)))
      .select((l) => l.delivery)
      .toSql(DIALECT);
    assert.equal(r.sql, `SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME LIKE ? ESCAPE '!' AND LIPS.VRKME LIKE ? ESCAPE '!'`);
    assert.deepEqual(r.parameters, ["a!_b%", "%c!!"]);
    assert.throws(() => from(Lips).where((l) => contains(l.unit, null)), TypeError);
  });
});

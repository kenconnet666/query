import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseDate, type CalendarDate } from "@internationalized/date";
import { from, ignore, where, type Expr, type Range } from "@zerodep-dev/query-core";
import { Decimal } from "decimal.js";
import { Lips, Mara } from "../fixtures/erp.js";
import { Task } from "../fixtures/workflow.js";
import { TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

/** 交货查询的请求参数。 */
class DeliverySearch {
  material?: string[];
  @where("ge", "createdOn") from?: CalendarDate;
  @where("lt", "createdOn") to?: CalendarDate;
  @where("contains", "mara.partNumber", "text.text") keyword?: string;
  @where("ne") unit?: string;
  @ignore sort?: string;
}

const search = (values: Partial<DeliverySearch>) => Object.assign(new DeliverySearch(), values);
const render = (s: DeliverySearch) => from(Lips).where(s).select((l) => l.delivery).toSql(DIALECT);

describe("条件对象", () => {
  it("每个字段按装饰器变成条件且关系按需连接", () => {
    const r = render(search({ material: ["M1", "M2"], from: parseDate("2026-01-01"), to: parseDate("2026-07-01"), keyword: "50%", unit: "KG", sort: "x" }));
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

  it("自定义比较由函数指定且字段为空时不调用", () => {
    const inMonth = (column: Expr<CalendarDate | null>, month: string): void => {
      const start = parseDate(`${month}-01`);
      column.range(start, start.add({ months: 1 }));
    };
    class Search {
      @where(inMonth, "createdOn") month?: string | null;
    }
    const r = from(Lips).where(Object.assign(new Search(), { month: "2026-01" })).select((l) => l.delivery).toSql(DIALECT);
    assert.ok(r.sql.endsWith("WHERE LIPS.ERDAT >= ? AND LIPS.ERDAT < ?"), r.sql);
    assert.deepEqual(r.parameters, ["20260101", "20260201"]);
    assert.equal(from(Lips).where(Object.assign(new Search(), { month: null })).select((l) => l.delivery).toSql(DIALECT).sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS');
  });

  it("值为空的字段不产生条件也不连接关系", () => {
    assert.equal(render(search({ material: [], keyword: " " })).sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS');
  });

  it("普通对象按同名列比较，可与回调条件一起用", () => {
    const sql = from(Lips)
      .where({ unit: ["EA", "KG"], salesOrder: "S1" })
      .where((l) => l.quantity.gt(new Decimal(1)))
      .select((l) => l.delivery)
      .toSql(DIALECT).sql;
    assert.equal(sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME IN (?, ?) AND LIPS.VGBEL = ? AND LIPS.LFIMG > ?');
  });

  it("经过一对多关系的字段写成 EXISTS，同一关系的字段合并", () => {
    class Search {
      @where("eq", "deliveries.unit") unit?: string;
      @where("ge", "deliveries.quantity") min?: Decimal;
      @where("eq", "deliveries.delivery", "partNumber") code?: string;
    }
    const render = (s: Partial<Search>) => from(Mara).where(Object.assign(new Search(), s)).select((m) => m.material).toSql(DIALECT).sql;
    assert.equal(
      render({ unit: "EA", min: new Decimal(10), code: "D1" }),
      `SELECT MARA.MATNR AS "material"
FROM QUERY_IT.MARA MARA
WHERE ((EXISTS (SELECT 1
FROM QUERY_IT.LIPS LIPS
WHERE LIPS.MATNR = MARA.MATNR AND LIPS.MANDT = MARA.MANDT AND LIPS.VBELN = ?)) OR (MARA.MFRPN = ?)) AND EXISTS (SELECT 1
FROM QUERY_IT.LIPS LIPS2
WHERE LIPS2.MATNR = MARA.MATNR AND LIPS2.MANDT = MARA.MANDT AND LIPS2.VRKME = ? AND LIPS2.LFIMG >= ?)`,
    );
    assert.equal(render({ code: " " }), `SELECT MARA.MATNR AS "material"
FROM QUERY_IT.MARA MARA`);
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
      .where((l) => {
        l.unit.startsWith("a_b");
        l.unit.endsWith("c!");
        l.unit.contains(null);
      })
      .select((l) => l.delivery)
      .toSql(DIALECT);
    assert.equal(r.sql, `SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME LIKE ? ESCAPE '!' AND LIPS.VRKME LIKE ? ESCAPE '!'`);
    assert.deepEqual(r.parameters, ["a!_b%", "%c!!"]);
    assert.throws(() => from(Lips).where((l) => l.unit.contains(null)), TypeError);
  });
});

describe("条件对象的区间与文字", () => {
  it("区间两端都含，空的一端不加条件；{ start, end } 与两项的数组都行", () => {
    class Search {
      @where("range", "createdOn") created?: Range<CalendarDate>;
      @where("range", "quantity") quantity?: (string | null)[];
    }
    const s = Object.assign(new Search(), { created: { start: parseDate("2026-01-01"), end: parseDate("2026-01-31") }, quantity: ["1.5", " "] });
    const r = from(Lips).where(s).select((l) => l.delivery).toSql(DIALECT);
    assert.ok(r.sql.endsWith("WHERE LIPS.ERDAT >= ? AND LIPS.ERDAT <= ? AND LIPS.LFIMG >= ?"), r.sql);
    assert.deepEqual(r.parameters, ["20260101", "20260131", "1.5"]);
    const empty = Object.assign(new Search(), { created: { start: null, end: null }, quantity: [] });
    assert.equal(from(Lips).where(empty).select((l) => l.delivery).toSql(DIALECT).sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS');
  });

  it("日期的一端落在时间戳列上时，止端写成早于后一天的零点", () => {
    class Search {
      @where("range", "createTime") created?: Range<CalendarDate>;
      @where("range", "createTime") at?: string[];
    }
    const s = Object.assign(new Search(), { created: { start: parseDate("2026-01-01"), end: parseDate("2026-01-31") }, at: ["2026-02-01T08:30", "2026-02-03"] });
    const r = from(Task).where(s).select((t) => t.id).toSql(DIALECT);
    assert.ok(r.sql.endsWith("WHERE TASK.CREATE_TIME >= ? AND TASK.CREATE_TIME < ? AND TASK.CREATE_TIME >= ? AND TASK.CREATE_TIME < ?"), r.sql);
    assert.deepEqual(r.parameters, ["2026-01-01 00:00:00", "2026-02-01 00:00:00", "2026-02-01 08:30:00", "2026-02-04 00:00:00"]);
  });

  it("文字按列的类型读（地址栏参数）；区间要两个值", () => {
    const r = from(Lips).where({ quantity: "2.50", material: ["M1", " "] }).select((l) => l.delivery).toSql(DIALECT);
    assert.ok(r.sql.endsWith("WHERE LIPS.LFIMG = ? AND LIPS.MATNR IN (?)"), r.sql);
    assert.deepEqual(r.parameters.slice(-2), ["2.5", "M1"]);
    class Three {
      @where("range", "quantity") quantity?: string[];
    }
    assert.throws(() => from(Lips).where(Object.assign(new Three(), { quantity: ["1", "2", "3"] })), /Three\.quantity: a range needs/);
    assert.throws(() => from(Lips).where({ quantity: "abc" }), /"abc" is not a decimal number/);
  });
});

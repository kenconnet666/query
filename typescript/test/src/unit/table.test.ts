import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseDate } from "@internationalized/date";
import { dryRunDriver, from, MAX_PAGE_SIZE, parseTableRequest, rowNumber, toWire, type Query } from "@zerodep-dev/query-core";
import { Decimal } from "decimal.js";
import { Lips } from "../fixtures/erp.js";
import { TestDatabase } from "../fixtures/support.js";

describe("表格查询的请求", () => {
  it("读地址栏参数（对象或 URLSearchParams），其余参数不管", () => {
    const expected = { page: 2, pageSize: 20, sort: "-total,material", total: 368, filter: { unit: ["EA", "KG"] }, search: { material: "泵" }, condition: undefined };
    const object = { page: "2", pageSize: "20", sort: "-total,material", total: "368", "filter.unit": ["EA", "KG"], "filter.material": " ", "search.material": " 泵 ", keyword: "x" };
    assert.deepEqual(parseTableRequest(object), expected);
    const search = new URLSearchParams("page=2&pageSize=20&sort=-total,material&total=368&filter.unit=EA&filter.unit=KG&search.material=%E6%B3%B5&keyword=x");
    assert.deepEqual(parseTableRequest(search), expected);
    assert.deepEqual(parseTableRequest({}), { page: 1, pageSize: 10, sort: undefined, total: undefined, filter: {}, search: {}, condition: undefined });
  });

  it("写错的数字和过大的条数报错", () => {
    assert.throws(() => parseTableRequest({ page: "x" }), /page must be an integer, got 'x'/);
    assert.throws(() => parseTableRequest({ page: "0" }), /page must be at least 1/);
    assert.throws(() => parseTableRequest({ pageSize: String(MAX_PAGE_SIZE + 1) }), /pageSize must be at most 2000/);
    assert.equal(parseTableRequest({ pageSize: "5000" }, { maxPageSize: 5000 }).pageSize, 5000);
    assert.throws(() => parseTableRequest({ total: "-1" }), /total must not be negative/);
  });

  it("db.table 依次筛选、排序、分页；有已知总数时不再 COUNT", async () => {
    const db = new TestDatabase({}, dryRunDriver());
    const q = from(Lips).select((l) => ({ delivery: l.delivery, unit: l.unit, quantity: l.quantity }));
    const events = await db.capture(() => db.table(q, parseTableRequest({ page: "2", pageSize: "5", sort: "-quantity", "filter.unit": "EA" })));
    assert.equal(events.length, 2);
    const rows = events.find((e) => e.statement.sql.includes("ORDER BY"))!.statement.sql;
    assert.ok(rows.includes("WHERE LIPS.VRKME IN (?)") && rows.includes("ORDER BY LIPS.LFIMG DESC"), rows);
    assert.equal((await db.capture(() => db.table(q, parseTableRequest({ total: "12" })))).length, 1);
  });

  it("计数直接数行：去掉排序、只为投影连接的关系和子查询；WHERE 用到的关系留下", async () => {
    const db = new TestDatabase({}, dryRunDriver());
    const countSql = async (q: Query<unknown>) => (await db.capture(() => db.count(q)))[0]!.statement.sql;
    const q = from(Lips)
      .where((l) => l.unit.eq("EA"))
      .orderBy((l) => l.delivery)
      .select((l) => ({ delivery: l.delivery, partNumber: l.mara.partNumber, lines: l.mara.deliveries.count(), rn: rowNumber().over((w) => w.orderBy(l.delivery.asc())) }));
    assert.equal(await countSql(q), "SELECT COUNT(*)\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME = ?");
    const byPart = from(Lips).where((l) => l.mara.partNumber.eq("P1")).select((l) => l.delivery);
    assert.equal(await countSql(byPart), "SELECT COUNT(*)\nFROM QUERY_IT.LIPS LIPS\nLEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT\nWHERE MARA.MFRPN = ?");
  });

  it("分组、去重、没有分组的聚合、分页与合并的查询仍然包一层", async () => {
    const db = new TestDatabase({}, dryRunDriver());
    const countSql = async (q: Query<unknown>) => (await db.capture(() => db.count(q)))[0]!.statement.sql;
    const units = () => from(Lips).select((l) => ({ unit: l.unit }));
    for (const q of [
      from(Lips).groupBy((l) => [l.unit]).select((l) => l.unit),
      from(Lips).distinct().select((l) => l.unit),
      from(Lips).select((l) => l.quantity.sum()),
      from(Lips).select((l) => l.delivery.stringAgg(",")),
      from(Lips).limit(5).select((l) => l.delivery),
      units().unionAll(units()),
    ] as Query<unknown>[]) {
      assert.ok((await countSql(q)).startsWith("SELECT COUNT(*) FROM ("), await countSql(q));
    }
  });

  it("一页结果经 toWire 写成线上的 JSON：total 是数字，行里的小数与日期是文字", () => {
    const page = { rows: [{ delivery: "D1", quantity: new Decimal("2.5"), on: parseDate("2026-01-31") }], total: 12, page: 2, size: 5, totalPages: 3 };
    assert.deepEqual(toWire(page), {
      rows: [{ delivery: "D1", quantity: "2.5", on: "2026-01-31" }],
      total: 12,
      page: 2,
      size: 5,
      totalPages: 3,
      $types: { "rows.quantity": "decimal", "rows.on": "date" },
    });
  });
});

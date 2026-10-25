import { parseDate, type CalendarDate } from "@internationalized/date";
import assert from "node:assert/strict";
import { it } from "node:test";
import { from, parseTableRequest, where, type Range } from "@zerodep-dev/query-core";
import { Lips } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/** 区间的条件对象：交货的创建日期两端都含。 */
class CreatedSearch {
  @where("range", "createdOn") created?: Range<CalendarDate>;
}

/**
 * 表格查询（0016）：表头筛选、关键字、排序、分页、已知总数，分组后按聚合筛选，区间，直接数行。数据见 support/sample-data.ts。
 * 与 Java 的 TableQueryTest 对应。
 */
liveSuite("表格查询", (live) => {
  const lines = () => from(Lips).select((l) => ({ delivery: l.delivery, material: l.material, quantity: l.quantity, partNumber: l.mara.partNumber }));

  it("db.table 按表头筛选、排序、分页；带上已知总数时不再数", async () => {
    // 客户端 100 里 M1 有 D1（3）、D2（2）、D5（1）。
    const first = await live.db.table(lines(), parseTableRequest({ "filter.material": "M1", sort: "-quantity", pageSize: "2" }));
    assert.deepEqual([first.rows.map((r) => r.delivery), first.total, first.totalPages], [["D1", "D2"], 3, 2]);
    const second = await live.db.table(lines(), parseTableRequest({ "filter.material": "M1", sort: "-quantity", pageSize: "2", page: "2", total: "3" }));
    assert.deepEqual([second.rows.map((r) => r.delivery), second.total], [["D5"], 3]);
  });

  it("关键字按文字包含筛选，经过的多对一关系照常连接", async () => {
    // 零件号里有 2 的只有 M2（P2）：D3、D4。
    const page = await live.db.table(lines(), parseTableRequest({ "search.partNumber": "2", sort: "delivery" }));
    assert.deepEqual([page.rows.map((r) => r.delivery), page.total], [["D3", "D4"], 2]);
  });

  it("分组后按聚合的列筛选写进 HAVING；数行数时去掉只为投影连接的关系", async () => {
    const totals = from(Lips)
      .groupBy((l) => [l.material])
      .orderBy((l) => l.material)
      .select((l) => ({ material: l.material, total: l.quantity.sum() }));
    // M1 合计 6，M2 合计 12。
    assert.deepEqual((await live.db.list(totals.filterBy({ total: ["6"] }))).map((r) => r.material), ["M1"]);
    assert.equal(await live.db.count(totals), 2);
    assert.equal(await live.db.count(lines()), 5);
  });

  it("区间两端都含，空的一端不加条件", async () => {
    const deliveries = (created: Range<CalendarDate>) =>
      live.db.list(from(Lips).where(Object.assign(new CreatedSearch(), { created })).orderBy((l) => l.delivery).select((l) => l.delivery));
    assert.deepEqual(await deliveries({ start: parseDate("2026-01-15"), end: parseDate("2026-01-20") }), ["D1", "D2"]);
    assert.deepEqual(await deliveries({ start: parseDate("2026-02-01"), end: null }), ["D3", "D5"]);
  });
});

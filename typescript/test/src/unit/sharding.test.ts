import { Decimal } from "decimal.js";
import { parseDate } from "@internationalized/date";
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { any, deleteFrom, exists, from, insertInto, modulo, update, type Rendered } from "@zerodep-dev/query-core";
import { Mara } from "../fixtures/erp.js";
import { SHARDS, ShEvent, ShOrder } from "../fixtures/shards.js";
import { TestDatabase } from "../fixtures/support.js";

const db = new TestDatabase({ shards: SHARDS });

/** 语句里出现的物理表名，按出现顺序。 */
const tables = (statements: readonly Rendered[]) => statements.map((r) => /SH_\w+_\d+/.exec(r.sql)![0]);

describe("同库分表", () => {
  it("条件推不出分表时报错", () => {
    assert.throws(() => db.render(from(ShEvent).where((x) => x.note.eq("a")).select((x) => x.id)), /allShards\(\)/);
    assert.throws(() => db.render(from(ShEvent).where((x) => any(() => {
      x.day.eq(parseDate("2026-01-03"));
      x.note.eq("a");
    })).select((x) => x.id)));
  });

  it("AND 取交集 OR 取并集且范围外的月份不读", () => {
    const sql = db.render(from(ShEvent).where((x) => {
      x.day.ge(parseDate("2025-06-01"));
      x.day.le(parseDate("2026-02-01"));
      x.note.eq("a");
    }).select((x) => x.id)).sql;
    assert.ok(sql.includes("SH_EVENT_202601") && sql.includes("SH_EVENT_202602") && !sql.includes("SH_EVENT_202603"), sql);
    const none = db.render(from(ShEvent).where((x) => x.day.lt(parseDate("2026-01-01"))).select((x) => x.id)).sql;
    assert.ok(none.includes("WHERE 1 = 0"), none);
  });

  it("一段 SELECT 里只能有一张分表", () => {
    assert.throws(() => db.render(from(ShEvent).join(ShOrder, (e, o) => o.id.eq(e.id)).allShards().select((e) => e.id)), /Only one sharded table/);
  });

  it("子查询沿用外层的 allShards", () => {
    const sql = db.render(from(Mara).allShards().where((m) => exists(from(ShEvent).where((e) => e.note.eq(m.material)))).select((m) => m.material)).sql;
    assert.ok(sql.includes("SH_EVENT_202601") && sql.includes("SH_EVENT_202603"), sql);
  });

  it("onShards 只读列出的表且不认识的表报错", () => {
    const sql = db.render(from(ShOrder).onShards("SH_ORDER_0").select((o) => o.id)).sql;
    assert.ok(sql.includes("SH_ORDER_0") && !sql.includes("SH_ORDER_1"), sql);
    assert.throws(() => db.render(from(ShOrder).onShards("SH_ORDER_9").select((o) => o.id)), /not a shard/);
  });

  it("插入按行写到各自的分表", () => {
    const statements = db.render(insertInto(ShEvent).values({ id: 1n, day: parseDate("2026-01-05"), amount: new Decimal("1"), note: "a" }, { id: 2n, day: parseDate("2026-03-05"), amount: new Decimal("1"), note: "b" }));
    assert.deepEqual(tables(statements), ["SH_EVENT_202601", "SH_EVENT_202603"]);
    assert.throws(() => db.render(insertInto(ShEvent).values({ id: 3n, day: parseDate("2026-05-01"), amount: null, note: null })), /outside the shards/);
  });

  it("按主键更新按行里的分表键选表且不能改分表键", () => {
    assert.deepEqual(tables(db.render(update(ShEvent).byId({ id: 1n, day: parseDate("2026-02-05"), amount: new Decimal("10"), note: "a" }))), ["SH_EVENT_202602"]);
    assert.throws(() => db.render(update(ShEvent).set(() => ({ day: parseDate("2026-01-01") })).allShards().all()), /shard key/);
    assert.throws(() => db.render(update(ShEvent).byId({ id: 1n, day: parseDate("2026-02-05"), amount: new Decimal("10"), note: "a" }).diffFrom({ id: 1n, day: parseDate("2026-01-05"), amount: new Decimal("10"), note: "a" })), /shard key/);
  });

  it("按条件删除每张分表一条", () => {
    assert.deepEqual(tables(db.render(deleteFrom(ShOrder).where((o) => o.customer.in([2, 3])))), ["SH_ORDER_0", "SH_ORDER_1"]);
  });

  it("取模的键必须是整数且规则的键必须是表的列", () => {
    assert.throws(() => modulo("customer", 2).route("SH_ORDER", "x"), /integer/);
    assert.throws(() => new TestDatabase({ shards: [{ table: ShOrder, rule: modulo("nope", 2) }] }), /not a column/);
  });
});

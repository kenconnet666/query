import { Decimal } from "decimal.js";
import { parseDate } from "@internationalized/date";
import assert from "node:assert/strict";
import { it } from "node:test";
import { count, deleteFrom, from, insertInto, update } from "@zerodep-dev/query-core";
import { SHARDS, ShEvent } from "../../fixtures/shards.js";
import { liveSuite } from "../support/live.js";

/**
 * 同库分表：SH_EVENT 按发生日分成 2026 年 1 到 3 月的 SH_EVENT_202601、_202602、_202603（声明与规则在 fixtures/shards.ts）。
 * 读写按条件里的分表键落到对应月份的表，没有分表键的语句要显式指定表或 `allShards()`。与 Java 的 ShardingTest 对应。
 */
liveSuite("分表", (live) => {
  it("按条件读写各月的物理表", async () => {
    const sharded = live.database({ shards: SHARDS });
    // 结构变更按规则给每个月建一张表；已经建过时计划为空。
    const plan = await sharded.schema().plan(ShEvent);
    if (!plan.isEmpty) {
      assert.equal(plan.changes.length, 3, plan.toString());
      await plan.apply();
    }
    assert.ok((await sharded.schema().check(ShEvent)).isEmpty, "every month has its table");
    await sharded.execute(deleteFrom(ShEvent).allShards().all());
    await sharded.execute(
      insertInto(ShEvent).values(
        { id: 1n, day: parseDate("2026-01-05"), amount: new Decimal("1.50"), note: "a" },
        { id: 2n, day: parseDate("2026-01-20"), amount: new Decimal("2.00"), note: "b" },
        { id: 3n, day: parseDate("2026-02-03"), amount: new Decimal("4.00"), note: "a" },
        { id: 4n, day: parseDate("2026-03-09"), amount: new Decimal("8.00"), note: "a" }),
    );
    const january = await sharded.one(from(ShEvent).onShards("SH_EVENT_202601").select(() => count()));
    const march = await sharded.one(from(ShEvent).onShards("SH_EVENT_202603").select(() => count()));
    assert.deepEqual([Number(january), Number(march)], [2, 1], "rows land in the table of their month");
    // 日期范围 [1 月 1 日, 3 月 1 日) 只查 1 月和 2 月的表：备注 a 的是 1.50 + 4.00。
    const total = await sharded.one(from(ShEvent).where((e) => {
      e.day.range(parseDate("2026-01-01"), parseDate("2026-03-01"));
      e.note.eq("a");
    }).select((e) => e.amount.sum()));
    assert.equal(Number(total), 5.5);
    assert.deepEqual(await sharded.list(from(ShEvent).allShards().where((e) => e.note.eq("a")).orderBy((e) => e.id).select((e) => e.id)), [1n, 3n, 4n]);
    assert.equal(await sharded.execute(update(ShEvent).set(() => ({ note: "c" })).where((e) => e.day.eq(parseDate("2026-02-03")))), 1);
    assert.equal(await sharded.execute(update(ShEvent).byId({ id: 4n, day: parseDate("2026-03-09"), amount: new Decimal("9.00"), note: "a" })), 1);
    assert.equal(await sharded.execute(deleteFrom(ShEvent).where((e) => e.day.between(parseDate("2026-01-01"), parseDate("2026-01-31")))), 2);
    const rows = await sharded.list(from(ShEvent).allShards().orderBy((e) => e.id).select((e) => ({ id: e.id, note: e.note, amount: e.amount })));
    assert.deepEqual(rows.map((r) => `${r.id}:${r.note}:${Number(r.amount).toFixed(2)}`), ["3:c:4.00", "4:a:9.00"]);
    await assert.rejects(sharded.list(from(ShEvent).where((e) => e.note.eq("a")).select((e) => e.id)), /Cannot tell which shards/, "without the shard key the query must name its tables");
  });
});

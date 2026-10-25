import assert from "node:assert/strict";
import { it } from "node:test";
import { count, from, inMemoryCache, QueryExecutionError, type QueryEvent } from "@zerodep-dev/query-core";
import { Lips } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/** 按交货单排序的全部交货单号。 */
const DELIVERIES = from(Lips)
  .orderBy((l) => l.delivery)
  .select((l) => l.delivery);

/**
 * 执行：分页、流式读取、结果缓存、出错时归还连接，以及语句超时由数据库取消。
 * 数据见 support/sample-data.ts：客户端 100 有 D1 到 D5 五张交货单，加上客户端 200 共六张。与 Java 的 ExecutionTest 对应。
 */
liveSuite("分页、流式、缓存与超时", (live) => {
  it("分页返回当前页和总数", async () => {
    const page = await live.db.page(DELIVERIES, 2, 2);
    assert.deepEqual([page.rows, page.total, page.totalPages], [["D3", "D4"], 5, 3]);
  });

  it("已知总数的分页不再执行 COUNT", async () => {
    const events = await live.db.capture(async () => assert.deepEqual((await live.db.page(DELIVERIES, 2, 2, 5)).rows, ["D3", "D4"]));
    assert.equal(events.length, 1, "only the page itself runs");
  });

  it("流式读取逐行处理且提前结束时释放游标", async () => {
    const seen: string[] = [];
    await live.db.forEach(DELIVERIES, (d) => void seen.push(d));
    assert.deepEqual(seen, ["D1", "D2", "D3", "D4", "D5"]);
    for await (const d of live.db.stream(DELIVERIES)) {
      assert.equal(d, "D1");
      break; // 提前结束必须释放游标和连接
    }
    assert.equal(await live.db.count(DELIVERIES), 5);
  });

  it("结果缓存命中时不再查询数据库", async () => {
    // 缓存按查询开启（cached），监听事件的 cached 记录每次是否来自缓存。
    const events: QueryEvent[] = [];
    const cached = live.database({ cache: inMemoryCache(), listener: (e) => void events.push(e) });
    const q = from(Lips).select(() => count()).cached(60_000);
    assert.deepEqual([await cached.one(q), await cached.one(q)], [6, 6], "this instance has no client filter");
    assert.deepEqual(events.map((e) => e.cached), [false, true]);
  });

  it("查询出错、映射出错或提前结束流时都归还连接", { timeout: 30_000 }, async () => {
    const failsOnD3 = DELIVERIES.map((d) => {
      if (d === "D3") throw new Error("mapping failed");
      return d;
    });
    // 连接池只有四个连接：任何一处漏还，五轮之后最后那条查询就借不到连接。
    for (let i = 0; i < 5; i++) {
      await assert.rejects(live.db.sql("SELECT * FROM NO_SUCH_TABLE").list(), "the database fails");
      await assert.rejects(live.db.list(failsOnD3), /mapping failed/);
      for await (const d of live.db.stream(DELIVERIES)) {
        assert.equal(d, "D1");
        break;
      }
      await assert.rejects(async () => {
        for await (const d of live.db.stream(failsOnD3)) assert.ok(d);
      }, /mapping failed/);
      await assert.rejects(live.db.forEach(DELIVERIES, () => Promise.reject(new Error("consumer failed"))), /consumer failed/);
    }
    assert.equal((await live.db.list(DELIVERIES)).length, 5);
  });

  it("超过超时的语句在数据库端被取消且连接仍可用", async (t) => {
    const slow = live.slowStatement;
    if (slow === undefined) return t.skip("the driver cannot interrupt a running statement");
    // 单条语句的 timeout 和实例的默认 queryTimeoutMs 都交给数据库取消；数据库报的超时错误要认得出来。
    const start = Date.now();
    const timedOut = (e: unknown) => e instanceof QueryExecutionError && e.timedOut;
    await assert.rejects(live.db.sql(slow).timeout(1000).list(), timedOut);
    const limited = live.database({ queryTimeoutMs: 1000 });
    await assert.rejects(limited.sql(slow).list(), timedOut);
    await assert.rejects(live.db.sql(`SELECT * FROM ${live.table("NO_SUCH_TABLE")}`).list(), (e: unknown) => e instanceof QueryExecutionError && !e.timedOut);
    const elapsed = Date.now() - start;
    assert.ok(elapsed < 5000, `both statements were cancelled after about a second instead of running to the end: ${elapsed} ms`);
    assert.equal(await limited.one(from(Lips).where((l) => l.client.eq("100")).select(() => count())), 5);
    const streamed: string[] = [];
    for await (const d of limited.stream(from(Lips).where((l) => l.client.eq("100")).select((l) => l.delivery).timeout(5000))) streamed.push(d);
    assert.equal(streamed.length, 5, "a stream applies its timeout and still reads every row");
  });
});

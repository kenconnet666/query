import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { count, dryRunDriver, EmptyResultError, from, inMemoryCache, lit, Rendered } from "@zerodep-dev/query-core";
import { PostgresDatabase } from "@zerodep-dev/query-postgres";
import { Lips, Mara } from "../fixtures/erp.js";
import { TestDatabase, TestDialect } from "../fixtures/support.js";

describe("打印与测试 SQL", () => {
  it("调试 SQL 把参数写成字面量且不碰字符串里的问号", () => {
    const r = from(Lips)
      .where((l) => l.material.eq("O'Neil").and(l.createdOn.eq("2026-01-15")))
      .select(() => ({ mark: lit("a?b") }))
      .toSql(new TestDialect());
    assert.equal(r.debugSql(), "SELECT 'a?b' AS \"mark\"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.MATNR = 'O''Neil' AND LIPS.ERDAT = '20260115'");
    assert.equal(r.parameterText(), "O'Neil(text), 20260115(converted text)");
    assert.ok(r.toString().endsWith("\n-- parameters: O'Neil(text), 20260115(converted text)"));
  });

  it("PostgreSQL 的编号占位符也能写回字面量", () => {
    const values = Array.from({ length: 11 }, (_, i) => `M${i + 1}`);
    const r = new PostgresDatabase(dryRunDriver()).render(from(Mara).where((m) => m.material.in(values)).select((m) => m.material));
    assert.ok(r.sql.includes("$10, $11"));
    assert.ok(r.debugSql().endsWith("IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9', 'M10', 'M11')"), r.debugSql());
  });

  it("打印每条语句的 SQL、参数、行数和耗时", async () => {
    const printed: string[] = [];
    const db = new TestDatabase({ printSql: (text) => printed.push(text) }, dryRunDriver());
    await db.list(from(Mara).where((m) => m.material.eq("M1")).select((m) => m.partNumber));
    assert.equal(printed.length, 1);
    const lines = printed[0]!.split("\n");
    assert.equal(lines[0], 'SELECT MARA.MFRPN AS "partNumber"'.replace(/^/, "==> Preparing: "));
    assert.equal(lines[3], "==> Parameters: M1(text)");
    assert.match(lines[4]!, /^<== Total: 0, Time: \d+ ms$/);
  });

  it("捕获一段代码（含异步调用）执行的全部语句且空跑不连数据库", async () => {
    const db = new TestDatabase({ cache: inMemoryCache() }, dryRunDriver());
    const total = from(Lips).select(() => count()).cached(60_000);
    const events = await db.capture(async () => {
      await db.list(from(Lips).where((l) => l.delivery.eq("D1")).select((l) => l.material));
      await db.page(from(Mara).orderBy((m) => m.material).select((m) => m.material), 1, 10);
      await db.first(total);
      await db.first(total);
    });
    assert.equal(events.length, 5);
    assert.deepEqual(events[0]!.statement.parameters, ["D1"]);
    assert.ok(events.some((e) => e.statement.sql.startsWith("SELECT COUNT(*) FROM (")));
    assert.deepEqual(events.slice(3).map((e) => e.cached), [false, true]);
    assert.equal(await db.count(from(Mara).select((m) => m.material)), 0);
    assert.deepEqual(await db.capture(() => undefined), []);
  });

  it("执行前钩子按注册顺序改写每条语句", async () => {
    const db = new TestDatabase(
      { interceptors: [(s) => new Rendered(`${s.sql}\n-- a`, s.bindings, s.dialect), (s) => new Rendered(`${s.sql} b`, s.bindings, s.dialect)] },
      dryRunDriver(),
    );
    const events = await db.capture(async () => {
      await db.list(from(Lips).select((l) => l.delivery));
      await db.count(from(Lips).select((l) => l.delivery));
      await db.exists(from(Lips).select((l) => l.delivery));
      await db.sql("SELECT 1 AS n").list();
    });
    assert.equal(events.length, 4);
    assert.ok(events.every((e) => e.statement.sql.endsWith("\n-- a b")), JSON.stringify(events.map((e) => e.statement.sql)));
  });

  it("执行前钩子抛出异常时语句不执行", async () => {
    const db = new TestDatabase({ interceptors: [() => { throw new Error("blocked"); }] }, dryRunDriver());
    const seen: unknown[] = [];
    await assert.rejects(async () => seen.push(...(await db.capture(() => db.list(from(Lips).select((l) => l.delivery))))), /blocked/);
    assert.deepEqual(seen, []);
  });

  it("恰好一行的查询没有行时抛出专门的错误", async () => {
    const db = new TestDatabase({}, dryRunDriver());
    await assert.rejects(db.one(from(Lips).select((l) => l.delivery)), EmptyResultError);
    await assert.rejects(db.sql("SELECT 1 AS n").one(), EmptyResultError);
  });

  it("捕获可以嵌套且只收集本数据库的语句", async () => {
    const a = new TestDatabase({}, dryRunDriver());
    const b = new TestDatabase({}, dryRunDriver());
    let inner: unknown[] = [];
    const outer = await a.capture(async () => {
      await a.list(from(Mara).select((m) => m.material));
      inner = await b.capture(async () => {
        await b.list(from(Lips).select((l) => l.delivery));
        await a.list(from(Lips).select((l) => l.unit));
      });
    });
    assert.equal(outer.length, 2, "a's statements, including the one run inside b's capture");
    assert.equal(inner.length, 1);
  });
});

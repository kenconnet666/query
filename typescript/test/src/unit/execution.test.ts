import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { count, from, inMemoryCache, lit, QueryExecutionError, val, type QueryEvent } from "@zerodep-dev/query-core";
import { MysqlDatabase } from "@zerodep-dev/query-mysql";
import { PostgresDatabase } from "@zerodep-dev/query-postgres";
import { DeliveryVO, Lips } from "../erp.js";
import { FakeDriver, rows, TestDatabase } from "../support.js";

describe("执行", () => {
  it("原生 SQL 替换引号、注释和类型转换之外的命名参数并展开数组", () => {
    const q = new TestDatabase()
      .sql("SELECT ':skip', \"a:b\" FROM t -- :comment\nWHERE x = :x AND y::text IN (:ys) /* :also */ AND z = :x")
      .bind("x", 1)
      .bind("ys", ["a", "b"]);
    const r = q.render();
    assert.equal(r.sql, "SELECT ':skip', \"a:b\" FROM t -- :comment\nWHERE x = ? AND y::text IN (?, ?) /* :also */ AND z = ?");
    assert.deepEqual(r.parameters, [1, "a", "b", 1]);
  });

  it("原生 SQL 在 PostgreSQL 上用编号占位符", () => {
    const never = { query: () => Promise.reject(new Error("never")) };
    const r = new PostgresDatabase(never).sql("SELECT * FROM t WHERE a = :a AND b IN (:b)").bind("a", "x").bind("b", [1, 2]).render();
    assert.equal(r.sql, "SELECT * FROM t WHERE a = $1 AND b IN ($2, $3)");
  });

  it("原生 SQL 按方言跳过引用：PostgreSQL 的美元引号、MySQL 的反引号和反斜杠转义", () => {
    const never = { query: () => Promise.reject(new Error("never")) };
    const pgSql = "SELECT $$:a$$, $fn$ :b $fn$, a$1 FROM t WHERE x = :x";
    assert.equal(new PostgresDatabase(never).sql(pgSql).bind("x", 1).render().sql, "SELECT $$:a$$, $fn$ :b $fn$, a$1 FROM t WHERE x = $1");
    const mysqlSql = "SELECT 'it\\'s :a', `b:c` FROM t WHERE x = :x";
    assert.equal(new MysqlDatabase(never).sql(mysqlSql).bind("x", 1).render().sql, "SELECT 'it\\'s :a', `b:c` FROM t WHERE x = ?");
  });

  it("原生 SQL 的未绑定参数和空数组立刻报错", () => {
    const db = new TestDatabase();
    assert.throws(() => db.sql("SELECT :a").render(), /:a is not bound/);
    assert.throws(() => db.sql("SELECT 1 WHERE x IN (:a)").bind("a", []).render(), /empty array/);
  });

  it("原生 SQL 按标签映射到类，忽略大小写和下划线", async () => {
    const driver = new FakeDriver(() => rows(["PART_NUMBER", "NAME", "QUANTITY", "OTHER"], ["P1", "Pump", "5", 1]));
    const [vo] = await new TestDatabase({}, driver).sql("SELECT ...").list(DeliveryVO);
    assert.ok(vo instanceof DeliveryVO);
    assert.deepEqual({ ...vo }, { partNumber: "P1", name: "Pump", quantity: "5" });
    const [plain] = await new TestDatabase({}, driver).sql("SELECT ...").list();
    assert.deepEqual(plain, { PART_NUMBER: "P1", NAME: "Pump", QUANTITY: "5", OTHER: 1 });
  });

  it("结果缓存按 SQL 和参数命中，监听器能区分", async () => {
    const events: QueryEvent[] = [];
    const driver = new FakeDriver(() => rows(["C"], ["6"]));
    const db = new TestDatabase({ cache: inMemoryCache(), listener: (e) => void events.push(e) }, driver);
    const q = from(Lips)
      .select(() => count())
      .cached(60_000);
    assert.equal(await db.one(q), 6);
    assert.equal(await db.one(q), 6);
    assert.equal(driver.statements.length, 1);
    assert.deepEqual(
      events.map((e) => e.cached),
      [false, true],
    );
    db.clearCache();
    await db.one(q);
    assert.equal(driver.statements.length, 2);
  });

  it("并发读取同一个键只查询一次", async () => {
    const driver = new FakeDriver(() => rows(["C"], ["6"]));
    const db = new TestDatabase({ cache: inMemoryCache() }, driver);
    const q = from(Lips)
      .select(() => count())
      .cached(60_000);
    await Promise.all([db.list(q), db.list(q), db.list(q)]);
    assert.equal(driver.statements.length, 1);
  });

  it("缓存的查询需要数据库配置缓存", async () => {
    await assert.rejects(
      new TestDatabase({}, new FakeDriver()).list(
        from(Lips)
          .select((l) => l.delivery)
          .cached(1000),
      ),
      /has no cache/,
    );
  });

  it("驱动的错误带上数据库名和 SQL，监听器收到失败事件", async () => {
    const events: QueryEvent[] = [];
    const driver = new FakeDriver(() => {
      throw new Error("table not found");
    });
    const db = new TestDatabase({ name: "erp", listener: (e) => void events.push(e) }, driver);
    const error = await db.list(from(Lips).select((l) => l.delivery)).catch((e: unknown) => e);
    assert.ok(error instanceof QueryExecutionError);
    assert.match(error.message, /^erp: table not found\nSELECT LIPS.VBELN/);
    assert.equal(events.length, 1);
    assert.ok(events[0]!.error instanceof Error);
  });

  it("超时取数据库实例的默认值，查询和原生 SQL 自己的值优先", async () => {
    const driver = new FakeDriver(() => rows(["C"], [1]));
    const db = new TestDatabase({ queryTimeoutMs: 1500 }, driver);
    const q = from(Lips).select((l) => l.delivery);
    await db.list(q);
    await db.list(q.timeout(200));
    await db.count(q.timeout(3000));
    await db.sql("SELECT 1").timeout(5000).list();
    await db.sql("SELECT 1").list();
    await new TestDatabase({}, driver).list(q);
    assert.deepEqual(
      driver.statements.map((s) => s.options?.timeoutMs),
      [1500, 200, 3000, 5000, 1500, undefined],
    );
    assert.throws(() => q.timeout(0), /timeout must be positive/);
    assert.throws(() => new TestDatabase({ queryTimeoutMs: -1 }), /queryTimeoutMs must be positive/);
  });

  it("参数个数超过方言上限时在连接数据库之前报错", async () => {
    const many = Array.from({ length: 70_000 }, (_, i) => `D${i}`);
    const q = from(Lips).where((l) => l.delivery.in(many)).select((l) => l.delivery);
    await assert.rejects(new TestDatabase().list(q), /binds 70000 parameters; .* accepts at most 65535/);
  });

  it("非有限的浮点数在 SELECT 列表里仍绑定为参数，写成字面量时报错", () => {
    const db = new TestDatabase();
    const r = db.render(from(Lips).select(() => val(Number.NaN)));
    assert.match(r.sql, /^SELECT \? AS /);
    assert.equal(r.parameters.length, 1);
    assert.throws(() => db.render(from(Lips).select(() => lit(Number.POSITIVE_INFINITY))), /No literal form for Infinity/);
  });

  it("监听器抛出的异常不影响查询", async () => {
    const db = new TestDatabase(
      {
        listener: () => {
          throw new Error("broken listener");
        },
      },
      new FakeDriver(() => rows([], ["D1"])),
    );
    assert.deepEqual(await db.list(from(Lips).select((l) => l.delivery)), ["D1"]);
  });
});

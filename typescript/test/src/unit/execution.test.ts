import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { count, from, inMemoryCache, QueryExecutionError, type QueryEvent } from "@query/core";
import { MysqlDatabase } from "@query/mysql";
import { PostgresDatabase } from "@query/postgres";
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
      .selectValue(() => count())
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
      .selectValue(() => count())
      .cached(60_000);
    await Promise.all([db.list(q), db.list(q), db.list(q)]);
    assert.equal(driver.statements.length, 1);
  });

  it("缓存的查询需要数据库配置缓存", async () => {
    await assert.rejects(
      new TestDatabase({}, new FakeDriver()).list(
        from(Lips)
          .selectValue((l) => l.delivery)
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
    const error = await db.list(from(Lips).selectValue((l) => l.delivery)).catch((e: unknown) => e);
    assert.ok(error instanceof QueryExecutionError);
    assert.match(error.message, /^erp: table not found\nSELECT LIPS.VBELN/);
    assert.equal(events.length, 1);
    assert.ok(events[0]!.error instanceof Error);
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
    assert.deepEqual(await db.list(from(Lips).selectValue((l) => l.delivery)), ["D1"]);
  });
});

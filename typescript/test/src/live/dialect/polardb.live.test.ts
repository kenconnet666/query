import assert from "node:assert/strict";
import { it } from "node:test";
import { all, count, from, fromAsOf, inventory, minus, update, val } from "@zerodep-dev/query-polardb";
import { MysqlDatabase } from "@zerodep-dev/query-mysql";
import { PolardbStock } from "../../fixtures/dialect-tables.js";
import { liveSuite } from "../support/live.js";

/** 停 ms 毫秒。 */
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * PolarDB 专属的写法（`@zerodep-dev/query-polardb`）：闪回查询和库存提示；MySQL 的写法在 mysql.live.test.ts 里，也在 PolarDB 上跑。
 * 闪回查询要求服务器开着 undo 保留（infra/polardb 的 my.cnf 设了 innodb_undo_retention），每个测试先手写重建 P_STOCK。
 * 与 Java 的 PolardbTest 对应。
 */
liveSuite("PolarDB 专属写法", { only: ["polardb"] }, (live) => {
  /** 删掉并重建 P_STOCK，写入 rows（形如 `(1, 10), (2, 20)`，为空时不写）。 */
  const stock = async (rows: string) => {
    const table = live.table("P_STOCK");
    await live.execute(`-DROP TABLE ${table}`, `CREATE TABLE ${table} (ID BIGINT PRIMARY KEY, QTY INT NOT NULL)`);
    if (rows !== "") await live.execute(`INSERT INTO ${table} VALUES ${rows}`);
  };

  it("闪回查询读到改动之前的行且全局过滤器照常生效", async () => {
    await stock("(1, 10), (2, 20)");
    // 引擎按 innodb_scn_history_interval（测试环境 1 秒）记录时刻到快照的对应：闪回的时刻要晚于建表、早于改动，各隔开一个多间隔
    await pause(1500);
    const before = String((await live.driver.query("SELECT DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s.%f')", [])).rows[0]![0]);
    await pause(1500);
    await live.execute(`UPDATE ${live.table("P_STOCK")} SET QTY = 9 WHERE ID = 1`, `DELETE FROM ${live.table("P_STOCK")} WHERE ID = 2`);
    const then = await live.db.list(fromAsOf(PolardbStock, before).orderBy((s) => s.id).select((s) => ({ id: s.id, qty: s.qty })));
    assert.deepEqual(then.map((r) => `${r.id}:${r.qty}`), ["1:10", "2:20"]);
    assert.deepEqual(await live.db.list(from(PolardbStock).select((s) => s.qty)), [9], "now");
    assert.equal(await live.db.one(fromAsOf(PolardbStock, before).where((s) => s.qty.gt(15)).select(() => count())), 1);
  });

  it("闪回到建表之前时数据库报错", async () => {
    await stock("");
    await assert.rejects(live.db.list(fromAsOf(PolardbStock, "2000-01-01 00:00:00").select((s) => s.id)));
  });

  it("库存提示改到预期行数时提交整个事务否则整个事务回滚", async () => {
    await stock("(1, 1), (2, 5)");
    const takeOne = update(PolardbStock)
      .with(() => inventory(1))
      .set((s) => ({ qty: minus(s.qty, val(1)) }))
      .where((s) => all(s.id.eq(1n), s.qty.gt(0)));
    // 扣到 0 为止：第一次改到一行，提交；第二次条件不满足，改到 0 行，数据库报错并回滚同一事务里之前的改动
    await live.db.transaction(async (tx) => void (await tx.execute(takeOne)));
    await assert.rejects(
      live.db.transaction(async (tx) => {
        await tx.execute(update(PolardbStock).set(() => ({ qty: 0 })).where((s) => s.id.eq(2n)));
        await tx.execute(takeOne);
      }),
    );
    assert.deepEqual(await live.db.list(from(PolardbStock).orderBy((s) => s.id).select((s) => s.qty)), [0, 5]);
    await assert.rejects(live.db.execute(update(PolardbStock).with(() => inventory(1)).set(() => ({ qty: 9 })).where((s) => s.id.eq(2n))), "自动提交时不能用");
  });

  it("专属写法在 MySQL 方言上报错", () => {
    const mysql = new MysqlDatabase({ query: () => Promise.reject(new Error("never connects")) });
    assert.throws(() => mysql.render(fromAsOf(PolardbStock, "2026-01-01 00:00:00").select((s) => s.id)), /PolarDB-only/);
    assert.throws(() => mysql.render(update(PolardbStock).with(() => inventory(1)).set(() => ({ qty: 1 })).where((s) => s.id.eq(1n))), /PolardbDialect clause/);
  });
});

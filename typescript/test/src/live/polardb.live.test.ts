import assert from "node:assert/strict";
import { it } from "node:test";
import { all, count, from, fromAsOf, inventory, minus, PolardbDatabase, update, val } from "@zerodep-dev/query-polardb";
import { MysqlDatabase } from "@zerodep-dev/query-mysql";
import { liveScenarios } from "./live.js";
import { mysqlScenarios, mysqlTarget } from "./mysql-scenarios.js";
import { PolardbStock } from "../fixtures/dialect-tables.js";

/**
 * 在 PolarDB 上跑 MySQL 方言的全部实库场景，再加 PolarDB 专属的闪回查询与库存提示；与 Java 的 PolardbLiveTest 一致。
 * 需要 QUERY_POLARDB_URL、QUERY_POLARDB_USER、QUERY_POLARDB_PASSWORD；闪回查询要求服务器开着 undo 保留（infra/polardb 的 my.cnf）。
 */
liveScenarios(
  mysqlTarget("POLARDB", (driver, options) => new PolardbDatabase(driver, options)),
  (live) => {
    mysqlScenarios(live);

    const stock = (live: () => { table(name: string): string }) => live().table("P_STOCK");
    const recreate = async (rows: string) => {
      const { driver } = live();
      await driver.query(`DROP TABLE ${stock(live)}`, []).catch(() => undefined);
      await driver.query(`CREATE TABLE ${stock(live)} (ID BIGINT PRIMARY KEY, QTY INT NOT NULL)`, []);
      if (rows !== "") await driver.query(`INSERT INTO ${stock(live)} VALUES ${rows}`, []);
    };
    const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

    it("闪回查询读到改动之前的行且全局过滤器照常生效", async () => {
      const { db, driver } = live();
      await recreate("(1, 10), (2, 20)");
      // 引擎按 innodb_scn_history_interval（测试环境 1 秒）记录时刻到快照的对应：闪回的时刻要晚于建表、早于改动，各隔开一个多间隔
      await pause(1500);
      const before = String((await driver.query("SELECT DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s.%f')", [])).rows[0]![0]);
      await pause(1500);
      await driver.query(`UPDATE ${stock(live)} SET QTY = 9 WHERE ID = 1`, []);
      await driver.query(`DELETE FROM ${stock(live)} WHERE ID = 2`, []);

      const then = await db.list(fromAsOf(PolardbStock, before).orderBy((s) => s.id).select((s) => ({ id: s.id, qty: s.qty })));
      assert.deepEqual(then.map((r) => `${r.id}:${r.qty}`), ["1:10", "2:20"]);
      assert.deepEqual(await db.list(from(PolardbStock).select((s) => s.qty)), [9]);
      assert.equal(await db.one(fromAsOf(PolardbStock, before).where((s) => s.qty.gt(15)).select(() => count())), 1);
    });

    it("闪回到建表之前时数据库报错", async () => {
      await recreate("");
      await assert.rejects(live().db.list(fromAsOf(PolardbStock, "2000-01-01 00:00:00").select((s) => s.id)));
    });

    it("库存提示改到预期行数时提交整个事务否则整个事务回滚", async () => {
      const { db } = live();
      await recreate("(1, 1), (2, 5)");
      const takeOne = update(PolardbStock)
        .with(() => inventory(1))
        .set((s) => ({ qty: minus(s.qty, val(1)) }))
        .where((s) => all(s.id.eq(1n), s.qty.gt(0)));
      // 扣到 0 为止：第一次改到一行，提交；第二次条件不满足，改到 0 行，数据库报错并回滚同一事务里之前的改动
      await db.transaction(async (tx) => void (await tx.execute(takeOne)));
      await assert.rejects(
        db.transaction(async (tx) => {
          await tx.execute(update(PolardbStock).set(() => ({ qty: 0 })).where((s) => s.id.eq(2n)));
          await tx.execute(takeOne);
        }),
      );
      assert.deepEqual(await db.list(from(PolardbStock).orderBy((s) => s.id).select((s) => s.qty)), [0, 5]);
      // 自动提交时不能用
      await assert.rejects(db.execute(update(PolardbStock).with(() => inventory(1)).set(() => ({ qty: 9 })).where((s) => s.id.eq(2n))));
    });

    it("专属写法在别的方言上报错", () => {
      const mysql = new MysqlDatabase({ query: () => Promise.reject(new Error("never connects")) });
      assert.throws(() => mysql.render(fromAsOf(PolardbStock, "2026-01-01 00:00:00").select((s) => s.id)), /PolarDB-only/);
      assert.throws(() => mysql.render(update(PolardbStock).with(() => inventory(1)).set(() => ({ qty: 1 })).where((s) => s.id.eq(1n))), /PolardbDialect clause/);
    });
  },
);

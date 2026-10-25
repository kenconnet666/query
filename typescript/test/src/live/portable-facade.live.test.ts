import assert from "node:assert/strict";
import { it } from "node:test";
import { count, database, filter, from } from "@zerodep-dev/query-core";
import { HanaDialect } from "@zerodep-dev/query-hana";
import { MysqlDialect } from "@zerodep-dev/query-mysql";
import { PostgresDialect } from "@zerodep-dev/query-postgres";
import { SqliteDialect } from "@zerodep-dev/query-sqlite";
import { ClientEntity, Lips } from "../fixtures/erp.js";
import { liveSuite } from "./support/live.js";

/**
 * 通用门面 `database(driver)`：方言来自驱动适配器（mysqlDriver、pgDriver 等都带着自己的方言），与 Java 的 `Sql.database(...)` 对应。
 * PolarDB 用 mysql2 的驱动，所以得到的是 MySQL 方言。
 */
liveSuite("通用门面", (live) => {
  it("按驱动识别出本库的方言后可以直接查询", async () => {
    const db = database(live.driver, { filters: [filter(ClientEntity, (r) => r.client.eq("100"))] });
    const expected = live.is("hana") ? HanaDialect : live.is("postgres") ? PostgresDialect : live.is("sqlite", "sqlite-wasm") ? SqliteDialect : MysqlDialect;
    assert.equal(db.dialect.constructor, expected);
    assert.equal(await db.one(from(Lips).select(() => count())), 5);
  });
});

import type { Client, Pool, PoolClient } from "pg";
import { Database, type DatabaseOptions, type Driver } from "@zerodep-dev/query-core";
import { PostgresDialect } from "./dialect.js";
import { pgDriver } from "./driver.js";

/** 一次导入拿到全部词汇：核心函数加上 PostgreSQL 专有的。 */
export * from "@zerodep-dev/query-core";
export { PostgresDialect } from "./dialect.js";
export { pgDriver } from "./driver.js";
export { cube, distinctOn, greatest, groupingSets, ilike, least, pg, PgOps } from "./functions.js";

/** PostgreSQL 数据库：查询渲染成 PostgreSQL SQL，经由给定的驱动执行。 */
export class PostgresDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(PostgresDialect.instance, driver, options);
  }
}

/** 基于 `pg` 连接池或客户端的 PostgreSQL 数据库。 */
export function database(source: Pool | PoolClient | Client, options?: DatabaseOptions): PostgresDatabase {
  return new PostgresDatabase(pgDriver(source), options);
}

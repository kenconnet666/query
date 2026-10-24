import type { Connection, Pool, PoolConnection } from "mysql2/promise";
import { Database, type DatabaseOptions, type Driver } from "@zerodep-dev/query-core";
import { MysqlDialect } from "./dialect.js";
import { mysqlDriver } from "./driver.js";

/** 一次导入拿到全部词汇：核心函数加上 MySQL 专有的。 */
export * from "@zerodep-dev/query-core";
export { deleteJoin, updateJoin } from "./join.js";
export { Hint, MysqlDialect } from "./dialect.js";
export { mysqlDriver } from "./driver.js";
export { index, jsonContains, jsonText, match, matchBoolean, matchScore, noIndex } from "./functions.js";
export { dateFormat, daysFollowing, daysPreceding, exceptAll, forShare, forUpdateOf, greatest, intersectAll, least, maxExecutionTime, range, recursiveCte } from "./functions.js";

/** MySQL 数据库：查询渲染成 MySQL SQL，经由给定的驱动执行。 */
export class MysqlDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(MysqlDialect.instance, driver, options);
  }
}

/** 基于 `mysql2/promise` 连接池或连接的 MySQL 数据库。 */
export function database(source: Pool | PoolConnection | Connection, options?: DatabaseOptions): MysqlDatabase {
  return new MysqlDatabase(mysqlDriver(source), options);
}
export { MysqlSchemaSyntax } from "./schema.js";

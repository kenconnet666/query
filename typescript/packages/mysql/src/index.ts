import type { Connection, Pool, PoolConnection } from "mysql2/promise";
import { Database, type DatabaseOptions, type Driver } from "@query/core";
import { MysqlDialect } from "./dialect.js";
import { mysqlDriver } from "./driver.js";

/** One import gives the whole vocabulary: core functions plus MySQL's own. */
export * from "@query/core";
export { MysqlDialect } from "./dialect.js";
export { mysqlDriver } from "./driver.js";
export { dateDiff, dateFormat, groupConcat, maxExecutionTime } from "./functions.js";

/** A MySQL database: queries render as MySQL SQL and run through the given driver. */
export class MysqlDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(MysqlDialect.instance, driver, options);
  }
}

/** A MySQL database over a `mysql2/promise` pool or connection. */
export function database(source: Pool | PoolConnection | Connection, options?: DatabaseOptions): MysqlDatabase {
  return new MysqlDatabase(mysqlDriver(source), options);
}

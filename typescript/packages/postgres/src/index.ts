import type { Client, Pool, PoolClient } from "pg";
import { Database, type DatabaseOptions, type Driver } from "@query/core";
import { PostgresDialect } from "./dialect.js";
import { pgDriver } from "./driver.js";

/** One import gives the whole vocabulary: core functions plus PostgreSQL's own. */
export * from "@query/core";
export { PostgresDialect } from "./dialect.js";
export { pgDriver } from "./driver.js";
export { cube, distinctOn, filterWhere, groupingSets, ilike, pg, PgOps, stringAgg } from "./functions.js";

/** A PostgreSQL database: queries render as PostgreSQL SQL and run through the given driver. */
export class PostgresDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(PostgresDialect.instance, driver, options);
  }
}

/** A PostgreSQL database over a `pg` pool or client. */
export function database(source: Pool | PoolClient | Client, options?: DatabaseOptions): PostgresDatabase {
  return new PostgresDatabase(pgDriver(source), options);
}

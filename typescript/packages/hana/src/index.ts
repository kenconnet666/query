import type { Connection, ConnectionPool } from "@sap/hana-client";
import { Database, type DatabaseOptions, type Driver } from "@query/core";
import { HanaDialect } from "./dialect.js";
import { hanaDriver } from "./driver.js";

/** One import gives the whole vocabulary: core functions plus HANA's own. */
export * from "@query/core";
export { HanaDialect } from "./dialect.js";
export { hanaDriver } from "./driver.js";
export { addDays, addMonths, addYears, cube, daysBetween, greatest, groupingSets, hint, least, length, likeRegexpr, stringAgg, toDate, toInteger, toNvarchar, toVarchar } from "./functions.js";

/** A HANA database: queries render as HANA SQL and run through the given driver. */
export class HanaDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(HanaDialect.instance, driver, options);
  }
}

/** A HANA database over an `@sap/hana-client` pool or connection. */
export function database(source: ConnectionPool | Connection, options?: DatabaseOptions): HanaDatabase {
  return new HanaDatabase(hanaDriver(source), options);
}

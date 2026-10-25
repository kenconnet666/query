import type { Connection, ConnectionPool } from "@sap/hana-client";
import { Database, type DatabaseOptions, type Driver } from "@zerodep-dev/query-core";
import { HanaDialect } from "./dialect.js";
import { hanaDriver } from "./driver.js";

/** 一次导入拿到全部词汇：核心函数加上 HANA 专有的。 */
export * from "@zerodep-dev/query-core";
export { deleteJoin, updateJoin } from "./join.js";
export { HanaDialect } from "./dialect.js";
export { hanaDriver } from "./driver.js";
export { ancestors, contains, containsFuzzy, descendants, fromFunction, fromView, hierarchy, score, type HierarchyColumns, type NavigationColumns } from "./functions.js";
export { corr, cube, greatest, groupingSets, hana, hint, least, percentileCont, percentileDisc, ViewAny, ViewNumber, ViewText } from "./functions.js";

/** HANA 数据库：查询渲染成 HANA SQL，经由给定的驱动执行。 */
export class HanaDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(HanaDialect.instance, driver, options);
  }
}

/** 基于 `@sap/hana-client` 连接池或连接的 HANA 数据库。 */
export function database(source: ConnectionPool | Connection, options?: DatabaseOptions): HanaDatabase {
  return new HanaDatabase(hanaDriver(source), options);
}
export { HanaSchemaSyntax } from "./schema.js";

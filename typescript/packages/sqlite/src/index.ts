import type { DatabaseSync } from "node:sqlite";
import type { Database as WasmDatabase } from "@sqlite.org/sqlite-wasm";
import { Database, type DatabaseOptions, type Driver } from "@zerodep-dev/query-core";
import { SqliteDialect } from "./dialect.js";
import { nodeSqliteDriver, wasmDriver } from "./driver.js";

/**
 * 一次导入拿到全部词汇：核心函数加上 SQLite 专有的。核心里 SQLite 不支持的 `grouping` 和 `forUpdate` 不从这里导出，
 * 从本包导入时写不出来；`groupByRollup` 渲染时报错。
 */
export * from "@zerodep-dev/query-core";
export { grouping, forUpdate } from "./unsupported.js";
export { deleteJoin, updateJoin } from "./join.js";
export { SqliteDialect } from "./dialect.js";
export { nodeSqliteDriver, wasmDriver } from "./driver.js";
export { glob, median, percentileCont, percentileDisc } from "./functions.js";
export { SqliteSchemaSyntax } from "./schema.js";

/** SQLite 数据库：查询渲染成 SQLite SQL，经由给定的驱动执行。 */
export class SqliteDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(SqliteDialect.instance, driver, options);
  }
}

/**
 * 基于 Node 内置 `node:sqlite` 的 `DatabaseSync`，或 SQLite WASM 的 oo1 `DB` 的 SQLite 数据库。表声明里的 schema 是 ATTACH 进来的库名：
 * 要么先对 db 执行 `ATTACH DATABASE ... AS APP`，要么用选项 `schemas: { APP: "main" }` 映射到主库。
 */
export function database(db: DatabaseSync | WasmDatabase, options?: DatabaseOptions): SqliteDatabase {
  return new SqliteDatabase("pointer" in db ? wasmDriver(db as WasmDatabase) : nodeSqliteDriver(db as DatabaseSync), options);
}

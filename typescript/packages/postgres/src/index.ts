import type { Client, Pool, PoolClient } from "pg";
import { columnName, Database, entityMeta, Rendered, type Columns, type DatabaseOptions, type Delete, type Driver, type Insert, type Update } from "@zerodep-dev/query-core";
import { PostgresDialect } from "./dialect.js";
import { pgDriver } from "./driver.js";

/** 一次导入拿到全部词汇：核心函数加上 PostgreSQL 专有的。 */
export * from "@zerodep-dev/query-core";
export { deleteJoin, updateJoin } from "./join.js";
export { PostgresDialect } from "./dialect.js";
export { pgDriver } from "./driver.js";
export { arrayAgg, arrayContains, arrayOf, arrayOverlaps, cardinality, jsonb, textMatches, textRank, webMatches } from "./functions.js";
export { corr, cube, daysFollowing, daysPreceding, distinctOn, exceptAll, forShare, forUpdateOf, greatest, groupingSets, intersectAll, least, mode, percentileCont, percentileDisc, postgres, range, recursiveCte, ViewAny, ViewText } from "./functions.js";

/** PostgreSQL 数据库：查询渲染成 PostgreSQL SQL，经由给定的驱动执行。 */
export class PostgresDatabase extends Database {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(PostgresDialect.instance, driver, options);
  }

  /**
   * 执行插入、更新或删除，并用 `RETURNING` 读回改到的行（表的全部列，按字段名）：
   * `await db.returning(update(Doc).set({ title: "x" }).where(...))`。逻辑删除改写成的 UPDATE 同样适用。
   */
  async returning<T>(write: Insert<T> | Update<T> | Delete<T>): Promise<Columns<T>[]> {
    const properties = entityMeta(write.table).properties;
    const suffix = " RETURNING " + properties.map((p) => `${this.dialect.quote(columnName(p, this.naming))} AS ${this.dialect.quote(p.name)}`).join(", ");
    const statements = this.render(write).map((s) => new Rendered(s.sql + suffix, s.bindings, s.dialect));
    return this.writeReturning(statements, (row) => Object.fromEntries(properties.map((p, i) => [p.name, row[i] == null ? null : p.type.decode(row[i])])) as Columns<T>);
  }
}

/** 基于 `pg` 连接池或客户端的 PostgreSQL 数据库。 */
export function database(source: Pool | PoolClient | Client, options?: DatabaseOptions): PostgresDatabase {
  return new PostgresDatabase(pgDriver(source), options);
}
export { PostgresSchemaSyntax } from "./schema.js";

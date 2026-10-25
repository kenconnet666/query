import type { CalendarDateTime } from "@internationalized/date";
import type { Connection, Pool, PoolConnection } from "mysql2/promise";
import { customSource, from, param, tableSource, timestamp, type Clause, type DatabaseOptions, type Driver, type From, type Ref, type SqlWriter, type Table } from "@zerodep-dev/query-core";
import { Hint, MysqlDatabase, MysqlDialect, mysqlDriver } from "@zerodep-dev/query-mysql";

/** 一次导入拿到全部词汇：核心函数、MySQL 专有的，再加上 PolarDB 专有的。 */
export * from "@zerodep-dev/query-mysql";

/**
 * PolarDB for MySQL。写法与 MySQL 8 方言完全相同：MySQL 方言的全部实库场景在开源的 PolarDB-X 引擎
 * （MySQL 8.0.42 内核，表名不区分大小写）上原样通过（决定 0012）。PolarDB 专属的闪回查询和库存提示只能在本方言上渲染；
 * MySQL 方言的专属函数在本方言上照常可用。与 Java 的 PolardbDialect 一致。
 */
export class PolardbDialect extends MysqlDialect {
  /** PolarDB MySQL 8.0 的小版本可能早于 8.0.14（没有 LATERAL），一对多的 first(...) 仍写成 ROW_NUMBER 排名。 */
  override get supportsLateral(): boolean {
    return false;
  }

  /** 方言无状态，全局共用这一个实例。 */
  static override readonly instance: PolardbDialect = new PolardbDialect();

  override readonly name: string = "PolarDB";
}

/** PolarDB 数据库：也是 MySQL 数据库，接受 MySQL 方言的全部写法。 */
export class PolardbDatabase extends MysqlDatabase {
  constructor(driver: Driver, options?: DatabaseOptions) {
    super(driver, options, PolardbDialect.instance);
  }
}

/** 基于 `mysql2/promise` 连接池或连接的 PolarDB 数据库。 */
export function database(source: Pool | PoolConnection | Connection, options?: DatabaseOptions): PolardbDatabase {
  return new PolardbDatabase(mysqlDriver(source), options);
}

/** 渲染目标不是 PolarDB 时抛出异常。 */
function requirePolardb(w: SqlWriter): void {
  if (!(w.dialect instanceof PolardbDialect)) throw new Error(`A PolarDB-only operation cannot run on ${w.dialect.name}`);
}

/**
 * 读表在 at 那一刻的样子（闪回查询）：`fromAsOf(Stock, new CalendarDateTime(2026, 10, 24, 10, 0)).where(...).select(...)` 写成
 * `FROM \`S\`.\`STOCK\` AS OF TIMESTAMP CAST(? AS DATETIME(6)) \`stock\``。时刻是数据库所在时区的本地时间（同 `NOW()`），
 * 要在 undo 的保留时间之内：社区版引擎由 `innodb_undo_retention`（秒）决定，
 * 阿里云 PolarDB 要先打开闪回查询。超出范围、或表结构在这一刻之后变过时数据库报错。与 Java 的 `Polardb.fromAsOf` 一致。
 */
export function fromAsOf<E>(table: Table<E>, at: CalendarDateTime): From<[Ref<E>]> {
  const base = tableSource(table);
  const moment = param(timestamp, at);
  return from(
    customSource<E>({
      entity: table,
      filtered: true,
      columns: base.columns,
      preferredAlias: base.preferredAlias,
      columnName: (c, w) => base.columnName(c, w),
      renderSource: (w) => {
        requirePolardb(w);
        base.renderSource(w);
        w.sql(" AS OF TIMESTAMP CAST(").render(moment).sql(" AS DATETIME(6))");
      },
    }),
  );
}

/**
 * 热点行的库存提示 `COMMIT_ON_SUCCESS ROLLBACK_ON_FAIL TARGET_AFFECT_ROW(n)`，写在 update、deleteFrom 上：
 * `update(Stock).with(() => inventory(1)).set(...).where(...)`。必须在事务里执行（自动提交时数据库报错）；
 * 语句正好改到 n 行时立即提交整个事务，否则报错并回滚整个事务，所以让它单独占一个事务或放在事务的最后一句。与 Java 一致。
 */
export function inventory(targetRows: number): Clause {
  if (!Number.isInteger(targetRows) || targetRows < 1) throw new RangeError("inventory needs at least one target row");
  return new Hint((w) => void w.sql(`COMMIT_ON_SUCCESS ROLLBACK_ON_FAIL TARGET_AFFECT_ROW(${targetRows})`), PolardbDialect);
}

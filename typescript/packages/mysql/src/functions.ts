import { aliasOf, Bound, call, Cte, Expr, FunctionDef, lit, param, text, type Clause, type KeepNull, type Lock, type Query, type Renderable, type SqlWriter } from "@zerodep-dev/query-core";
import { MysqlDialect } from "./dialect.js";

/** MySQL 专有的函数和子句；在其他方言上拒绝渲染。 */

const DATE_FORMAT = FunctionDef.only(MysqlDialect, "DATE_FORMAT");

/** DATE_FORMAT(value, format)，例如 `dateFormat(day, "%Y%m%d")`。 */
export function dateFormat<T>(value: Expr<T>, format: string): Expr<KeepNull<T, string>> {
  return call(DATE_FORMAT, text, value as Expr<unknown>, lit(format));
}

/** 优化器提示 MAX_EXECUTION_TIME：语句超过给定毫秒数后中止。 */
export function maxExecutionTime(milliseconds: number): Clause {
  if (!Number.isInteger(milliseconds) || milliseconds < 1) throw new RangeError("maxExecutionTime needs a positive number of milliseconds");
  const hint = `/*+ MAX_EXECUTION_TIME(${milliseconds}) */`;
  return { position: "hint", dialect: MysqlDialect, render: (w) => void w.sql(hint) };
}

const GREATEST = FunctionDef.only(MysqlDialect, "GREATEST");
const LEAST = FunctionDef.only(MysqlDialect, "LEAST");

/** GREATEST / LEAST 的参数：至少两个，值按第一个参数的类型绑定。 */
function extremeArgs<T>(name: string, first: Expr<T>, rest: readonly (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<unknown>[] {
  if (rest.length === 0) throw new TypeError(`${name} needs at least two arguments`);
  return [first, ...rest.map((v) => (v instanceof Expr ? v : param(first.type, v as NonNullable<T>)))];
}

/** GREATEST(a, b, ...)：只要有一个参数为 NULL，结果就是 NULL。 */
export function greatest<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(GREATEST, first.type, ...extremeArgs("GREATEST", first, rest));
}

/** LEAST(a, b, ...)：只要有一个参数为 NULL，结果就是 NULL。 */
export function least<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(LEAST, first.type, ...extremeArgs("LEAST", first, rest));
}

/** 渲染时确认当前方言是 MySQL，否则报错。 */
function requireMysql(w: SqlWriter): void {
  if (!(w.dialect instanceof MysqlDialect)) throw new Error(`A MySQL-only operation cannot run on ${w.dialect.name}`);
}

// ---------- 集合运算、窗口帧、递归 CTE、加锁 ----------

/** INTERSECT ALL：两边都有的行，保留重复（取两边次数的较小值）。规则同 `q1.unionAll(q2)`。 */
export function intersectAll<R>(left: Query<R>, right: Query<R>): Query<R> {
  return left.combine("INTERSECT ALL", right);
}

/** EXCEPT ALL：左边有、右边没有的行，按次数相减。规则同 `q1.unionAll(q2)`。 */
export function exceptAll<R>(left: Query<R>, right: Query<R>): Query<R> {
  return left.combine("EXCEPT ALL", right);
}

/**
 * RANGE 窗口帧：按排序值的距离取行，排序值相同的行一起进出，例如近 7 天的滚动合计
 * `over((w) => w.orderBy(l.createdOn).frame(range(daysPreceding(6), Bound.currentRow())))`。
 * 有限的偏移要求窗口只有一个排序项；数值偏移用 `Bound.preceding(n)`。
 */
export function range(start: Bound, end: Bound): Renderable {
  const sql = `RANGE BETWEEN ${start.sql} AND ${end.sql}`;
  return {
    render: (w) => {
      requireMysql(w);
      w.sql(sql);
    },
  };
}

/** RANGE 帧的起点：排序值（日期或时间戳）之前 days 天。 */
export function daysPreceding(days: number): Bound {
  return Bound.custom(`INTERVAL ${wholeDays(days)} DAY PRECEDING`);
}

/** RANGE 帧的终点：排序值之后 days 天。 */
export function daysFollowing(days: number): Bound {
  return Bound.custom(`INTERVAL ${wholeDays(days)} DAY FOLLOWING`);
}

/** 校验天数是非负整数。 */
function wholeDays(days: number): number {
  if (!Number.isInteger(days) || days < 0) throw new RangeError("Frame offsets must be non-negative integers");
  return days;
}

/**
 * 递归 CTE：`anchor UNION ALL step(自己)`，step 里 `from(self)` 取上一轮的行，例如逐层展开：
 * `recursiveCte("steps", from(Lips).where(...).select((l) => ({ delivery: l.delivery, depth: lit(1) })), (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: plus(s.depth, lit(1)) })))`。
 * 语句里有递归 CTE 时写成 `WITH RECURSIVE`；step 必须有终止条件。
 */
export function recursiveCte<E>(name: string, anchor: Query<E>, step: (self: Cte<E>) => Query<E>): Cte<E> {
  return Cte.recursive(name, anchor, step, MysqlDialect);
}

/** 共享锁读 `FOR SHARE`：别人可以读、也可以再加共享锁，但改不了；lock 同 `forUpdate(lock)`。 */
export function forShare(lock: Lock = "wait"): Clause {
  return lockClause("FOR SHARE", lock, []);
}

/** 只锁 tables 这几张表的行：`.with((l, m) => forUpdateOf("skipLocked", l))` 写成 `FOR UPDATE OF 别名 SKIP LOCKED`。 */
export function forUpdateOf(lock: Lock, ...tables: object[]): Clause {
  if (tables.length === 0) throw new TypeError("forUpdateOf(...) needs at least one table");
  return lockClause("FOR UPDATE", lock, tables);
}

/** `FOR UPDATE / FOR SHARE [OF 别名, ...] [NOWAIT | SKIP LOCKED]`。 */
function lockClause(mode: string, lock: Lock, tables: readonly object[]): Clause {
  return {
    position: "end",
    dialect: MysqlDialect,
    render: (w) => {
      w.sql(mode);
      tables.forEach((t, i) => void w.sql(i === 0 ? " OF " : ", ").alias(aliasOf(w, t)));
      if (lock === "nowait") w.sql(" NOWAIT");
      else if (lock === "skipLocked") w.sql(" SKIP LOCKED");
    },
  };
}

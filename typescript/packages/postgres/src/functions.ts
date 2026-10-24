import { aliasOf, Bound, call, cast, CastType, Cond, condition, Cte, double, Expr, expression, FunctionDef, lit, param, skips, untyped, type Clause, type Lock, type Query, type Renderable, type SqlType, type SqlWriter } from "@zerodep-dev/query-core";
import { PostgresDialect } from "./dialect.js";

/** PostgreSQL 专有的函数和子句；在其他方言上拒绝渲染。 */

/** 渲染时确认当前方言是 PostgreSQL，否则报错。 */
function requirePostgres(w: SqlWriter): void {
  if (!(w.dialect instanceof PostgresDialect)) throw new Error(`A PostgreSQL-only operation cannot run on ${w.dialect.name}`);
}

/** PostgreSQL 专有的条件，渲染时检查方言。 */
function pgCond(render: (w: SqlWriter) => void): Cond {
  return condition((w) => {
    requirePostgres(w);
    render(w);
  });
}

/** DISTINCT ON (keys)：每个键只留第一行；ORDER BY 必须以相同的键开头。 */
export function distinctOn(...keys: Expr<unknown>[]): Clause {
  if (keys.length === 0) throw new TypeError("distinctOn() needs at least one key");
  return { position: "distinct", dialect: PostgresDialect, render: (w) => void w.sql("DISTINCT ON (").join(keys, ", ").sql(")") };
}

/** ILIKE：不区分大小写的 LIKE。skipEmpty 下模式为空时没有条件。 */
export function ilike(e: Expr<string | null>, pattern: string | null | undefined): Cond {
  if (skips(pattern)) return Cond.EMPTY;
  const p = param(e.type, pattern as string);
  return pgCond((w) => w.render(e).sql(" ILIKE ").render(p));
}

/** CUBE 与 GROUPING SETS 只能出现在 GROUP BY 里。 */
const CUBE = FunctionDef.only(PostgresDialect, "CUBE");
const GROUPING_SETS = FunctionDef.only(PostgresDialect, "GROUPING SETS", (w, args) => void w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

/** CUBE(keys) 作为 GROUP BY 的键：对键的每种组合输出小计。 */
export function cube(...keys: Expr<unknown>[]): Expr<unknown> {
  return call(CUBE, untyped as SqlType<{}>, ...keys);
}

/** GROUPING SETS ((a, b), (a), ()) 作为 GROUP BY 的键：只输出列出的分组；空列表表示总计。 */
export function groupingSets(...sets: (readonly Expr<unknown>[])[]): Expr<unknown> {
  const args = sets.map((set) => ({ render: (w: SqlWriter) => void w.sql("(").join(set, ", ").sql(")") }));
  return expression(untyped as SqlType<{}>, (w) => GROUPING_SETS.render(w, args));
}

const GREATEST = FunctionDef.only(PostgresDialect, "GREATEST");
const LEAST = FunctionDef.only(PostgresDialect, "LEAST");

/** GREATEST / LEAST 的参数：至少两个，值按第一个参数的类型绑定。 */
function extremeArgs<T>(name: string, first: Expr<T>, rest: readonly (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<unknown>[] {
  if (rest.length === 0) throw new TypeError(`${name} needs at least two arguments`);
  return [first, ...rest.map((v) => (v instanceof Expr ? v : param(first.type, v as NonNullable<T>)))];
}

/** GREATEST(a, b, ...)：跳过 NULL 参数，只有全部为 NULL 时结果才是 NULL。 */
export function greatest<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(GREATEST, first.type, ...extremeArgs("GREATEST", first, rest));
}

/** LEAST(a, b, ...)：跳过 NULL 参数，只有全部为 NULL 时结果才是 NULL。 */
export function least<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(LEAST, first.type, ...extremeArgs("LEAST", first, rest));
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
      requirePostgres(w);
      w.sql(sql);
    },
  };
}

/** RANGE 帧的起点：排序值（日期或时间戳）之前 days 天。 */
export function daysPreceding(days: number): Bound {
  return Bound.custom(`INTERVAL '${wholeDays(days)} days' PRECEDING`);
}

/** RANGE 帧的终点：排序值之后 days 天。 */
export function daysFollowing(days: number): Bound {
  return Bound.custom(`INTERVAL '${wholeDays(days)} days' FOLLOWING`);
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
  return Cte.recursive(name, anchor, step, PostgresDialect);
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
    dialect: PostgresDialect,
    render: (w) => {
      w.sql(mode);
      tables.forEach((t, i) => void w.sql(i === 0 ? " OF " : ", ").alias(aliasOf(w, t)));
      if (lock === "nowait") w.sql(" NOWAIT");
      else if (lock === "skipLocked") w.sql(" SKIP LOCKED");
    },
  };
}

// ---------- 统计 ----------

const PERCENTILE_CONT = FunctionDef.only(PostgresDialect, "PERCENTILE_CONT", (w, args) => void w.sql("PERCENTILE_CONT(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")"));
const PERCENTILE_DISC = FunctionDef.only(PostgresDialect, "PERCENTILE_DISC", (w, args) => void w.sql("PERCENTILE_DISC(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")"));
const MODE = FunctionDef.only(PostgresDialect, "MODE", (w, args) => void w.sql("MODE() WITHIN GROUP (ORDER BY ").render(args[0]!).sql(")"));
const CORR = FunctionDef.only(PostgresDialect, "CORR");

/** 连续百分位数 `PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY e)`，在相邻两行之间插值；p 取 0 到 1，中位数是 0.5。只能作聚合。 */
export function percentileCont(p: number, e: Expr<unknown>): Expr<number | null> {
  return call(PERCENTILE_CONT, double, lit(fraction(p)), cast(e, CastType.double()));
}

/** 离散百分位数 `PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY e)`：取排序后第一个累计比例不小于 p 的值。只能作聚合。 */
export function percentileDisc<T>(p: number, e: Expr<T>): Expr<T | null> {
  return call(PERCENTILE_DISC, e.type, lit(fraction(p)), e as Expr<unknown>);
}

/** 众数 `MODE() WITHIN GROUP (ORDER BY e)`：出现次数最多的值，并列时取最小的。只能作聚合。 */
export function mode<T>(e: Expr<T>): Expr<T | null> {
  return call(MODE, e.type, e as Expr<unknown>);
}

/** 相关系数 `CORR(y, x)`，取值 -1 到 1。 */
export function corr(y: Expr<unknown>, x: Expr<unknown>): Expr<number | null> {
  return call(CORR, double, y, x);
}

/** 校验百分位数在 0 到 1 之间。 */
function fraction(p: number): number {
  if (!(p >= 0 && p <= 1)) throw new RangeError(`A percentile must be between 0 and 1, got ${p}`);
  return p;
}

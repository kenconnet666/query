import { call, Cond, condition, Expr, expression, FunctionDef, lit, param, skips, text, toOrder, untyped, type Aggregate, type Clause, type OrderItem, type SqlType, type SqlWriter } from "@zerodep-dev/query-core";
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

/** PostgreSQL 专有的表达式，类型取自 `like`，渲染时检查方言。 */
function pgExpr<T>(like: Expr<T>, render: (w: SqlWriter) => void): Expr<T> {
  return expression(like.type, (w) => {
    requirePostgres(w);
    render(w);
  });
}

/** DISTINCT ON (keys)：每个键只留第一行；ORDER BY 必须以相同的键开头。 */
export function distinctOn(...keys: Expr<unknown>[]): Clause {
  if (keys.length === 0) throw new TypeError("distinctOn() needs at least one key");
  return { position: "distinct", dialect: PostgresDialect, render: (w) => void w.sql("DISTINCT ON (").join(keys, ", ").sql(")") };
}

/** PostgreSQL 专有、要求操作数同类型的运算符；由接收者确定类型。通过 {@link pg} 获得。 */
export class PgOps<T> {
  constructor(readonly expr: Expr<T>) {}

  /** IS DISTINCT FROM：把 NULL 当作普通值来比较，两个 NULL 视为相等。 */
  isDistinctFrom(value: NonNullable<T>): Cond;
  isDistinctFrom(other: Expr<NonNullable<T> | null>): Cond;
  isDistinctFrom(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    const other = this.#operand(arg);
    return pgCond((w) => w.render(this.expr).sql(" IS DISTINCT FROM ").render(other));
  }

  /** IS NOT DISTINCT FROM：`isDistinctFrom` 的反面。 */
  isNotDistinctFrom(value: NonNullable<T>): Cond;
  isNotDistinctFrom(other: Expr<NonNullable<T> | null>): Cond;
  isNotDistinctFrom(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    const other = this.#operand(arg);
    return pgCond((w) => w.render(this.expr).sql(" IS NOT DISTINCT FROM ").render(other));
  }

  /** 表达式原样使用，值则按本表达式的类型绑定成参数。 */
  #operand(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<unknown> {
    return arg instanceof Expr ? (arg as Expr<unknown>) : param(this.expr.type, arg);
  }
}

/** 取表达式上 PostgreSQL 专有的运算：`pg(col).isDistinctFrom(x)`。 */
export function pg<T>(expr: Expr<T>): PgOps<T> {
  return new PgOps(expr);
}

/** ILIKE：不区分大小写的 LIKE。skipEmpty 下模式为空时没有条件。 */
export function ilike(e: Expr<string | null>, pattern: string | null | undefined): Cond {
  if (skips(pattern)) return Cond.EMPTY;
  const p = param(e.type, pattern as string);
  return pgCond((w) => w.render(e).sql(" ILIKE ").render(p));
}

/** 聚合的 FILTER (WHERE 条件)。取名区别于核心包的全局过滤器 `filter`。 */
export function filterWhere<T>(aggregate: Aggregate<T>, cond: Cond): Expr<T> {
  return pgExpr(aggregate, (w) => w.render(aggregate).sql(" FILTER (WHERE ").render(cond).sql(")"));
}

/** STRING_AGG(value, separator ORDER BY ...)：把多行文本用分隔符聚合成一个。 */
export function stringAgg(value: Expr<string | null>, separator: string, ...order: OrderItem[]): Expr<string | null> {
  const sep = lit(separator, text);
  const orders = order.map(toOrder);
  return pgExpr(value, (w) => {
    w.sql("STRING_AGG(").render(value).sql(", ").render(sep);
    if (orders.length > 0) w.sql(" ORDER BY ").join(orders, ", ");
    w.sql(")");
  });
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

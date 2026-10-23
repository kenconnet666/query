import { call, Cond, condition, Expr, expression, FunctionDef, lit, param, skips, text, toOrder, untyped, type Aggregate, type Clause, type OrderItem, type SqlType, type SqlWriter } from "@zerodep-dev/query-core";
import { PostgresDialect } from "./dialect.js";

/** PostgreSQL-only functions and clauses; they refuse to render on another dialect. */

function requirePostgres(w: SqlWriter): void {
  if (!(w.dialect instanceof PostgresDialect)) throw new Error(`A PostgreSQL-only operation cannot run on ${w.dialect.name}`);
}

function pgCond(render: (w: SqlWriter) => void): Cond {
  return condition((w) => {
    requirePostgres(w);
    render(w);
  });
}

function pgExpr<T>(like: Expr<T>, render: (w: SqlWriter) => void): Expr<T> {
  return expression(like.type, (w) => {
    requirePostgres(w);
    render(w);
  });
}

/** DISTINCT ON (keys): the first row per key; ORDER BY must start with the same keys. */
export function distinctOn(...keys: Expr<unknown>[]): Clause {
  if (keys.length === 0) throw new TypeError("distinctOn() needs at least one key");
  return { position: "distinct", dialect: PostgresDialect, render: (w) => void w.sql("DISTINCT ON (").join(keys, ", ").sql(")") };
}

/** PostgreSQL-only operators whose operands must share a type; the receiver fixes the type. */
export class PgOps<T> {
  constructor(readonly expr: Expr<T>) {}

  isDistinctFrom(value: NonNullable<T>): Cond;
  isDistinctFrom(other: Expr<NonNullable<T> | null>): Cond;
  isDistinctFrom(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    const other = this.#operand(arg);
    return pgCond((w) => w.render(this.expr).sql(" IS DISTINCT FROM ").render(other));
  }

  isNotDistinctFrom(value: NonNullable<T>): Cond;
  isNotDistinctFrom(other: Expr<NonNullable<T> | null>): Cond;
  isNotDistinctFrom(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    const other = this.#operand(arg);
    return pgCond((w) => w.render(this.expr).sql(" IS NOT DISTINCT FROM ").render(other));
  }

  #operand(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<unknown> {
    return arg instanceof Expr ? (arg as Expr<unknown>) : param(this.expr.type, arg);
  }
}

export function pg<T>(expr: Expr<T>): PgOps<T> {
  return new PgOps(expr);
}

export function ilike(e: Expr<string | null>, pattern: string | null | undefined): Cond {
  if (skips(pattern)) return Cond.EMPTY;
  const p = param(e.type, pattern as string);
  return pgCond((w) => w.render(e).sql(" ILIKE ").render(p));
}

/** aggregate FILTER (WHERE condition). Named apart from the global `filter` of the core package. */
export function filterWhere<T>(aggregate: Aggregate<T>, cond: Cond): Expr<T> {
  return pgExpr(aggregate, (w) => w.render(aggregate).sql(" FILTER (WHERE ").render(cond).sql(")"));
}

/** STRING_AGG(value, separator ORDER BY ...). */
export function stringAgg(value: Expr<string | null>, separator: string, ...order: OrderItem[]): Expr<string | null> {
  const sep = lit(separator, text);
  const orders = order.map(toOrder);
  return pgExpr(value, (w) => {
    w.sql("STRING_AGG(").render(value).sql(", ").render(sep);
    if (orders.length > 0) w.sql(" ORDER BY ").join(orders, ", ");
    w.sql(")");
  });
}

const CUBE = FunctionDef.only(PostgresDialect, "CUBE");
const GROUPING_SETS = FunctionDef.only(PostgresDialect, "GROUPING SETS", (w, args) => void w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

/** CUBE(keys) as a GROUP BY key: subtotals for every combination of the keys. */
export function cube(...keys: Expr<unknown>[]): Expr<unknown> {
  return call(CUBE, untyped as SqlType<{}>, ...keys);
}

/** GROUPING SETS ((a, b), (a), ()) as a GROUP BY key: exactly the listed groupings; an empty list is the grand total. */
export function groupingSets(...sets: (readonly Expr<unknown>[])[]): Expr<unknown> {
  const args = sets.map((set) => ({ render: (w: SqlWriter) => void w.sql("(").join(set, ", ").sql(")") }));
  return expression(untyped as SqlType<{}>, (w) => GROUPING_SETS.render(w, args));
}

const GREATEST = FunctionDef.only(PostgresDialect, "GREATEST");
const LEAST = FunctionDef.only(PostgresDialect, "LEAST");

function extremeArgs<T>(name: string, first: Expr<T>, rest: readonly (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<unknown>[] {
  if (rest.length === 0) throw new TypeError(`${name} needs at least two arguments`);
  return [first, ...rest.map((v) => (v instanceof Expr ? v : param(first.type, v as NonNullable<T>)))];
}

/** GREATEST(a, b, ...): NULL arguments are skipped; NULL only when all are NULL. */
export function greatest<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(GREATEST, first.type, ...extremeArgs("GREATEST", first, rest));
}

/** LEAST(a, b, ...): NULL arguments are skipped; NULL only when all are NULL. */
export function least<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(LEAST, first.type, ...extremeArgs("LEAST", first, rest));
}

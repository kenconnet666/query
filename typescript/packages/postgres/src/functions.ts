import { condition, Expr, expression, lit, param, text, toOrder, type Aggregate, type Clause, type Cond, type OrderItem, type SqlWriter } from "@query/core";
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

export function ilike(e: Expr<string | null>, pattern: string): Cond {
  const p = param(e.type, pattern);
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

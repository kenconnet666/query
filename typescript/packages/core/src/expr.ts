import { FunctionDef } from "./dialect.js";
import { COLUMN_TYPES, SUBQUERY } from "./internal.js";
import type { Query } from "./query.js";
import { typeOf, type CastType, type SqlType } from "./types.js";
import type { Renderable, SqlWriter } from "./writer.js";

declare const valueType: unique symbol;

/** `U`, or `U | null` when `T` may be null: functions keep the nullability of their operand. */
export type KeepNull<T, U> = null extends T ? U | null : U;

/**
 * An SQL expression producing values of TypeScript type T (null included when the value may be
 * missing). Operations whose operands must share T are instance methods: the receiver fixes T
 * before the argument is checked, so a text column never compares with a number. Arguments are
 * overloads (value, or expression) rather than a `value | Expr` union, which would weaken inference.
 *
 * Extend this class (or use {@link expression}) to add an expression node.
 */
export abstract class Expr<T> implements Renderable {
  declare readonly [valueType]: T;

  constructor(readonly type: SqlType<NonNullable<T>>) {}

  abstract render(w: SqlWriter): void;

  /** The column label used when this expression is selected alone; a column's property name. */
  get label(): string | undefined {
    return undefined;
  }

  // ---------- comparison ----------

  eq(value: NonNullable<T>): Cond;
  eq(other: Expr<NonNullable<T> | null>): Cond;
  eq(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    return compare(this, "=", this.#operand(arg));
  }

  ne(value: NonNullable<T>): Cond;
  ne(other: Expr<NonNullable<T> | null>): Cond;
  ne(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    return compare(this, "<>", this.#operand(arg));
  }

  gt(value: NonNullable<T>): Cond;
  gt(other: Expr<NonNullable<T> | null>): Cond;
  gt(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    return compare(this, ">", this.#operand(arg));
  }

  ge(value: NonNullable<T>): Cond;
  ge(other: Expr<NonNullable<T> | null>): Cond;
  ge(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    return compare(this, ">=", this.#operand(arg));
  }

  lt(value: NonNullable<T>): Cond;
  lt(other: Expr<NonNullable<T> | null>): Cond;
  lt(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    return compare(this, "<", this.#operand(arg));
  }

  le(value: NonNullable<T>): Cond;
  le(other: Expr<NonNullable<T> | null>): Cond;
  le(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    return compare(this, "<=", this.#operand(arg));
  }

  between(low: NonNullable<T>, high: NonNullable<T>): Cond;
  between(low: Expr<NonNullable<T> | null>, high: Expr<NonNullable<T> | null>): Cond;
  between(low: NonNullable<T> | Expr<NonNullable<T> | null>, high: NonNullable<T> | Expr<NonNullable<T> | null>): Cond {
    const [lo, hi] = [this.#operand(low), this.#operand(high)];
    return condition((w) => w.render(this).sql(" BETWEEN ").render(lo).sql(" AND ").render(hi));
  }

  /** An empty list is always false; long lists are split by the dialect's IN limit. */
  in(values: readonly NonNullable<T>[]): Cond;
  in(subquery: Query<NonNullable<T> | null>): Cond;
  in(arg: readonly NonNullable<T>[] | Query<NonNullable<T> | null>): Cond {
    return Array.isArray(arg) ? inList(this, arg.map((v) => this.#param(v)), false) : inQuery(this, arg as Query<unknown>, false);
  }

  /** An empty list is always true. */
  notIn(values: readonly NonNullable<T>[]): Cond;
  notIn(subquery: Query<NonNullable<T> | null>): Cond;
  notIn(arg: readonly NonNullable<T>[] | Query<NonNullable<T> | null>): Cond {
    return Array.isArray(arg) ? inList(this, arg.map((v) => this.#param(v)), true) : inQuery(this, arg as Query<unknown>, true);
  }

  isNull(): Cond {
    return condition((w) => w.render(this).sql(" IS NULL"));
  }

  isNotNull(): Cond {
    return condition((w) => w.render(this).sql(" IS NOT NULL"));
  }

  // ---------- optional filters: null or undefined (an empty list) means "no condition" ----------

  eqIfPresent(value: NonNullable<T> | null | undefined): Cond {
    return value == null ? Cond.EMPTY : this.eq(value);
  }

  neIfPresent(value: NonNullable<T> | null | undefined): Cond {
    return value == null ? Cond.EMPTY : this.ne(value);
  }

  gtIfPresent(value: NonNullable<T> | null | undefined): Cond {
    return value == null ? Cond.EMPTY : this.gt(value);
  }

  geIfPresent(value: NonNullable<T> | null | undefined): Cond {
    return value == null ? Cond.EMPTY : this.ge(value);
  }

  ltIfPresent(value: NonNullable<T> | null | undefined): Cond {
    return value == null ? Cond.EMPTY : this.lt(value);
  }

  leIfPresent(value: NonNullable<T> | null | undefined): Cond {
    return value == null ? Cond.EMPTY : this.le(value);
  }

  /** Each bound applies only when present. */
  betweenIfPresent(low: NonNullable<T> | null | undefined, high: NonNullable<T> | null | undefined): Cond {
    return all(this.geIfPresent(low), this.leIfPresent(high));
  }

  inIfPresent(values: readonly NonNullable<T>[] | null | undefined): Cond {
    return values == null || values.length === 0 ? Cond.EMPTY : this.in(values);
  }

  // ---------- same-type functions ----------

  /** The first non-null of this and the fallbacks; only a last fallback that is never null makes the result non-null. */
  coalesce(fallback: NonNullable<T>): Expr<NonNullable<T>>;
  coalesce(fallback: Expr<NonNullable<T>>): Expr<NonNullable<T>>;
  coalesce(...fallbacks: [...Expr<NonNullable<T> | null>[], NonNullable<T> | Expr<NonNullable<T>>]): Expr<NonNullable<T>>;
  coalesce(...fallbacks: Expr<NonNullable<T> | null>[]): Expr<NonNullable<T> | null>;
  coalesce(...fallbacks: (NonNullable<T> | Expr<NonNullable<T> | null>)[]): Expr<NonNullable<T> | null> {
    if (fallbacks.length === 0) throw new TypeError("coalesce() needs at least one fallback");
    return functionCall<NonNullable<T> | null>(this.type, "COALESCE", [this, ...fallbacks.map((f) => this.#operand(f))]);
  }

  nullif(value: NonNullable<T>): Expr<NonNullable<T> | null> {
    return functionCall<NonNullable<T> | null>(this.type, "NULLIF", [this, this.#param(value)]);
  }

  // ---------- naming, ordering, conversion ----------

  as(label: string): Expr<T> {
    return new Labeled(this, label);
  }

  asc(): Order {
    return new Order(this, false, undefined);
  }

  desc(): Order {
    return new Order(this, true, undefined);
  }

  cast<U>(target: CastType<U>): Expr<KeepNull<T, U>> {
    return new CastExpr(this, target as CastType<unknown>) as unknown as Expr<KeepNull<T, U>>;
  }

  #param(value: NonNullable<T>): Expr<NonNullable<T>> {
    return param(this.type, value);
  }

  #operand(arg: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<unknown> {
    return arg instanceof Expr ? arg : this.#param(arg);
  }
}

/** A bound parameter of a type, so a mounted converter applies to the value. */
export function param<T>(type: SqlType<T>, value: T): Expr<T> {
  if (value == null) throw new TypeError("Comparing with NULL never matches; use isNull() or an ...IfPresent method");
  return new Param(type as SqlType<NonNullable<T>>, value);
}

/** An expression node written by a function; the way dialect packages add their own syntax. */
export function expression<T>(type: SqlType<NonNullable<T>>, render: (w: SqlWriter) => void): Expr<T> {
  return new (class extends Expr<T> {
    render(w: SqlWriter): void {
      render(w);
    }
  })(type);
}

// ---------- conditions ----------

/**
 * A condition. Kept apart from `Expr<boolean>` because some databases (HANA) cannot select a
 * predicate directly. {@link Cond.EMPTY} stands for "no condition" and disappears when combined.
 */
export abstract class Cond implements Renderable {
  static readonly EMPTY: Cond = new (class extends Cond {
    render(): void {
      throw new Error("Cond.EMPTY is dropped before rendering");
    }

    override toString(): string {
      return "EMPTY";
    }
  })();

  abstract render(w: SqlWriter): void;

  and(other: Cond): Cond {
    return all(this, other);
  }

  or(other: Cond): Cond {
    return any(this, other);
  }

  not(): Cond {
    return not(this);
  }

  get isEmpty(): boolean {
    return this === Cond.EMPTY;
  }
}

/** A condition node written by a function; the way dialect packages add their own predicates. */
export function condition(render: (w: SqlWriter) => void): Cond {
  return new (class extends Cond {
    render(w: SqlWriter): void {
      render(w);
    }
  })();
}

function kept(conds: readonly Cond[]): Cond[] {
  return conds.filter((c) => {
    if (c == null) throw new TypeError("A condition is null or undefined; use Cond.EMPTY for no condition");
    return !c.isEmpty;
  });
}

export function all(...conds: readonly Cond[]): Cond {
  const list = kept(conds);
  if (list.length === 0) return Cond.EMPTY;
  if (list.length === 1) return list[0]!;
  return condition((w) => w.join(list, " AND "));
}

/** Each operand in parentheses, so an AND inside an OR keeps its meaning. */
export function any(...conds: readonly Cond[]): Cond {
  const list = kept(conds);
  if (list.length === 0) return Cond.EMPTY;
  if (list.length === 1) return list[0]!;
  return condition((w) => {
    w.sql("(");
    list.forEach((c, i) => w.sql(i > 0 ? " OR (" : "(").render(c).sql(")"));
    w.sql(")");
  });
}

export function not(c: Cond): Cond {
  return c.isEmpty ? Cond.EMPTY : condition((w) => w.sql("NOT (").render(c).sql(")"));
}

const FALSE = condition((w) => w.sql("1 = 0"));
const TRUE = condition((w) => w.sql("1 = 1"));

export function compare(left: Renderable, operator: string, right: Renderable): Cond {
  return condition((w) => w.render(left).sql(` ${operator} `).render(right));
}

function inList(e: Expr<unknown>, values: readonly Expr<unknown>[], negate: boolean): Cond {
  if (values.length === 0) return negate ? TRUE : FALSE;
  const keyword = negate ? " NOT IN (" : " IN (";
  return condition((w) => {
    const max = Math.max(1, w.dialect.maxInListSize);
    if (values.length <= max) {
      w.render(e).sql(keyword).join(values, ", ").sql(")");
      return;
    }
    w.sql("(");
    for (let from = 0; from < values.length; from += max) {
      if (from > 0) w.sql(negate ? " AND " : " OR ");
      w.render(e).sql(keyword).join(values.slice(from, from + max), ", ").sql(")");
    }
    w.sql(")");
  });
}

function inQuery(e: Expr<unknown>, query: Query<unknown>, negate: boolean): Cond {
  return condition((w) => {
    w.render(e).sql(negate ? " NOT IN (" : " IN (");
    query[SUBQUERY](w);
    w.sql(")");
  });
}

export function exists(subquery: Query<unknown>): Cond {
  return condition((w) => {
    w.sql("EXISTS (");
    subquery[SUBQUERY](w);
    w.sql(")");
  });
}

export function notExists(subquery: Query<unknown>): Cond {
  return condition((w) => {
    w.sql("NOT EXISTS (");
    subquery[SUBQUERY](w);
    w.sql(")");
  });
}

// ---------- ordering ----------

/** One ORDER BY item. */
export class Order implements Renderable {
  constructor(
    readonly expr: Expr<unknown>,
    readonly descending: boolean,
    readonly nullsPlacement: boolean | undefined,
  ) {}

  nullsFirst(): Order {
    return new Order(this.expr, this.descending, true);
  }

  nullsLast(): Order {
    return new Order(this.expr, this.descending, false);
  }

  render(w: SqlWriter): void {
    w.dialect.orderItem(w, this.expr, this.descending, this.nullsPlacement);
  }
}

/** An ORDER BY item as written by users: an Order, or an expression meaning ascending. */
export type OrderItem = Order | Expr<unknown>;

export function toOrder(item: OrderItem): Order {
  return item instanceof Order ? item : item.asc();
}

// ---------- expression nodes ----------

class Param<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly value: T,
  ) {
    super(type);
  }

  render(w: SqlWriter): void {
    const type = this.type as SqlType<unknown>;
    if (w.inlining && INLINABLE.has(type.kind)) w.literal(this.value, type);
    else w.bind(this.value, type);
  }
}

/** Kinds a dialect can write as literals; others stay parameters even when the writer inlines. */
const INLINABLE = new Set(["text", "integer", "bigint", "double", "decimal", "boolean", "date", "timestamp"]);

export class Literal<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly value: T,
  ) {
    super(type);
  }

  render(w: SqlWriter): void {
    w.literal(this.value, this.type as SqlType<unknown>);
  }
}

/** Renders a column of one source occurrence, with the alias assigned for the statement. */
export interface ColumnOwner {
  renderColumn(w: SqlWriter, column: SourceColumn): void;
}

/** A column a source offers: the property name and its type. */
export interface SourceColumn {
  readonly name: string;
  readonly type: SqlType<unknown>;
}

export class Column<T> extends Expr<T> {
  constructor(
    readonly owner: ColumnOwner,
    readonly column: SourceColumn,
  ) {
    super(column.type as SqlType<NonNullable<T>>);
  }

  render(w: SqlWriter): void {
    this.owner.renderColumn(w, this.column);
  }

  override get label(): string {
    return this.column.name;
  }
}

class Labeled<T> extends Expr<T> {
  readonly #label: string;

  constructor(
    readonly inner: Expr<T>,
    label: string,
  ) {
    super(inner.type);
    if (label.trim() === "") throw new TypeError("A label must not be blank");
    this.#label = label;
  }

  render(w: SqlWriter): void {
    this.inner.render(w);
  }

  override get label(): string {
    return this.#label;
  }
}

class Call<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly fn: FunctionDef | string,
    readonly args: readonly Renderable[],
  ) {
    super(type);
  }

  render(w: SqlWriter): void {
    if (typeof this.fn === "string") FunctionDef.call(w, this.fn, this.args);
    else this.fn.render(w, this.args);
  }
}

export function functionCall<T>(type: SqlType<NonNullable<T>>, fn: FunctionDef | string, args: readonly Renderable[]): Expr<T> {
  return new Call(type, fn, args);
}

/** Arguments of an SQL template: expressions render in place, other values become parameters. */
export type TemplateArg = Renderable | string | number | bigint | boolean | Uint8Array;

export function renderTemplate(w: SqlWriter, parts: readonly string[], args: readonly TemplateArg[]): void {
  parts.forEach((part, i) => {
    w.sql(part);
    if (i < args.length) {
      const arg = args[i]!;
      if (typeof arg === "object" && !(arg instanceof Uint8Array)) w.render(arg);
      else w.bind(arg, typeOf(arg));
    }
  });
}

export class Arithmetic<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly operator: string,
    readonly operands: readonly Expr<unknown>[],
  ) {
    super(type);
  }

  render(w: SqlWriter): void {
    w.sql("(").join(this.operands, ` ${this.operator} `).sql(")");
  }
}

class CastExpr extends Expr<unknown> {
  constructor(
    readonly inner: Expr<unknown>,
    readonly target: CastType<unknown>,
  ) {
    super(target.type as SqlType<{}>);
  }

  render(w: SqlWriter): void {
    w.sql("CAST(").render(this.inner).sql(" AS ").sql(w.dialect.castType(this.target)).sql(")");
  }
}

export class Scalar<T> extends Expr<T | null> {
  constructor(readonly query: Query<T>) {
    const types = query[COLUMN_TYPES];
    if (types.length !== 1) throw new Error(`A scalar subquery must select exactly one column, this one selects ${types.length}`);
    super(types[0] as SqlType<NonNullable<T>>);
  }

  render(w: SqlWriter): void {
    w.sql("(");
    this.query[SUBQUERY](w);
    w.sql(")");
  }
}

/** Fixed SQL text such as NULL or CURRENT_DATE. */
export function raw<T>(type: SqlType<NonNullable<T>>, sql: string): Expr<T> {
  return expression(type, (w) => w.sql(sql));
}

// ---------- windows, aggregates, CASE ----------

/** A frame boundary of a ROWS window. */
export class Bound {
  private constructor(readonly sql: string) {}

  static unboundedPreceding(): Bound {
    return new Bound("UNBOUNDED PRECEDING");
  }

  static preceding(rows: number): Bound {
    return new Bound(`${Bound.#rows(rows)} PRECEDING`);
  }

  static currentRow(): Bound {
    return new Bound("CURRENT ROW");
  }

  static following(rows: number): Bound {
    return new Bound(`${Bound.#rows(rows)} FOLLOWING`);
  }

  static unboundedFollowing(): Bound {
    return new Bound("UNBOUNDED FOLLOWING");
  }

  static #rows(rows: number): number {
    if (!Number.isInteger(rows) || rows < 0) throw new RangeError("Frame offsets must be non-negative integers");
    return rows;
  }
}

/** An OVER (...) specification, built inside `over(w => w.partitionBy(...).orderBy(...))`. */
export class Window implements Renderable {
  static readonly EMPTY = new Window([], [], undefined);

  private constructor(
    readonly partition: readonly Expr<unknown>[],
    readonly order: readonly Order[],
    readonly frame: string | undefined,
  ) {}

  partitionBy(...keys: Expr<unknown>[]): Window {
    return new Window(keys, this.order, this.frame);
  }

  orderBy(...items: OrderItem[]): Window {
    return new Window(this.partition, items.map(toOrder), this.frame);
  }

  /** ROWS BETWEEN start AND end; RANGE frames differ between databases and live in dialect packages. */
  rows(start: Bound, end: Bound): Window {
    return new Window(this.partition, this.order, `ROWS BETWEEN ${start.sql} AND ${end.sql}`);
  }

  render(w: SqlWriter): void {
    let sep = "";
    if (this.partition.length > 0) {
      w.sql("PARTITION BY ").join(this.partition, ", ");
      sep = " ";
    }
    if (this.order.length > 0) {
      w.sql(sep + "ORDER BY ").join(this.order, ", ");
      sep = " ";
    }
    if (this.frame !== undefined) w.sql(sep + this.frame);
  }
}

class Over<T> extends Expr<T> {
  constructor(
    readonly fn: Expr<T>,
    readonly window: Window,
  ) {
    super(fn.type);
  }

  render(w: SqlWriter): void {
    w.render(this.fn).sql(" OVER (").render(this.window).sql(")");
  }
}

/** An aggregate such as SUM(x); usable as is, or as a window function through {@link over}. */
export class Aggregate<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly name: string,
    readonly distinct: boolean,
    readonly args: readonly Expr<unknown>[],
  ) {
    super(type);
  }

  over(window: (w: Window) => Window): Expr<T> {
    return new Over(this, window(Window.EMPTY));
  }

  render(w: SqlWriter): void {
    w.sql(this.name + "(");
    if (this.distinct) w.sql("DISTINCT ");
    if (this.args.length === 0) w.sql("*");
    else w.join(this.args, ", ");
    w.sql(")");
  }
}

/** ROW_NUMBER, RANK, LAG and friends: not an expression until {@link over} gives it a window. */
export class WindowFunction<T> {
  constructor(
    readonly type: SqlType<NonNullable<T>>,
    readonly name: string,
    readonly args: readonly Renderable[],
  ) {}

  over(window: (w: Window) => Window): Expr<T> {
    return new Over(functionCall<T>(this.type, this.name, this.args), window(Window.EMPTY));
  }
}

/** CASE WHEN ... THEN ... builder; the first branch fixes T for all others. */
export class Case<T> {
  constructor(
    readonly type: SqlType<NonNullable<T>>,
    readonly branches: readonly (readonly [Cond, Expr<unknown>])[],
  ) {}

  when(condition: Cond, result: NonNullable<T>): Case<T>;
  when(condition: Cond, result: Expr<NonNullable<T>>): Case<T>;
  when(condition: Cond, result: Expr<NonNullable<T> | null>): Case<T | null>;
  when(condition: Cond, result: NonNullable<T> | Expr<NonNullable<T> | null>): Case<T | null> {
    return new Case<T | null>(this.type, [...this.branches, [condition, this.#operand(result)]]);
  }

  otherwise(result: NonNullable<T>): Expr<T>;
  otherwise(result: Expr<NonNullable<T>>): Expr<T>;
  otherwise(result: Expr<NonNullable<T> | null>): Expr<T | null>;
  otherwise(result: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<T | null> {
    return this.#build(this.#operand(result));
  }

  /** No ELSE: unmatched rows give NULL. */
  end(): Expr<T | null> {
    return this.#build(undefined);
  }

  #operand(result: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<unknown> {
    return result instanceof Expr ? result : param(this.type, result);
  }

  #build(otherwise: Expr<unknown> | undefined): Expr<T | null> {
    return caseExpression<T | null>(this.type, undefined, this.branches, otherwise);
  }
}

/** The subject of a simple CASE before its first branch; see `caseOf`. */
export class CaseOf<V> {
  constructor(readonly subject: Expr<V>) {}

  when(value: NonNullable<V>, result: string): SimpleCase<V, string>;
  when(value: NonNullable<V>, result: number): SimpleCase<V, number>;
  when(value: NonNullable<V>, result: boolean): SimpleCase<V, boolean>;
  when<T>(value: NonNullable<V>, result: Expr<T>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: unknown): SimpleCase<V, unknown> {
    const e = result instanceof Expr ? (result as Expr<unknown>) : param(typeOf(result), result);
    return new SimpleCase<V, unknown>(this.subject, e.type as SqlType<{}>, [[param(this.subject.type, value), e]]);
  }
}

/** `CASE subject WHEN value THEN result ... END`, a code table written inline; the first branch fixes T. */
export class SimpleCase<V, T> {
  constructor(
    readonly subject: Expr<V>,
    readonly type: SqlType<NonNullable<T>>,
    readonly branches: readonly (readonly [Expr<unknown>, Expr<unknown>])[],
  ) {}

  when(value: NonNullable<V>, result: NonNullable<T>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: Expr<NonNullable<T>>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: Expr<NonNullable<T> | null>): SimpleCase<V, T | null>;
  when(value: NonNullable<V>, result: NonNullable<T> | Expr<NonNullable<T> | null>): SimpleCase<V, T | null> {
    const e = result instanceof Expr ? result : param(this.type, result);
    return new SimpleCase<V, T | null>(this.subject, this.type, [...this.branches, [param(this.subject.type, value), e]]);
  }

  otherwise(result: NonNullable<T>): Expr<T>;
  otherwise(result: Expr<NonNullable<T>>): Expr<T>;
  otherwise(result: Expr<NonNullable<T> | null>): Expr<T | null>;
  otherwise(result: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<T | null> {
    return caseExpression<T | null>(this.type, this.subject, this.branches, result instanceof Expr ? result : param(this.type, result));
  }

  /** No ELSE: unmatched rows give NULL. */
  end(): Expr<T | null> {
    return caseExpression<T | null>(this.type, this.subject, this.branches, undefined);
  }
}

/** `CASE [subject] WHEN key THEN result ... [ELSE otherwise] END`; the keys are conditions, or values with a subject. */
function caseExpression<T>(
  type: SqlType<NonNullable<T>>,
  subject: Expr<unknown> | undefined,
  branches: readonly (readonly [Renderable, Expr<unknown>])[],
  otherwise: Expr<unknown> | undefined,
): Expr<T> {
  return expression<T>(type, (w) => {
    w.sql("CASE");
    if (subject !== undefined) w.sql(" ").render(subject);
    for (const [key, result] of branches) {
      if (key instanceof Cond && key.isEmpty) throw new Error("A CASE branch has no condition");
      w.sql(" WHEN ").render(key).sql(" THEN ").render(result);
    }
    if (otherwise !== undefined) w.sql(" ELSE ").render(otherwise);
    w.sql(" END");
  });
}

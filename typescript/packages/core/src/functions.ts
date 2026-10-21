import { FunctionDef } from "./dialect.js";
import {
  Aggregate,
  Arithmetic,
  Case,
  CaseOf,
  compare,
  condition,
  Cond,
  Expr,
  expression,
  functionCall,
  Literal,
  param,
  raw,
  renderTemplate,
  Scalar,
  WindowFunction,
  type KeepNull,
  type TemplateArg,
} from "./expr.js";
import type { Query } from "./query.js";
import { date, decimal, integer, text, timestamp, typeOf, untyped, type CastType, type SqlType, type TypeKind } from "./types.js";

/**
 * Core functions: the ones whose semantics are the same on every supported database. Generic
 * functions here take one generic operand; functions whose operands must share a type live on
 * `Expr` as instance methods.
 */

/** `a || b || ...`; dialects without the operator (MySQL) register their own rendering. */
export const CONCAT = FunctionDef.of("CONCAT", (w, args) => w.sql("(").join(args, " || ").sql(")"));

/** `LOCATE(text, search)`; PostgreSQL and MySQL register their own spelling. */
export const LOCATE = FunctionDef.of("LOCATE");

const NUMERIC: readonly TypeKind[] = ["integer", "bigint", "double", "decimal", "unknown"];

function numeric(e: Expr<unknown>, fn: string): void {
  if (!NUMERIC.includes(e.type.kind)) throw new TypeError(`${fn} needs a numeric expression, got ${e.type.name}`);
}

// ---------- values ----------

/**
 * A bound parameter, typed by the value or by the given type. Text, numbers and booleans widen to
 * `string`, `number` and `boolean`, so `val("A")` and `val("B")` have the same type.
 */
export function val(value: string): Expr<string>;
export function val(value: number): Expr<number>;
export function val(value: boolean): Expr<boolean>;
export function val<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T>;
export function val<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T> {
  return param((type ?? typeOf(value)) as SqlType<NonNullable<T>>, value);
}

/** A literal written into the SQL text; use for fixed codes, never for user input. Widens like `val`. */
export function lit(value: string): Expr<string>;
export function lit(value: number): Expr<number>;
export function lit(value: boolean): Expr<boolean>;
export function lit<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T>;
export function lit<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T> {
  if (value == null) throw new TypeError("lit() needs a value; use nullOf(type) for NULL");
  return new Literal((type ?? typeOf(value)) as SqlType<NonNullable<T>>, value);
}

export function nullOf<T>(type: SqlType<T>): Expr<T | null> {
  return raw(type as SqlType<NonNullable<T>>, "NULL");
}

// ---------- conditions ----------

/** The condition only when test holds; otherwise no condition. */
export function onlyIf(test: boolean, cond: () => Cond): Cond {
  return test ? cond() : Cond.EMPTY;
}

/** The condition built from value only when value is present. */
export function ifPresent<T>(value: T | null | undefined, cond: (value: T) => Cond): Cond {
  return value == null ? Cond.EMPTY : cond(value);
}

export function like(e: Expr<string | null>, pattern: string): Cond {
  return compare(e, "LIKE", param(e.type, pattern));
}

export function notLike(e: Expr<string | null>, pattern: string): Cond {
  return compare(e, "NOT LIKE", param(e.type, pattern));
}

export function likeIfPresent(e: Expr<string | null>, pattern: string | null | undefined): Cond {
  return pattern == null ? Cond.EMPTY : like(e, pattern);
}

/** A condition written as SQL; expressions render in place, other values become parameters. */
export function sqlCond(parts: TemplateStringsArray, ...args: TemplateArg[]): Cond {
  return condition((w) => renderTemplate(w, parts, args));
}

// ---------- text ----------

export function upper<T extends string | null>(e: Expr<T>): Expr<T> {
  return functionCall(e.type, "UPPER", [e]);
}

export function lower<T extends string | null>(e: Expr<T>): Expr<T> {
  return functionCall(e.type, "LOWER", [e]);
}

export function trim<T extends string | null>(e: Expr<T>): Expr<T> {
  return functionCall(e.type, "TRIM", [e]);
}

function textPosition(start: number): number {
  if (!Number.isInteger(start) || start < 1) throw new RangeError("Text positions are integers from 1; negative positions differ between databases");
  return start;
}

function textLength(length: number): number {
  if (!Number.isInteger(length) || length < 0) throw new RangeError("A length must be a non-negative integer");
  return length;
}

/** A text position or length: a number becomes a literal, an expression is computed, e.g. from `locate`. */
type Position = number | Expr<number> | Expr<number | null>;

function positionArg(p: Position, check: (n: number) => number): Expr<unknown> {
  return typeof p === "number" ? lit(check(p)) : p;
}

/** SUBSTRING(e, start[, length]), counting from 1. Literal positions keep the call usable as a GROUP BY key. */
export function substring<T extends string | null>(e: Expr<T>, start: Position, length?: Position): Expr<T> {
  const args: Expr<unknown>[] = [e, positionArg(start, textPosition)];
  if (length !== undefined) args.push(positionArg(length, textLength));
  return functionCall(text as SqlType<NonNullable<T>>, "SUBSTRING", args);
}

export function left<T extends string | null>(e: Expr<T>, length: Position): Expr<T> {
  return functionCall(text as SqlType<NonNullable<T>>, "LEFT", [e, positionArg(length, textLength)]);
}

export function right<T extends string | null>(e: Expr<T>, length: Position): Expr<T> {
  return functionCall(text as SqlType<NonNullable<T>>, "RIGHT", [e, positionArg(length, textLength)]);
}

/** Where search first occurs in value, counting from 1, or 0 when it does not. */
export function locate<T extends string | null>(value: Expr<T>, search: string | Expr<string> | Expr<string | null>): Expr<KeepNull<T, number>> {
  return functionCall(integer, LOCATE, [value, typeof search === "string" ? param(text, search) : search]);
}

export function replace<T extends string | null>(e: Expr<T>, search: string, replacement: string): Expr<T> {
  return functionCall(text as SqlType<NonNullable<T>>, "REPLACE", [e, param(text, search), param(text, replacement)]);
}

export function concat(...parts: Expr<string>[]): Expr<string>;
export function concat(...parts: Expr<string | null>[]): Expr<string | null>;
export function concat(...parts: Expr<string | null>[]): Expr<string | null> {
  if (parts.length < 2) throw new TypeError("concat() needs at least two operands");
  return functionCall(text, CONCAT, parts);
}

// ---------- numbers ----------

type Numeric = number | bigint | string | null;

/** The result has the first operand's type. A list sums all its terms, e.g. sixteen period columns. */
export function plus<T extends Numeric>(terms: readonly Expr<T>[]): Expr<T>;
export function plus<T extends Numeric>(first: Expr<T>, ...rest: Expr<Numeric>[]): Expr<T>;
export function plus<T extends Numeric>(first: Expr<T> | readonly Expr<T>[], ...rest: Expr<Numeric>[]): Expr<T> {
  if (!Array.isArray(first)) return arithmetic("+", first as Expr<T>, rest);
  const [head, ...tail] = first as readonly Expr<T>[];
  if (head === undefined) throw new TypeError("plus() needs at least one term");
  return arithmetic("+", head, tail);
}

/** Unary minus: `-(e)`; the parentheses keep a negative literal from turning into a comment. */
export function negate<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "negate");
  return expression(e.type, (w) => w.sql("-(").render(e).sql(")"));
}

export function times<T extends Numeric>(first: Expr<T>, ...rest: Expr<Numeric>[]): Expr<T> {
  return arithmetic("*", first, rest);
}

export function minus<T extends Numeric>(left: Expr<T>, right: Expr<Numeric>): Expr<T> {
  return arithmetic("-", left, [right]);
}

/** Decimal or double division only: integer division truncates on some databases and not on others. */
export function divide<T extends number | string | null>(left: Expr<T>, right: Expr<Numeric>): Expr<T> {
  if (left.type.kind !== "decimal" && left.type.kind !== "double") throw new TypeError(`divide() needs a decimal or double dividend, got ${left.type.name}`);
  return arithmetic("/", left, [right]);
}

function arithmetic<T>(operator: string, first: Expr<T>, rest: readonly Expr<unknown>[]): Expr<T> {
  for (const e of [first, ...rest]) numeric(e as Expr<unknown>, operator);
  return new Arithmetic(first.type, operator, [first as Expr<unknown>, ...rest]);
}

export function abs<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "ABS");
  return functionCall(e.type, "ABS", [e]);
}

export function floor<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "FLOOR");
  return functionCall(e.type, "FLOOR", [e]);
}

export function ceil<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "CEIL");
  return functionCall(e.type, "CEIL", [e]);
}

export function round<T extends Numeric>(e: Expr<T>, scale: number): Expr<T> {
  numeric(e as Expr<unknown>, "ROUND");
  return functionCall(e.type, "ROUND", [e, lit(scale)]);
}

// ---------- aggregates ----------

export function count(e?: Expr<unknown>): Aggregate<number> {
  return new Aggregate(integer, "COUNT", false, e === undefined ? [] : [e]);
}

export function countDistinct(e: Expr<unknown>): Aggregate<number> {
  return new Aggregate(integer, "COUNT", true, [e]);
}

export function sum<T extends Numeric>(e: Expr<T>): Aggregate<T | null> {
  numeric(e as Expr<unknown>, "SUM");
  return new Aggregate(e.type, "SUM", false, [e as Expr<unknown>]);
}

/** The average as an exact decimal. */
export function avg(e: Expr<Numeric>): Aggregate<string | null> {
  numeric(e as Expr<unknown>, "AVG");
  return new Aggregate(decimal, "AVG", false, [e as Expr<unknown>]);
}

export function min<T>(e: Expr<T>): Aggregate<T | null> {
  return new Aggregate(e.type, "MIN", false, [e as Expr<unknown>]);
}

export function max<T>(e: Expr<T>): Aggregate<T | null> {
  return new Aggregate(e.type, "MAX", false, [e as Expr<unknown>]);
}

/** 1 on the subtotal rows of `groupByRollup` where the key is rolled up, 0 elsewhere. */
export function grouping(key: Expr<unknown>): Expr<number> {
  return functionCall(integer, "GROUPING", [key]);
}

// ---------- window functions ----------

export function rowNumber(): WindowFunction<number> {
  return new WindowFunction(integer, "ROW_NUMBER", []);
}

export function rank(): WindowFunction<number> {
  return new WindowFunction(integer, "RANK", []);
}

export function denseRank(): WindowFunction<number> {
  return new WindowFunction(integer, "DENSE_RANK", []);
}

export function lag<T>(e: Expr<T>, offset = 1): WindowFunction<T | null> {
  return new WindowFunction(e.type, "LAG", [e, lit(offset)]);
}

export function lead<T>(e: Expr<T>, offset = 1): WindowFunction<T | null> {
  return new WindowFunction(e.type, "LEAD", [e, lit(offset)]);
}

// ---------- CASE, CAST, SQL fragments, functions, subqueries ----------

export function caseWhen(condition: Cond, result: string): Case<string>;
export function caseWhen(condition: Cond, result: number): Case<number>;
export function caseWhen(condition: Cond, result: boolean): Case<boolean>;
export function caseWhen<T>(condition: Cond, result: Expr<T>): Case<T>;
export function caseWhen(condition: Cond, result: unknown): Case<unknown> {
  const e = result instanceof Expr ? (result as Expr<unknown>) : val(result as {});
  return new Case(e.type, [[condition, e]]);
}

/** `CASE subject WHEN value THEN result ... END`: `caseOf(code).when("1", "own").when("2", "supplier").otherwise("other")`. */
export function caseOf<V>(subject: Expr<V>): CaseOf<V> {
  return new CaseOf(subject);
}

export function cast<T, U>(e: Expr<T>, target: CastType<U>): Expr<KeepNull<T, U>> {
  return e.cast(target);
}

/**
 * A typed SQL fragment: `sql(integer)\`DAYS_BETWEEN(${a}, ${b})\``. Expressions render in place,
 * other values become parameters. Without a type the value is read as the driver returns it.
 */
export function sql<T>(type: SqlType<T>): (parts: TemplateStringsArray, ...args: TemplateArg[]) => Expr<T>;
export function sql(parts: TemplateStringsArray, ...args: TemplateArg[]): Expr<unknown>;
export function sql(first: SqlType<unknown> | TemplateStringsArray, ...rest: TemplateArg[]): unknown {
  const build = (type: SqlType<unknown>, parts: TemplateStringsArray, args: readonly TemplateArg[]) =>
    expression(type as SqlType<{}>, (w) => renderTemplate(w, parts, args));
  if (Array.isArray(first)) return build(untyped, first as TemplateStringsArray, rest);
  return (parts: TemplateStringsArray, ...args: TemplateArg[]) => build(first as SqlType<unknown>, parts, args);
}

/** A call of a function definition, the way built-in and user functions are made alike. */
export function call<T>(fn: FunctionDef, type: SqlType<NonNullable<T>>, ...args: Expr<unknown>[]): Expr<T> {
  return functionCall(type, fn, args);
}

/** A one-column query used as a value; NULL when it finds no row. */
export function scalar<T>(subquery: Query<T>): Expr<T | null> {
  return new Scalar(subquery);
}

export function currentDate(): Expr<string> {
  return raw(date, "CURRENT_DATE");
}

export function currentTimestamp(): Expr<string> {
  return raw(timestamp, "CURRENT_TIMESTAMP");
}

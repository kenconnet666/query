import { call, condition, date, Expr, expression, FunctionDef, integer, lit, param, text, toOrder, untyped, type Clause, type Cond, type KeepNull, type OrderItem, type SqlType, type SqlWriter } from "@zerodep-dev/query-core";
import { HanaDialect } from "./dialect.js";

/** HANA-only functions and clauses; they refuse to render on another dialect. */

const DAYS_BETWEEN = FunctionDef.only(HanaDialect, "DAYS_BETWEEN");
const ADD_DAYS = FunctionDef.only(HanaDialect, "ADD_DAYS");
const ADD_MONTHS = FunctionDef.only(HanaDialect, "ADD_MONTHS");
const ADD_YEARS = FunctionDef.only(HanaDialect, "ADD_YEARS");
const TO_VARCHAR = FunctionDef.only(HanaDialect, "TO_VARCHAR");
const TO_DATE = FunctionDef.only(HanaDialect, "TO_DATE");
const TO_NVARCHAR = FunctionDef.only(HanaDialect, "TO_NVARCHAR");
const LENGTH = FunctionDef.only(HanaDialect, "LENGTH");
const TO_INTEGER = FunctionDef.only(HanaDialect, "TO_INTEGER");
const STRING_AGG = FunctionDef.only(HanaDialect, "STRING_AGG", (w, args) => {
  w.sql("STRING_AGG(").render(args[0]!).sql(", ").render(args[1]!);
  if (args.length > 2) w.sql(" ORDER BY ").join(args.slice(2), ", ");
  w.sql(")");
});

/** Days from `from` to `to`. */
export function daysBetween(from: Expr<string | null>, to: Expr<string | null>): Expr<number | null> {
  return call(DAYS_BETWEEN, integer, from, to);
}

export function addDays<T extends string | null>(day: Expr<T>, days: number): Expr<T> {
  return call(ADD_DAYS, day.type, day, lit(days));
}

export function addMonths<T extends string | null>(day: Expr<T>, months: number): Expr<T> {
  return call(ADD_MONTHS, day.type, day, lit(months));
}

export function addYears<T extends string | null>(day: Expr<T>, years: number): Expr<T> {
  return call(ADD_YEARS, day.type, day, lit(years));
}

/** TO_VARCHAR(value, format), e.g. `toVarchar(day, "YYYYMMDD")`. */
export function toVarchar<T>(value: Expr<T>, format: string): Expr<KeepNull<T, string>> {
  return call(TO_VARCHAR, text, value as Expr<unknown>, lit(format));
}

export function toDate<T extends string | null>(value: Expr<T>, format: string): Expr<KeepNull<T, string>> {
  return call(TO_DATE, date, value, lit(format));
}

/** TO_NVARCHAR(value[, format]), the Unicode form of {@link toVarchar}. */
export function toNvarchar<T>(value: Expr<T>, format?: string): Expr<KeepNull<T, string>> {
  return format === undefined ? call(TO_NVARCHAR, text, value as Expr<unknown>) : call(TO_NVARCHAR, text, value as Expr<unknown>, lit(format));
}

/** `e LIKE_REGEXPR pattern`, HANA's regular-expression predicate. */
export function likeRegexpr(e: Expr<string | null>, pattern: string): Cond {
  const p = param(text, pattern);
  return condition((w) => {
    if (!(w.dialect instanceof HanaDialect)) throw new Error(`LIKE_REGEXPR is a HanaDialect predicate; this database uses ${w.dialect.name}`);
    w.render(e).sql(" LIKE_REGEXPR ").render(p);
  });
}

/** HANA's LENGTH counts UTF-16 code units, so a character outside the BMP counts as 2. */
export function length<T extends string | null>(value: Expr<T>): Expr<KeepNull<T, number>> {
  return call(LENGTH, integer, value);
}

/** TO_INTEGER(value), also written TO_INT: text or a number as an integer; text that is no number fails the query. */
export function toInteger<T>(value: Expr<T>): Expr<KeepNull<T, number>> {
  return call(TO_INTEGER, integer, value);
}

/** STRING_AGG(value, separator ORDER BY ...). */
export function stringAgg(value: Expr<string | null>, separator: string, ...order: OrderItem[]): Expr<string | null> {
  const args = [value, lit(separator), ...order.map(toOrder)];
  return expression(text, (w) => STRING_AGG.render(w, args));
}

/** WITH HINT(...) at the end of the statement. */
export function hint(...hints: string[]): Clause {
  if (hints.length === 0) throw new TypeError("hint() needs at least one hint");
  const sql = `WITH HINT(${hints.join(", ")})`;
  return { position: "end", dialect: HanaDialect, render: (w) => void w.sql(sql) };
}

const CUBE = FunctionDef.only(HanaDialect, "CUBE");
const GROUPING_SETS = FunctionDef.only(HanaDialect, "GROUPING SETS", (w, args) => void w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

/** CUBE(keys) as a GROUP BY key: subtotals for every combination of the keys. */
export function cube(...keys: Expr<unknown>[]): Expr<unknown> {
  return call(CUBE, untyped as SqlType<{}>, ...keys);
}

/** GROUPING SETS ((a, b), (a), ()) as a GROUP BY key: exactly the listed groupings; an empty list is the grand total. */
export function groupingSets(...sets: (readonly Expr<unknown>[])[]): Expr<unknown> {
  const args = sets.map((set) => ({ render: (w: SqlWriter) => void w.sql("(").join(set, ", ").sql(")") }));
  return expression(untyped as SqlType<{}>, (w) => GROUPING_SETS.render(w, args));
}

const GREATEST = FunctionDef.only(HanaDialect, "GREATEST");
const LEAST = FunctionDef.only(HanaDialect, "LEAST");

function extremeArgs<T>(name: string, first: Expr<T>, rest: readonly (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<unknown>[] {
  if (rest.length === 0) throw new TypeError(`${name} needs at least two arguments`);
  return [first, ...rest.map((v) => (v instanceof Expr ? v : param(first.type, v as NonNullable<T>)))];
}

/** GREATEST(a, b, ...): NULL as soon as one argument is NULL. */
export function greatest<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(GREATEST, first.type, ...extremeArgs("GREATEST", first, rest));
}

/** LEAST(a, b, ...): NULL as soon as one argument is NULL. */
export function least<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): Expr<T> {
  return call(LEAST, first.type, ...extremeArgs("LEAST", first, rest));
}

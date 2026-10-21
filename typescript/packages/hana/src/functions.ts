import { call, date, expression, FunctionDef, integer, lit, text, toOrder, type Clause, type Expr, type KeepNull, type OrderItem } from "@query/core";
import { HanaDialect } from "./dialect.js";

/** HANA-only functions and clauses; they refuse to render on another dialect. */

const DAYS_BETWEEN = FunctionDef.only(HanaDialect, "DAYS_BETWEEN");
const ADD_DAYS = FunctionDef.only(HanaDialect, "ADD_DAYS");
const ADD_MONTHS = FunctionDef.only(HanaDialect, "ADD_MONTHS");
const ADD_YEARS = FunctionDef.only(HanaDialect, "ADD_YEARS");
const TO_VARCHAR = FunctionDef.only(HanaDialect, "TO_VARCHAR");
const TO_DATE = FunctionDef.only(HanaDialect, "TO_DATE");
const LENGTH = FunctionDef.only(HanaDialect, "LENGTH");
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

/** HANA's LENGTH counts UTF-16 code units, so a character outside the BMP counts as 2. */
export function length<T extends string | null>(value: Expr<T>): Expr<KeepNull<T, number>> {
  return call(LENGTH, integer, value);
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

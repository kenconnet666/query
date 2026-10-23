import { call, Expr, FunctionDef, lit, param, text, type Clause, type KeepNull } from "@zerodep-dev/query-core";
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

import { call, cast, CastType, Cond, condition, date, double, Expr, expression, FunctionDef, integer, lit, param, skips, text, untyped, WindowFunction, type Clause, type KeepNull, type SqlType, type SqlWriter } from "@zerodep-dev/query-core";
import { HanaDialect } from "./dialect.js";

/** HANA 专有的函数和子句；在其他方言上拒绝渲染。 */

const TO_VARCHAR = FunctionDef.only(HanaDialect, "TO_VARCHAR");
const TO_DATE = FunctionDef.only(HanaDialect, "TO_DATE");
const TO_NVARCHAR = FunctionDef.only(HanaDialect, "TO_NVARCHAR");
const LENGTH = FunctionDef.only(HanaDialect, "LENGTH");
const TO_INTEGER = FunctionDef.only(HanaDialect, "TO_INTEGER");

/** TO_VARCHAR(value, format)，例如 `toVarchar(day, "YYYYMMDD")`。 */
export function toVarchar<T>(value: Expr<T>, format: string): Expr<KeepNull<T, string>> {
  return call(TO_VARCHAR, text, value as Expr<unknown>, lit(format));
}

/** TO_DATE(value, format)：按格式把文本解析成日期。 */
export function toDate<T extends string | null>(value: Expr<T>, format: string): Expr<KeepNull<T, string>> {
  return call(TO_DATE, date, value, lit(format));
}

/** TO_NVARCHAR(value[, format])，{@link toVarchar} 的 Unicode 形式。 */
export function toNvarchar<T>(value: Expr<T>, format?: string): Expr<KeepNull<T, string>> {
  return format === undefined ? call(TO_NVARCHAR, text, value as Expr<unknown>) : call(TO_NVARCHAR, text, value as Expr<unknown>, lit(format));
}

/** `e LIKE_REGEXPR pattern`，HANA 的正则表达式谓词。skipEmpty 下模式为空时没有条件。 */
export function likeRegexpr(e: Expr<string | null>, pattern: string | null | undefined): Cond {
  if (skips(pattern)) return Cond.EMPTY;
  const p = param(text, pattern as string);
  return condition((w) => {
    if (!(w.dialect instanceof HanaDialect)) throw new Error(`LIKE_REGEXPR is a HanaDialect predicate; this database uses ${w.dialect.name}`);
    w.render(e).sql(" LIKE_REGEXPR ").render(p);
  });
}

/** HANA 的 LENGTH 按 UTF-16 码元计数，所以 BMP 之外的字符算 2。 */
export function length<T extends string | null>(value: Expr<T>): Expr<KeepNull<T, number>> {
  return call(LENGTH, integer, value);
}

/** TO_INTEGER(value)，也写作 TO_INT：把文本或数字转成整数；不是数字的文本会使查询失败。 */
export function toInteger<T>(value: Expr<T>): Expr<KeepNull<T, number>> {
  return call(TO_INTEGER, integer, value);
}


/** 语句末尾的 WITH HINT(...)。 */
export function hint(...hints: string[]): Clause {
  if (hints.length === 0) throw new TypeError("hint() needs at least one hint");
  const sql = `WITH HINT(${hints.join(", ")})`;
  return { position: "end", dialect: HanaDialect, render: (w) => void w.sql(sql) };
}

/** CUBE 与 GROUPING SETS 只能出现在 GROUP BY 里。 */
const CUBE = FunctionDef.only(HanaDialect, "CUBE");
const GROUPING_SETS = FunctionDef.only(HanaDialect, "GROUPING SETS", (w, args) => void w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

/** CUBE(keys) 作为 GROUP BY 的键：对键的每种组合输出小计。 */
export function cube(...keys: Expr<unknown>[]): Expr<unknown> {
  return call(CUBE, untyped as SqlType<{}>, ...keys);
}

/** GROUPING SETS ((a, b), (a), ()) 作为 GROUP BY 的键：只输出列出的分组；空列表表示总计。 */
export function groupingSets(...sets: (readonly Expr<unknown>[])[]): Expr<unknown> {
  const args = sets.map((set) => ({ render: (w: SqlWriter) => void w.sql("(").join(set, ", ").sql(")") }));
  return expression(untyped as SqlType<{}>, (w) => GROUPING_SETS.render(w, args));
}

const GREATEST = FunctionDef.only(HanaDialect, "GREATEST");
const LEAST = FunctionDef.only(HanaDialect, "LEAST");

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

// ---------- 统计 ----------

const MEDIAN = FunctionDef.only(HanaDialect, "MEDIAN");
const CORR = FunctionDef.only(HanaDialect, "CORR");
const PERCENTILE_CONT = FunctionDef.only(HanaDialect, "PERCENTILE_CONT", (w, args) => void w.sql("PERCENTILE_CONT(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")"));
const PERCENTILE_DISC = FunctionDef.only(HanaDialect, "PERCENTILE_DISC", (w, args) => void w.sql("PERCENTILE_DISC(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")"));

/** 中位数 `MEDIAN(e)`，偶数行时取中间两个值的平均；参数先转成 DOUBLE。可以作聚合。 */
export function median(e: Expr<unknown>): Expr<number | null> {
  return call(MEDIAN, double, cast(e, CastType.double()));
}

/**
 * 连续百分位数 `PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY e)`，再加 `.over((w) => w.partitionBy(...))`：
 * HANA 只把它当窗口函数（PostgreSQL 只当聚合），分组的百分位数用 `partitionBy` 加 `distinct()`。p 取 0 到 1。
 */
export function percentileCont(p: number, e: Expr<unknown>): WindowFunction<number | null> {
  return new WindowFunction(double, PERCENTILE_CONT, [lit(fraction(p)), cast(e, CastType.double())]);
}

/** 离散百分位数 `PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY e)`，再加 `.over(...)`；规则同 {@link percentileCont}。 */
export function percentileDisc<T>(p: number, e: Expr<T>): WindowFunction<T | null> {
  return new WindowFunction(e.type, PERCENTILE_DISC, [lit(fraction(p)), e]);
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

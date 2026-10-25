import type { CalendarDate, CalendarDateTime } from "@internationalized/date";
import type { Decimal } from "decimal.js";
import { CURRENT_DATE, CURRENT_TIMESTAMP } from "./builtins.js";
import { addCondition, collect, negated, rowInQuery } from "./cond.js";
import type { FunctionDef } from "./dialect.js";
import { Aggregate, expression, functionCall, Literal, param, quantified, raw, Scalar, type Expr, type TemplateArg } from "./expr.js";
import type { Query } from "../query/query.js";
import { SqlCond, SqlExpr, type SqlExprOf } from "./template.js";
import { boolean, date, double, integer, timestamp, typeOf, untyped, type SqlType } from "../schema/types.js";
import type { ExprBigint, ExprDate, ExprDecimal, ExprNumber, ExprOf, ExprText, ExprTimestamp } from "./typed.js";
import { WindowFunction } from "./window.js";

/**
 * 没有主操作数的函数：值、COUNT(*)、窗口函数、子查询、CASE 的入口（见 case.ts）、原生 SQL。
 * 有主操作数的运算是表达式的实例方法（`l.unit.upper()`、`l.quantity.sum()`），条件块是 cond.ts 的 `any`、`all`、`not`。
 */

// ---------- 值 ----------

/**
 * 绑定参数，类型取自值或给定的类型。文本、数字和布尔会放宽为 `string`、`number`、`boolean`，
 * 所以 `val("A")` 和 `val("B")` 的类型相同。
 */
export function val(value: string): ExprText<string>;
export function val(value: number): ExprNumber<number>;
export function val(value: boolean): Expr<boolean>;
export function val(value: bigint): ExprBigint<bigint>;
export function val(value: Decimal): ExprDecimal<Decimal>;
export function val(value: CalendarDateTime): ExprTimestamp<CalendarDateTime>;
export function val(value: CalendarDate): ExprDate<CalendarDate>;
export function val<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): ExprOf<T>;
export function val<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): ExprOf<T> {
  return param((type ?? typeOf(value)) as SqlType<NonNullable<T>>, value) as never;
}

/** 直接写进 SQL 文本的字面量；只用于固定代码，绝不用于用户输入。类型放宽方式同 {@link val}。 */
export function lit(value: string): ExprText<string>;
export function lit(value: number): ExprNumber<number>;
export function lit(value: boolean): Expr<boolean>;
export function lit(value: bigint): ExprBigint<bigint>;
export function lit(value: Decimal): ExprDecimal<Decimal>;
export function lit(value: CalendarDateTime): ExprTimestamp<CalendarDateTime>;
export function lit(value: CalendarDate): ExprDate<CalendarDate>;
export function lit<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): ExprOf<T>;
export function lit<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): ExprOf<T> {
  if (value == null) throw new TypeError("lit() needs a value; use nullOf(type) for NULL");
  return new Literal((type ?? typeOf(value)) as SqlType<NonNullable<T>>, value) as never;
}

/** 指定类型的 NULL。 */
export function nullOf<T>(type: SqlType<T>): ExprOf<T | null> {
  return raw<T | null>(type as SqlType<NonNullable<T>>, "NULL");
}

// ---------- 条件 ----------

/**
 * 块里的条件作为布尔列：`CASE WHEN c THEN TRUE WHEN NOT (c) THEN FALSE END`，条件未知时为 NULL。
 * HANA 不能直接把条件写在 SELECT 列表里，所以各库都这样写：`select((l) => ({ big: bool(() => l.quantity.gt(x)) }))`。
 */
export function bool(condition: () => void): Expr<boolean | null> {
  const c = collect(condition);
  if (c.isEmpty) throw new Error("bool(...) needs a condition");
  return expression<boolean | null>(boolean, (w) => void w.sql("CASE WHEN ").render(c).sql(" THEN TRUE WHEN ").render(negated(c)).sql(" THEN FALSE END"));
}

/** 用 SQL 模板写成的条件，写进当前的条件块；表达式原位渲染，其他值成为绑定参数。`.on(方言类)\`...\`` 给某个方言另一种写法。 */
export function sqlCond(parts: TemplateStringsArray, ...args: TemplateArg[]): SqlCond {
  const c = new SqlCond([{ dialect: undefined, parts, args }]);
  addCondition(c);
  return c;
}

/** 多列 IN 子查询的左侧，由 {@link keys} 得到。 */
export class KeyTuple {
  constructor(readonly items: readonly Expr<unknown>[]) {}

  /** `(a, b) IN (子查询)`，子查询的列数必须相同。 */
  in(subquery: Query<unknown>): void {
    addCondition(rowInQuery(this.items, subquery, false));
  }

  /** `(a, b) NOT IN (子查询)`；子查询里有 NULL 时，与单列 NOT IN 一样整个条件为未知。 */
  notIn(subquery: Query<unknown>): void {
    addCondition(rowInQuery(this.items, subquery, true));
  }
}

/** 多个键组成一行，用于多列 IN 子查询：`keys(l.delivery, l.material).in(子查询)`。 */
export function keys(...items: Expr<unknown>[]): KeyTuple {
  return new KeyTuple(items);
}

// ---------- 聚合与窗口函数 ----------

/** COUNT(*)：行数；某一列的非空个数是 `e.count()`。 */
export function count(): ExprNumber<number> {
  return new Aggregate<number>(integer, "COUNT", false, []) as never;
}

/** 在 `groupByRollup` 的小计行上，该键被汇总时为 1，其余为 0。 */
export function grouping(key: Expr<unknown>): ExprNumber<number> {
  return functionCall<number>(integer, "GROUPING", [key]);
}

/** ROW_NUMBER：分区内的连续行号。 */
export function rowNumber(): WindowFunction<number> {
  return new WindowFunction(integer, "ROW_NUMBER", []);
}

/** RANK：并列时跳号的排名。 */
export function rank(): WindowFunction<number> {
  return new WindowFunction(integer, "RANK", []);
}

/** DENSE_RANK：并列时不跳号的排名。 */
export function denseRank(): WindowFunction<number> {
  return new WindowFunction(integer, "DENSE_RANK", []);
}

/** LAG：往前第 `offset` 行的值，越界为 NULL；结果与 e 同一类（`lag(l.quantity)` 仍是小数）。 */
export function lag<T>(e: Expr<T>, offset?: number): WindowFunction<T | null>;
/** LAG：往前第 `offset` 行的值，越界时取 fallback。 */
export function lag<T>(e: Expr<T>, offset: number, fallback: NonNullable<T>): WindowFunction<T>;
export function lag<T>(e: Expr<T>, offset = 1, fallback?: NonNullable<T>): WindowFunction<T | null> {
  return new WindowFunction(e.type, "LAG", fallback === undefined ? [e, lit(offset)] : [e, lit(offset), param(e.type, fallback)]);
}

/** LEAD：往后第 `offset` 行的值，越界为 NULL。 */
export function lead<T>(e: Expr<T>, offset?: number): WindowFunction<T | null>;
/** LEAD：往后第 `offset` 行的值，越界时取 fallback。 */
export function lead<T>(e: Expr<T>, offset: number, fallback: NonNullable<T>): WindowFunction<T>;
export function lead<T>(e: Expr<T>, offset = 1, fallback?: NonNullable<T>): WindowFunction<T | null> {
  return new WindowFunction(e.type, "LEAD", fallback === undefined ? [e, lit(offset)] : [e, lit(offset), param(e.type, fallback)]);
}

/** FIRST_VALUE：窗口里第一行的值。 */
export function firstValue<T>(e: Expr<T>): WindowFunction<T> {
  return new WindowFunction(e.type, "FIRST_VALUE", [e]);
}

/**
 * LAST_VALUE：窗口里最后一行的值。有 ORDER BY 时默认窗口只到当前行，要取整个分区的最后一行需写
 * `w.rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())`。
 */
export function lastValue<T>(e: Expr<T>): WindowFunction<T> {
  return new WindowFunction(e.type, "LAST_VALUE", [e]);
}

/** PERCENT_RANK：相对排名 `(rank - 1) / (行数 - 1)`，取值 0 到 1。 */
export function percentRank(): WindowFunction<number> {
  return new WindowFunction(double, "PERCENT_RANK", []);
}

/** CUME_DIST：不大于当前行的行数占分区行数的比例，取值大于 0、不超过 1。 */
export function cumeDist(): WindowFunction<number> {
  return new WindowFunction(double, "CUME_DIST", []);
}

/**
 * NTH_VALUE：窗口里第 n 行（1 起）的值，没有时为 NULL。与 {@link lastValue} 一样，有 ORDER BY 时默认窗口只到当前行，
 * 要在整个分区里取需写 `w.rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())`。
 */
export function nthValue<T>(e: Expr<T>, n: number): WindowFunction<T | null> {
  if (!Number.isInteger(n) || n < 1) throw new RangeError("nthValue counts rows from 1");
  return new WindowFunction(e.type, "NTH_VALUE", [e, lit(n)]);
}

/** NTILE：按窗口的顺序把分区分成 buckets 组，给出组号（1 起），例如四分位。 */
export function ntile(buckets: number): WindowFunction<number> {
  if (!Number.isInteger(buckets) || buckets < 1) throw new RangeError("ntile needs a whole number of buckets, at least 1");
  return new WindowFunction(integer, "NTILE", [lit(buckets)]);
}

// ---------- SQL 片段、函数、子查询 ----------

/**
 * 带类型的 SQL 片段：`sql(integer)\`DAYS_BETWEEN(${a}, ${b})\``，结果按类型给出接口，可以接着点。表达式原位渲染，
 * 其他值成为绑定参数。不指定类型时，值按驱动返回的样子读取。
 */
export function sql<T>(type: SqlType<T>): (parts: TemplateStringsArray, ...args: TemplateArg[]) => SqlExprOf<T>;
export function sql(parts: TemplateStringsArray, ...args: TemplateArg[]): SqlExprOf<unknown>;
export function sql(first: SqlType<unknown> | TemplateStringsArray, ...rest: TemplateArg[]): unknown {
  const build = (type: SqlType<unknown>, parts: TemplateStringsArray, args: readonly TemplateArg[]) => new SqlExpr(type as SqlType<{}>, [{ dialect: undefined, parts, args }]);
  if (Array.isArray(first)) return build(untyped, first as TemplateStringsArray, rest);
  return (parts: TemplateStringsArray, ...args: TemplateArg[]) => build(first as SqlType<unknown>, parts, args);
}

/** 调用一个函数定义；内置函数和用户自定义函数用同一种方式构造。 */
export function call<T>(fn: FunctionDef, type: SqlType<NonNullable<T>>, ...args: Expr<unknown>[]): ExprOf<T> {
  return functionCall<T>(type, fn, args);
}

/** 只有一列的查询当作一个值使用；没有行时为 NULL。 */
export function scalar<T>(subquery: Query<T>): ExprOf<T | null> {
  return new Scalar(subquery) as never;
}

/** 与子查询的任一行比较：`x.eq(anyOf(q))` 写成 `x = ANY (子查询)`；子查询没有行时比较为假。 */
export function anyOf<T>(subquery: Query<T>): ExprOf<T> {
  return quantified("ANY", subquery);
}

/** 与子查询的每一行比较：`x.gt(allOf(q))` 写成 `x > ALL (子查询)`；子查询没有行时比较为真。 */
export function allOf<T>(subquery: Query<T>): ExprOf<T> {
  return quantified("ALL", subquery);
}

/** CURRENT_DATE：数据库当前日期。 */
export function currentDate(): ExprDate<CalendarDate> {
  return functionCall<CalendarDate>(date, CURRENT_DATE, []);
}

/** CURRENT_TIMESTAMP：数据库当前时间戳。 */
export function currentTimestamp(): ExprTimestamp<CalendarDateTime> {
  return functionCall<CalendarDateTime>(timestamp, CURRENT_TIMESTAMP, []);
}

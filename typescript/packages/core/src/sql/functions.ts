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
  skips,
  Scalar,
  toOrder,
  WindowFunction,
  type KeepNull,
  type OrderItem,
  type TemplateArg,
} from "./expr.js";
import type { Query } from "../query/query.js";
import { SqlCond, SqlExpr } from "./template.js";
import { date, decimal, integer, text, timestamp, typeOf, untyped, type CastType, type SqlType, type TypeKind } from "../schema/types.js";

/**
 * 核心函数：在所有受支持的数据库上语义一致的函数。这里的泛型函数只接受一个泛型操作数；
 * 要求操作数同类型的函数写在 `Expr` 上，作为实例方法。
 */

/** `a || b || ...`；没有该运算符的方言（MySQL）注册自己的渲染。 */
export const CONCAT = FunctionDef.of("CONCAT", (w, args) => w.sql("(").join(args, " || ").sql(")"));

/** `LOCATE(text, search)`；PostgreSQL 和 MySQL 注册自己的写法。 */
export const LOCATE = FunctionDef.of("LOCATE");

/** `YEAR(d)`；PostgreSQL 注册 EXTRACT 写法，下面的 MONTH、DAYOFMONTH 相同。 */
export const YEAR = FunctionDef.of("YEAR");

/** `MONTH(d)`。 */
export const MONTH = FunctionDef.of("MONTH");

/** `DAYOFMONTH(d)`。 */
export const DAY = FunctionDef.of("DAYOFMONTH");

/** `ADD_DAYS(d, n)`；PostgreSQL、MySQL 注册自己的写法，下面的 ADD_MONTHS、ADD_YEARS 相同。 */
export const ADD_DAYS = FunctionDef.of("ADD_DAYS");

/** `ADD_MONTHS(d, n)`。 */
export const ADD_MONTHS = FunctionDef.of("ADD_MONTHS");

/** `ADD_YEARS(d, n)`。 */
export const ADD_YEARS = FunctionDef.of("ADD_YEARS");

/** `DAYS_BETWEEN(from, to)`；PostgreSQL 写成相减，MySQL 写成 DATEDIFF(to, from)。 */
export const DAYS_BETWEEN = FunctionDef.of("DAYS_BETWEEN");

/** `SECONDS_BETWEEN(from, to)`；PostgreSQL 写成 EXTRACT(EPOCH ...)，MySQL 写成 TIMESTAMPDIFF(SECOND, from, to)。 */
export const SECONDS_BETWEEN = FunctionDef.of("SECONDS_BETWEEN");

/** `STRING_AGG(value, separator ORDER BY ...)`，参数依次是值、分隔符和排序项；MySQL 写成 GROUP_CONCAT。 */
export const STRING_AGG = FunctionDef.of("STRING_AGG", (w, args) => {
  w.sql("STRING_AGG(").render(args[0]!).sql(", ").render(args[1]!);
  if (args.length > 2) w.sql(" ORDER BY ").join(args.slice(2), ", ");
  w.sql(")");
});

/** 可以参与算术运算的类型；`unknown` 放行原生 SQL 片段。 */
const NUMERIC: readonly TypeKind[] = ["integer", "bigint", "double", "decimal", "unknown"];

/** 要求表达式是数值类型，否则抛错并指出函数名。 */
function numeric(e: Expr<unknown>, fn: string): void {
  if (!NUMERIC.includes(e.type.kind)) throw new TypeError(`${fn} needs a numeric expression, got ${e.type.name}`);
}

/** 可以取日期部分的类型；`unknown` 放行原生 SQL 片段。 */
const TEMPORAL: readonly TypeKind[] = ["date", "timestamp", "unknown"];

/** 日期加减和相隔天数只接受日期：时间戳在 PostgreSQL 上加整数会失败。 */
const DATE_ONLY: readonly TypeKind[] = ["date", "unknown"];

/** 相隔秒数只接受时间戳。 */
const TIMESTAMP_ONLY: readonly TypeKind[] = ["timestamp", "unknown"];

/** 参数不是日期或时间戳时抛出 TypeError；TS 里日期是字符串，所以只能在运行时检查列的类型。 */
function temporal(e: Expr<unknown>, fn: string, kinds: readonly TypeKind[] = TEMPORAL): void {
  if (!kinds.includes(e.type.kind)) throw new TypeError(`${fn} needs a ${kinds.slice(0, -1).join(" or ")} expression, got ${e.type.name}`);
}

// ---------- 值 ----------

/**
 * 绑定参数，类型取自值或给定的类型。文本、数字和布尔会放宽为 `string`、`number`、`boolean`，
 * 所以 `val("A")` 和 `val("B")` 的类型相同。
 */
export function val(value: string): Expr<string>;
export function val(value: number): Expr<number>;
export function val(value: boolean): Expr<boolean>;
export function val<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T>;
export function val<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T> {
  return param((type ?? typeOf(value)) as SqlType<NonNullable<T>>, value);
}

/** 直接写进 SQL 文本的字面量；只用于固定代码，绝不用于用户输入。类型放宽方式同 `val`。 */
export function lit(value: string): Expr<string>;
export function lit(value: number): Expr<number>;
export function lit(value: boolean): Expr<boolean>;
export function lit<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T>;
export function lit<T>(value: NonNullable<T>, type?: SqlType<NonNullable<T>>): Expr<T> {
  if (value == null) throw new TypeError("lit() needs a value; use nullOf(type) for NULL");
  return new Literal((type ?? typeOf(value)) as SqlType<NonNullable<T>>, value);
}

/** 指定类型的 NULL。 */
export function nullOf<T>(type: SqlType<T>): Expr<T | null> {
  return raw(type as SqlType<NonNullable<T>>, "NULL");
}

// ---------- 条件 ----------

/** test 成立时才加这个条件，否则没有条件。 */
export function onlyIf(test: boolean, cond: () => Cond): Cond {
  return test ? cond() : Cond.EMPTY;
}

/** value 有值时用它构造条件，否则没有条件。 */
export function ifPresent<T>(value: T | null | undefined, cond: (value: T) => Cond): Cond {
  return value == null ? Cond.EMPTY : cond(value);
}

/** LIKE；模式原样使用，% 和 _ 是通配符。skipEmpty 下模式为空时没有条件。 */
export function like(e: Expr<string | null>, pattern: string | null | undefined): Cond {
  if (skips(pattern)) return Cond.EMPTY;
  return compare(e, "LIKE", param(e.type, pattern as string));
}

/** NOT LIKE；skipEmpty 下模式为空时没有条件。 */
export function notLike(e: Expr<string | null>, pattern: string | null | undefined): Cond {
  if (skips(pattern)) return Cond.EMPTY;
  return compare(e, "NOT LIKE", param(e.type, pattern as string));
}

/** 文本包含 text：`LIKE '%text%'`，text 里的 % 和 _ 按普通字符匹配。skipEmpty 下 text 为空时没有条件。 */
export function contains(e: Expr<string | null>, text: string | null | undefined): Cond {
  return likeLiteral(e, "%", text, "%");
}

/** 文本以 text 开头，规则同 {@link contains}。 */
export function startsWith(e: Expr<string | null>, text: string | null | undefined): Cond {
  return likeLiteral(e, "", text, "%");
}

/** 文本以 text 结尾，规则同 {@link contains}。 */
export function endsWith(e: Expr<string | null>, text: string | null | undefined): Cond {
  return likeLiteral(e, "%", text, "");
}

/** prefix + 转义后的 text + suffix 作为 LIKE 模式；用 ! 作转义符，三个库的写法相同。 */
function likeLiteral(e: Expr<string | null>, prefix: string, text: string | null | undefined, suffix: string): Cond {
  if (skips(text)) return Cond.EMPTY;
  param(e.type, text as string); // text 为 null 时与其他比较一样报错
  const pattern = prefix + (text as string).replaceAll("!", "!!").replaceAll("%", "!%").replaceAll("_", "!_") + suffix;
  return condition((w) => w.render(e).sql(" LIKE ").render(param(e.type, pattern)).sql(" ESCAPE '!'"));
}

/** 用 SQL 模板写成的条件；表达式原位渲染，其他值成为绑定参数。 */
export function sqlCond(parts: TemplateStringsArray, ...args: TemplateArg[]): SqlCond {
  return new SqlCond([{ dialect: undefined, parts, args }]);
}

// ---------- 文本 ----------

/** UPPER：转大写。 */
export function upper<T extends string | null>(e: Expr<T>): Expr<T> {
  return functionCall(e.type, "UPPER", [e]);
}

/** LOWER：转小写。 */
export function lower<T extends string | null>(e: Expr<T>): Expr<T> {
  return functionCall(e.type, "LOWER", [e]);
}

/** TRIM：去掉两端空白。 */
export function trim<T extends string | null>(e: Expr<T>): Expr<T> {
  return functionCall(e.type, "TRIM", [e]);
}

/** 校验文本位置是从 1 开始的整数。 */
function textPosition(start: number): number {
  if (!Number.isInteger(start) || start < 1) throw new RangeError("Text positions are integers from 1; negative positions differ between databases");
  return start;
}

/** 校验长度是非负整数。 */
function textLength(length: number): number {
  if (!Number.isInteger(length) || length < 0) throw new RangeError("A length must be a non-negative integer");
  return length;
}

/** 文本的位置或长度：数字写成字面量，表达式则现场计算，例如来自 `locate`。 */
type Position = number | Expr<number> | Expr<number | null>;

/** 把位置参数转成表达式；数字先经 `check` 校验。 */
function positionArg(p: Position, check: (n: number) => number): Expr<unknown> {
  return typeof p === "number" ? lit(check(p)) : p;
}

/** SUBSTRING(e, start[, length])，从 1 开始计数。位置用字面量，调用才能作为 GROUP BY 的键。 */
export function substring<T extends string | null>(e: Expr<T>, start: Position, length?: Position): Expr<T> {
  const args: Expr<unknown>[] = [e, positionArg(start, textPosition)];
  if (length !== undefined) args.push(positionArg(length, textLength));
  return functionCall(text as SqlType<NonNullable<T>>, "SUBSTRING", args);
}

/** LEFT：取左边 `length` 个字符。 */
export function left<T extends string | null>(e: Expr<T>, length: Position): Expr<T> {
  return functionCall(text as SqlType<NonNullable<T>>, "LEFT", [e, positionArg(length, textLength)]);
}

/** RIGHT：取右边 `length` 个字符。 */
export function right<T extends string | null>(e: Expr<T>, length: Position): Expr<T> {
  return functionCall(text as SqlType<NonNullable<T>>, "RIGHT", [e, positionArg(length, textLength)]);
}

/** `search` 在 `value` 中第一次出现的位置，从 1 开始计数；没有出现时为 0。 */
export function locate<T extends string | null>(value: Expr<T>, search: string | Expr<string> | Expr<string | null>): Expr<KeepNull<T, number>> {
  return functionCall(integer, LOCATE, [value, typeof search === "string" ? param(text, search) : search]);
}

/** REPLACE：把 `search` 全部替换为 `replacement`。 */
export function replace<T extends string | null>(e: Expr<T>, search: string, replacement: string): Expr<T> {
  return functionCall(text as SqlType<NonNullable<T>>, "REPLACE", [e, param(text, search), param(text, replacement)]);
}

/** 拼接至少两个文本；各方言写法不同，见 {@link CONCAT}。 */
export function concat(...parts: Expr<string>[]): Expr<string>;
export function concat(...parts: Expr<string | null>[]): Expr<string | null>;
export function concat(...parts: Expr<string | null>[]): Expr<string | null> {
  if (parts.length < 2) throw new TypeError("concat() needs at least two operands");
  return functionCall(text, CONCAT, parts);
}

// ---------- 数值 ----------

/** 数值表达式可以取的值类型（decimal 用字符串）。 */
type Numeric = number | bigint | string | null;

/** 加法，结果取第一个操作数的类型。传入列表则对所有项求和，例如十六个期间列。 */
export function plus<T extends Numeric>(terms: readonly Expr<T>[]): Expr<T>;
export function plus<T extends Numeric>(first: Expr<T>, ...rest: Expr<Numeric>[]): Expr<T>;
export function plus<T extends Numeric>(first: Expr<T> | readonly Expr<T>[], ...rest: Expr<Numeric>[]): Expr<T> {
  if (!Array.isArray(first)) return arithmetic("+", first as Expr<T>, rest);
  const [head, ...tail] = first as readonly Expr<T>[];
  if (head === undefined) throw new TypeError("plus() needs at least one term");
  return arithmetic("+", head, tail);
}

/** 取负：`-(e)`；加括号是为了避免负的字面量连成 `--` 变成注释。 */
export function negate<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "negate");
  return expression(e.type, (w) => w.sql("-(").render(e).sql(")"));
}

/** 乘法，结果取第一个操作数的类型。 */
export function times<T extends Numeric>(first: Expr<T>, ...rest: Expr<Numeric>[]): Expr<T> {
  return arithmetic("*", first, rest);
}

/** 减法，结果取被减数的类型。 */
export function minus<T extends Numeric>(left: Expr<T>, right: Expr<Numeric>): Expr<T> {
  return arithmetic("-", left, [right]);
}

/** 只允许 decimal 或 double 的除法：整数除法在有的数据库截断、有的不截断。 */
export function divide<T extends number | string | null>(left: Expr<T>, right: Expr<Numeric>): Expr<T> {
  if (left.type.kind !== "decimal" && left.type.kind !== "double") throw new TypeError(`divide() needs a decimal or double dividend, got ${left.type.name}`);
  return arithmetic("/", left, [right]);
}

/** 校验所有操作数都是数值，构造算术表达式。 */
function arithmetic<T>(operator: string, first: Expr<T>, rest: readonly Expr<unknown>[]): Expr<T> {
  for (const e of [first, ...rest]) numeric(e as Expr<unknown>, operator);
  return new Arithmetic(first.type, operator, [first as Expr<unknown>, ...rest]);
}

/** ABS：绝对值。 */
export function abs<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "ABS");
  return functionCall(e.type, "ABS", [e]);
}

/** FLOOR：向下取整。 */
export function floor<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "FLOOR");
  return functionCall(e.type, "FLOOR", [e]);
}

/** CEIL：向上取整。 */
export function ceil<T extends Numeric>(e: Expr<T>): Expr<T> {
  numeric(e as Expr<unknown>, "CEIL");
  return functionCall(e.type, "CEIL", [e]);
}

/** ROUND：四舍五入到 `scale` 位小数。 */
export function round<T extends Numeric>(e: Expr<T>, scale: number): Expr<T> {
  numeric(e as Expr<unknown>, "ROUND");
  return functionCall(e.type, "ROUND", [e, lit(scale)]);
}

// ---------- 日期 ----------

/** 年份，参数可以是日期或时间戳：`groupBy((l) => [year(l.createdOn), month(l.createdOn)])`。 */
export function year<T extends string | null>(e: Expr<T>): Expr<KeepNull<T, number>> {
  temporal(e, "YEAR");
  return functionCall(integer, YEAR, [e]);
}

/** 月份，1 到 12。 */
export function month<T extends string | null>(e: Expr<T>): Expr<KeepNull<T, number>> {
  temporal(e, "MONTH");
  return functionCall(integer, MONTH, [e]);
}

/** 月中的第几天，1 到 31。 */
export function day<T extends string | null>(e: Expr<T>): Expr<KeepNull<T, number>> {
  temporal(e, "DAYOFMONTH");
  return functionCall(integer, DAY, [e]);
}

/** 日期加上 days 天，可以为负。 */
export function addDays<T extends string | null>(day: Expr<T>, days: number): Expr<T> {
  return dateShift(ADD_DAYS, day, days);
}

/** 日期加上 months 个月；目标月没有那一天时取月末，例如 1 月 31 日加一个月是 2 月末。 */
export function addMonths<T extends string | null>(day: Expr<T>, months: number): Expr<T> {
  return dateShift(ADD_MONTHS, day, months);
}

/** 日期加上 years 年；2 月 29 日加一年是 2 月 28 日。 */
export function addYears<T extends string | null>(day: Expr<T>, years: number): Expr<T> {
  return dateShift(ADD_YEARS, day, years);
}

/** 从 from 到 to 相隔的天数，to 较早时为负。 */
export function daysBetween(from: Expr<string | null>, to: Expr<string | null>): Expr<number | null> {
  temporal(from, "DAYS_BETWEEN", DATE_ONLY);
  temporal(to, "DAYS_BETWEEN", DATE_ONLY);
  return functionCall(integer, DAYS_BETWEEN, [from, to]);
}

/** 从 from 到 to 相隔的整秒数（不足一秒的部分舍去），to 较早时为负；任一端为 null 时为 null。 */
export function secondsBetween(from: Expr<string | null>, to: Expr<string | null>): Expr<number | null> {
  temporal(from, "SECONDS_BETWEEN", TIMESTAMP_ONLY);
  temporal(to, "SECONDS_BETWEEN", TIMESTAMP_ONLY);
  return functionCall(integer, SECONDS_BETWEEN, [from, to]);
}

/** 日期加减的公共部分：检查类型、整数个数，再按函数定义渲染。 */
function dateShift<T extends string | null>(fn: FunctionDef, day: Expr<T>, n: number): Expr<T> {
  temporal(day, fn.name, DATE_ONLY);
  if (!Number.isInteger(n)) throw new RangeError(`${fn.name} needs a whole number, got ${n}`);
  return functionCall(day.type, fn, [day, lit(n)]);
}

// ---------- 聚合 ----------

/** COUNT：不传参数时是 COUNT(*)，传入表达式时不计 NULL。 */
export function count(e?: Expr<unknown>): Aggregate<number> {
  return new Aggregate(integer, "COUNT", false, e === undefined ? [] : [e]);
}

/** COUNT(DISTINCT e)。 */
export function countDistinct(e: Expr<unknown>): Aggregate<number> {
  return new Aggregate(integer, "COUNT", true, [e]);
}

/** SUM：求和，没有行时为 NULL。 */
export function sum<T extends Numeric>(e: Expr<T>): Aggregate<T | null> {
  numeric(e as Expr<unknown>, "SUM");
  return new Aggregate(e.type, "SUM", false, [e as Expr<unknown>]);
}

/** AVG：平均值，以精确的 decimal 返回。 */
export function avg(e: Expr<Numeric>): Aggregate<string | null> {
  numeric(e as Expr<unknown>, "AVG");
  return new Aggregate(decimal, "AVG", false, [e as Expr<unknown>]);
}

/** MIN：最小值。 */
export function min<T>(e: Expr<T>): Aggregate<T | null> {
  return new Aggregate(e.type, "MIN", false, [e as Expr<unknown>]);
}

/** MAX：最大值。 */
export function max<T>(e: Expr<T>): Aggregate<T | null> {
  return new Aggregate(e.type, "MAX", false, [e as Expr<unknown>]);
}

/**
 * 把组内的文本用 separator 拼接，可以指定拼接顺序：`stringAgg(l.delivery, ",", l.delivery.asc())`。
 * HANA、PostgreSQL 写成 STRING_AGG，MySQL 写成 GROUP_CONCAT（结果长度受 group_concat_max_len 限制，默认 1024 字节）。
 */
export function stringAgg(value: Expr<string | null>, separator: string, ...order: OrderItem[]): Expr<string | null> {
  const args = [value, lit(separator), ...order.map(toOrder)];
  return expression(text, (w) => STRING_AGG.render(w, args));
}

/** 在 `groupByRollup` 的小计行上，该键被汇总时为 1，其余为 0。 */
export function grouping(key: Expr<unknown>): Expr<number> {
  return functionCall(integer, "GROUPING", [key]);
}

// ---------- 窗口函数 ----------

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

/** LAG：往前第 `offset` 行的值，越界为 NULL。 */
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

/** NTILE：按窗口的顺序把分区分成 buckets 组，给出组号（1 起），例如四分位。 */
export function ntile(buckets: number): WindowFunction<number> {
  if (!Number.isInteger(buckets) || buckets < 1) throw new RangeError("ntile needs a whole number of buckets, at least 1");
  return new WindowFunction(integer, "NTILE", [lit(buckets)]);
}

// ---------- CASE、CAST、SQL 片段、函数、子查询 ----------

/** 开始 CASE WHEN condition THEN result；第一个分支决定结果类型，后续用 `.when(...)` 追加。 */
export function caseWhen(condition: Cond, result: string): Case<string>;
export function caseWhen(condition: Cond, result: number): Case<number>;
export function caseWhen(condition: Cond, result: boolean): Case<boolean>;
export function caseWhen<T>(condition: Cond, result: Expr<T>): Case<T>;
export function caseWhen(condition: Cond, result: unknown): Case<unknown> {
  const e = result instanceof Expr ? (result as Expr<unknown>) : val(result as {});
  return new Case(e.type, [[condition, e]]);
}

/** `CASE subject WHEN value THEN result ... END`：`caseOf(code).when("1", "own").when("2", "supplier").otherwise("other")`。 */
export function caseOf<V>(subject: Expr<V>): CaseOf<V> {
  return new CaseOf(subject);
}

/** CAST(e AS 目标类型)，等同 `e.cast(target)`。 */
export function cast<T, U>(e: Expr<T>, target: CastType<U>): Expr<KeepNull<T, U>> {
  return e.cast(target);
}

/**
 * 带类型的 SQL 片段：`sql(integer)\`DAYS_BETWEEN(${a}, ${b})\``。表达式原位渲染，
 * 其他值成为绑定参数。不指定类型时，值按驱动返回的样子读取。
 */
export function sql<T>(type: SqlType<T>): (parts: TemplateStringsArray, ...args: TemplateArg[]) => SqlExpr<T>;
export function sql(parts: TemplateStringsArray, ...args: TemplateArg[]): SqlExpr<unknown>;
export function sql(first: SqlType<unknown> | TemplateStringsArray, ...rest: TemplateArg[]): unknown {
  const build = (type: SqlType<unknown>, parts: TemplateStringsArray, args: readonly TemplateArg[]) =>
    new SqlExpr(type as SqlType<{}>, [{ dialect: undefined, parts, args }]);
  if (Array.isArray(first)) return build(untyped, first as TemplateStringsArray, rest);
  return (parts: TemplateStringsArray, ...args: TemplateArg[]) => build(first as SqlType<unknown>, parts, args);
}

/** 调用一个函数定义；内置函数和用户自定义函数用同一种方式构造。 */
export function call<T>(fn: FunctionDef, type: SqlType<NonNullable<T>>, ...args: Expr<unknown>[]): Expr<T> {
  return functionCall(type, fn, args);
}

/** 只有一列的查询当作一个值使用；没有行时为 NULL。 */
export function scalar<T>(subquery: Query<T>): Expr<T | null> {
  return new Scalar(subquery);
}

/** CURRENT_DATE：数据库当前日期。 */
export function currentDate(): Expr<string> {
  return raw(date, "CURRENT_DATE");
}

/** CURRENT_TIMESTAMP：数据库当前时间戳。 */
export function currentTimestamp(): Expr<string> {
  return raw(timestamp, "CURRENT_TIMESTAMP");
}

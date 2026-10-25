import type { CalendarDate, CalendarDateTime } from "@internationalized/date";
import type { Decimal } from "decimal.js";
import { ADD_DAYS, ADD_MONTHS, ADD_SECONDS, ADD_YEARS, CONCAT, DAY, DAYS_BETWEEN, HOUR, LOCATE, MINUTE, MONTH, MONTH_START, SECOND, SECONDS_BETWEEN, STRING_AGG, YEAR } from "./builtins.js";
import { addCondition, and, Between, collect, compare, condition, Cond, inList, inQuery, skips } from "./cond.js";
import { FunctionDef } from "./dialect.js";
import { COLUMN_TYPES, SUBQUERY } from "../internal.js";
import type { Query } from "../query/query.js";
import { bigint, CastType, date, decimal, double, integer, text, timestamp, typeOf, untyped, type SqlType, type TypeKind } from "../schema/types.js";
import type { ExprNumber, ExprOf } from "./typed.js";
import { Window } from "./window.js";
import type { Renderable, SqlWriter } from "./writer.js";

// cond.ts、window.ts 与本模块互相引用：各模块只在函数体里用对方的值，模块顶层不用，所以先加载哪一个都可以。

declare const valueType: unique symbol;

/** `U`；当 `T` 可为 null 时是 `U | null`：函数保持操作数的可空性。 */
export type KeepNull<T, U> = null extends T ? U | null : U;

/**
 * 产生 TypeScript 类型 T 的值的 SQL 表达式（值可能缺失时 T 含 null）。这里只放所有类型共有的方法；按类型才有的运算在
 * {@link ExprOf} 给出的接口上（文本 `ExprText`、数值 `ExprNumber`、日期 `ExprDate` 等，见 typed.ts），函数的结果都按值类型
 * 给出那一类接口，所以 `l.unit.coalesce("EA").upper()` 能接着写。
 *
 * 比较、IN、IS NULL 等条件方法不返回值，而是写进当前的条件块（见 `collect`）：
 * `where((l) => { l.unit.eq("KG"); l.quantity.gt(new Decimal(1)); })`。与 null 比较是错误，除非查询开了 `skipEmpty()`。
 *
 * 要求操作数同为 T 的运算是实例方法：先由接收者确定 T 再检查参数，所以文本列不会与数字比较。参数写成重载（值，或表达式），
 * 而不是 `value | Expr` 联合，后者会削弱类型推断。要增加表达式节点，继承 {@link ExprNode}（或用 {@link expression}）。
 */
export interface Expr<T> extends Renderable {
  /** 只在类型层面携带值类型，运行时不存在。 */
  readonly [valueType]: T;
  /** 值类型，决定参数的绑定和结果的读取。 */
  readonly type: SqlType<NonNullable<T>>;

  /** 等于：参数是值或另一个表达式；skipEmpty 下值为空时没有条件。 */
  eq(value: NonNullable<T> | null | undefined): void;
  eq(other: Expr<NonNullable<T> | null>): void;
  /** 不等于，规则同 {@link eq}。 */
  ne(value: NonNullable<T> | null | undefined): void;
  ne(other: Expr<NonNullable<T> | null>): void;
  /** 大于，规则同 {@link eq}。 */
  gt(value: NonNullable<T> | null | undefined): void;
  gt(other: Expr<NonNullable<T> | null>): void;
  /** 大于等于，规则同 {@link eq}。 */
  ge(value: NonNullable<T> | null | undefined): void;
  ge(other: Expr<NonNullable<T> | null>): void;
  /** 小于，规则同 {@link eq}。 */
  lt(value: NonNullable<T> | null | undefined): void;
  lt(other: Expr<NonNullable<T> | null>): void;
  /** 小于等于，规则同 {@link eq}。 */
  le(value: NonNullable<T> | null | undefined): void;
  le(other: Expr<NonNullable<T> | null>): void;
  /** BETWEEN low AND high；skipEmpty 下缺一边时只留下另一边，两边都缺时没有条件。 */
  between(low: NonNullable<T> | null | undefined, high: NonNullable<T> | null | undefined): void;
  between(low: Expr<NonNullable<T> | null>, high: Expr<NonNullable<T> | null>): void;
  /**
   * 半开区间 [from, until)：写成 `x >= from AND x < until`，适合日期和时间段，
   * 例如 `l.createdOn.range(monthStart, nextMonthStart)`。skipEmpty 下缺少的一端被忽略。
   */
  range(from: NonNullable<T> | null | undefined, until: NonNullable<T> | null | undefined): void;
  /** IN 列表或子查询；空列表恒假（skipEmpty 下没有条件），很长的列表按方言上限拆开。 */
  in(values: readonly NonNullable<T>[] | null | undefined): void;
  in(subquery: Query<NonNullable<T> | null>): void;
  /** NOT IN 列表或子查询；空列表恒真（skipEmpty 下没有条件）。 */
  notIn(values: readonly NonNullable<T>[] | null | undefined): void;
  notIn(subquery: Query<NonNullable<T> | null>): void;
  /** NULL 也相等的比较：两侧都为 NULL 时为真，只有一侧为 NULL 时为假（标准 SQL 的 `IS NOT DISTINCT FROM`）。 */
  isNotDistinctFrom(other: Expr<NonNullable<T> | null>): void;
  /** {@link isNotDistinctFrom} 的否定：值不同，或只有一侧为 NULL 时为真。 */
  isDistinctFrom(other: Expr<NonNullable<T> | null>): void;
  /** IS NULL。 */
  isNull(): void;
  /** IS NOT NULL。 */
  isNotNull(): void;

  /** 取自身和各备选值中第一个非空的；只有最后一个备选值恒不为空，结果才是非空类型。 */
  coalesce(fallback: NonNullable<T>): ExprOf<NonNullable<T>>;
  coalesce(fallback: Expr<NonNullable<T>>): ExprOf<NonNullable<T>>;
  coalesce(...fallbacks: [...Expr<NonNullable<T> | null>[], NonNullable<T> | Expr<NonNullable<T>>]): ExprOf<NonNullable<T>>;
  coalesce(...fallbacks: Expr<NonNullable<T> | null>[]): ExprOf<NonNullable<T> | null>;
  /** NULLIF(自身, value)：两者相等时得到 NULL。 */
  nullif(value: NonNullable<T>): ExprOf<NonNullable<T> | null>;
  /** MAX：最大值，没有行时为 NULL。 */
  max(): ExprOf<T | null>;
  /** MIN：最小值，没有行时为 NULL。 */
  min(): ExprOf<T | null>;
  /** COUNT(e)：不计 NULL；COUNT(*) 是静态函数 `count()`。 */
  count(): ExprNumber<number>;
  /** COUNT(DISTINCT e)。 */
  countDistinct(): ExprNumber<number>;
  /**
   * 条件聚合：只聚合满足块里条件的行，例如 `l.quantity.sum().filter(() => l.unit.eq("KG"))`。
   * 各库都写成 `SUM(CASE WHEN 条件 THEN x END)`，COUNT(*) 写成 `COUNT(CASE WHEN 条件 THEN 1 END)`；
   * 多次调用按 AND 合并，没有条件（例如 skipEmpty 省略掉的）不过滤。只能接在聚合上，否则立即报错。
   */
  filter(condition: () => void): ExprOf<T>;
  /** 把聚合作为窗口函数使用：`l.quantity.sum().over((w) => w.partitionBy(l.material))`；只能接在聚合上。 */
  over(window: (w: Window) => Window): ExprOf<T>;

  /** 给表达式起列标签，投影时作为结果的键。 */
  as(label: string): ExprOf<T>;
  /** 升序排序项。 */
  asc(): Order;
  /** 降序排序项。 */
  desc(): Order;
  /** CAST 成目标类型；可空性保持不变，结果的类型接口由目标决定（`cast(CastType.decimal(15, 3))` 是 `ExprDecimal`）。 */
  cast<U>(target: CastType<U>): ExprOf<KeepNull<T, U>>;
}

// ---------- 实现 ----------

/** 文本函数接受的种类；`unknown` 放行原生 SQL 片段，下同。 */
const TEXT: readonly TypeKind[] = ["text", "unknown"];

/** 可以参与算术运算的种类。 */
const NUMERIC: readonly TypeKind[] = ["integer", "bigint", "double", "decimal", "unknown"];

/** 可以取日期部分的种类。 */
const TEMPORAL: readonly TypeKind[] = ["date", "timestamp", "unknown"];

/** 日期加减和相隔天数只接受日期：时间戳在 PostgreSQL 上加整数会失败。 */
const DATE_ONLY: readonly TypeKind[] = ["date", "unknown"];

/** 相隔秒数、时分秒只接受时间戳。 */
const TIMESTAMP_ONLY: readonly TypeKind[] = ["timestamp", "unknown"];

/** 报错里对种类的称呼。 */
const KIND_NAMES = new Map<readonly TypeKind[], string>([
  [TEXT, "a text"],
  [NUMERIC, "a numeric"],
  [TEMPORAL, "a date or timestamp"],
  [DATE_ONLY, "a date"],
  [TIMESTAMP_ONLY, "a timestamp"],
]);

/** 种类对应的不带转换器的类型：算出新值的运算回到库里的类型，静态类型也是存储类型的默认值类型。 */
const STORED: Readonly<Record<TypeKind, SqlType<unknown>>> = {
  text: text as SqlType<unknown>,
  integer: integer as SqlType<unknown>,
  bigint: bigint as SqlType<unknown>,
  double: double as SqlType<unknown>,
  decimal: decimal as SqlType<unknown>,
  boolean: untyped,
  date: date as SqlType<unknown>,
  timestamp: timestamp as SqlType<unknown>,
  bytes: untyped,
  unknown: untyped,
};

/**
 * 所有表达式节点的基类：运行时每个表达式都是它，它实现了各类型接口（typed.ts）的全部方法，能点出哪些由接口决定；
 * 类型专属的方法在这里签名宽松，先按 `type.kind` 检查种类，绕过类型检查（`any`、JavaScript）调用时给出明确的报错。
 * 继承它并实现 {@link render} 即可增加表达式节点。
 */
export abstract class ExprNode<T> implements Expr<T> {
  declare readonly [valueType]: T;

  constructor(readonly type: SqlType<NonNullable<T>>) {}

  /** 把表达式写入 SQL 写入器。 */
  abstract render(w: SqlWriter): void;

  /** 单独选取该表达式时使用的列标签；列的标签就是属性名。 */
  get label(): string | undefined {
    return undefined;
  }

  // ---------- 条件 ----------

  eq(value: NonNullable<T> | null | undefined): void;
  eq(other: Expr<NonNullable<T> | null>): void;
  eq(arg: unknown): void {
    addCondition(this.#comparison("=", arg));
  }

  ne(value: NonNullable<T> | null | undefined): void;
  ne(other: Expr<NonNullable<T> | null>): void;
  ne(arg: unknown): void {
    addCondition(this.#comparison("<>", arg));
  }

  gt(value: NonNullable<T> | null | undefined): void;
  gt(other: Expr<NonNullable<T> | null>): void;
  gt(arg: unknown): void {
    addCondition(this.#comparison(">", arg));
  }

  ge(value: NonNullable<T> | null | undefined): void;
  ge(other: Expr<NonNullable<T> | null>): void;
  ge(arg: unknown): void {
    addCondition(this.#comparison(">=", arg));
  }

  lt(value: NonNullable<T> | null | undefined): void;
  lt(other: Expr<NonNullable<T> | null>): void;
  lt(arg: unknown): void {
    addCondition(this.#comparison("<", arg));
  }

  le(value: NonNullable<T> | null | undefined): void;
  le(other: Expr<NonNullable<T> | null>): void;
  le(arg: unknown): void {
    addCondition(this.#comparison("<=", arg));
  }

  between(low: NonNullable<T> | null | undefined, high: NonNullable<T> | null | undefined): void;
  between(low: Expr<NonNullable<T> | null>, high: Expr<NonNullable<T> | null>): void;
  between(low: unknown, high: unknown): void {
    if (!(low instanceof ExprNode) && !(high instanceof ExprNode) && (skips(low) || skips(high))) {
      // 合成一个条件写进块里：在 any(() => ...) 里两个条件会成为两个 OR 选项
      addCondition(and(this.#comparison(">=", low), this.#comparison("<=", high)));
      return;
    }
    addCondition(new Between(this, this.#operand(low), this.#operand(high)));
  }

  range(from: NonNullable<T> | null | undefined, until: NonNullable<T> | null | undefined): void {
    addCondition(and(this.#comparison(">=", from), this.#comparison("<", until)));
  }

  in(values: readonly NonNullable<T>[] | null | undefined): void;
  in(subquery: Query<NonNullable<T> | null>): void;
  in(arg: unknown): void {
    addCondition(this.#membership(arg, false));
  }

  notIn(values: readonly NonNullable<T>[] | null | undefined): void;
  notIn(subquery: Query<NonNullable<T> | null>): void;
  notIn(arg: unknown): void {
    addCondition(this.#membership(arg, true));
  }

  isNotDistinctFrom(other: Expr<NonNullable<T> | null>): void {
    addCondition(condition((w) => w.dialect.notDistinct(w, this, other, false)));
  }

  isDistinctFrom(other: Expr<NonNullable<T> | null>): void {
    addCondition(condition((w) => w.dialect.notDistinct(w, this, other, true)));
  }

  isNull(): void {
    addCondition(condition((w) => w.render(this).sql(" IS NULL")));
  }

  isNotNull(): void {
    addCondition(condition((w) => w.render(this).sql(" IS NOT NULL")));
  }

  /** `this op 参数`；skipEmpty 下值为空时没有条件。 */
  #comparison(operator: string, arg: unknown): Cond {
    if (!(arg instanceof ExprNode) && skips(arg)) return Cond.EMPTY;
    return compare(this, operator, this.#operand(arg));
  }

  /** IN / NOT IN 值列表或子查询；值为 null 时 skipEmpty 下没有条件，否则报错。 */
  #membership(arg: unknown, negate: boolean): Cond {
    if (arg == null) {
      if (skips(arg)) return Cond.EMPTY;
      throw new TypeError("IN needs a list or a subquery; use skipEmpty() on the query to drop conditions on empty values");
    }
    if (!Array.isArray(arg)) return inQuery(this, arg as Query<unknown>, negate);
    return skips(arg) ? Cond.EMPTY : inList(this, arg.map((v) => this.#param(v)), negate);
  }

  // ---------- 值不变的函数 ----------

  coalesce(fallback: NonNullable<T>): ExprOf<NonNullable<T>>;
  coalesce(fallback: Expr<NonNullable<T>>): ExprOf<NonNullable<T>>;
  coalesce(...fallbacks: [...Expr<NonNullable<T> | null>[], NonNullable<T> | Expr<NonNullable<T>>]): ExprOf<NonNullable<T>>;
  coalesce(...fallbacks: Expr<NonNullable<T> | null>[]): ExprOf<NonNullable<T> | null>;
  coalesce(...fallbacks: unknown[]): unknown {
    if (fallbacks.length === 0) throw new TypeError("coalesce() needs at least one fallback");
    return functionCall(this.type, "COALESCE", [this, ...fallbacks.map((f) => this.#operand(f))]);
  }

  nullif(value: NonNullable<T>): ExprOf<NonNullable<T> | null> {
    return functionCall<NonNullable<T> | null>(this.type, "NULLIF", [this, this.#param(value)]);
  }

  max(): ExprOf<T | null> {
    return new Aggregate<T | null>(this.type, "MAX", false, [this]) as never;
  }

  min(): ExprOf<T | null> {
    return new Aggregate<T | null>(this.type, "MIN", false, [this]) as never;
  }

  count(): ExprNumber<number> {
    return new Aggregate<number>(integer, "COUNT", false, [this]) as never;
  }

  countDistinct(): ExprNumber<number> {
    return new Aggregate<number>(integer, "COUNT", true, [this]) as never;
  }

  filter(_condition: () => void): ExprOf<T> {
    throw new TypeError("filter(...) applies to an aggregate such as e.sum() or count()");
  }

  over(_window: (w: Window) => Window): ExprOf<T> {
    throw new TypeError("over(...) applies to an aggregate such as e.sum(); use a window function such as rowNumber() otherwise");
  }

  // ---------- 命名、排序、转换 ----------

  as(label: string): ExprOf<T> {
    return new Labeled(this, label) as never;
  }

  asc(): Order {
    return new Order(this, false, undefined);
  }

  desc(): Order {
    return new Order(this, true, undefined);
  }

  cast<U>(target: CastType<U>): ExprOf<KeepNull<T, U>> {
    return new CastExpr(this, target as CastType<unknown>) as never;
  }

  // ---------- 文本（ExprText） ----------

  /** LIKE 模式。 */
  like(pattern: string | null | undefined): void {
    addCondition(this.#pattern("LIKE", pattern));
  }

  /** NOT LIKE 模式。 */
  notLike(pattern: string | null | undefined): void {
    addCondition(this.#pattern("NOT LIKE", pattern));
  }

  /** 包含：`LIKE '%text%' ESCAPE '!'`。 */
  contains(value: string | null | undefined): void {
    addCondition(this.#escapedLike("%", value, "%"));
  }

  /** 开头是：`LIKE 'text%' ESCAPE '!'`。 */
  startsWith(value: string | null | undefined): void {
    addCondition(this.#escapedLike("", value, "%"));
  }

  /** 结尾是：`LIKE '%text' ESCAPE '!'`。 */
  endsWith(value: string | null | undefined): void {
    addCondition(this.#escapedLike("%", value, ""));
  }

  /** UPPER(e)。 */
  upper(): ExprNode<unknown> {
    return this.#text("UPPER");
  }

  /** LOWER(e)。 */
  lower(): ExprNode<unknown> {
    return this.#text("LOWER");
  }

  /** TRIM(e)。 */
  trim(): ExprNode<unknown> {
    return this.#text("TRIM");
  }

  /** SUBSTRING(e, start[, length])。 */
  substring(start: Position, length?: Position): ExprNode<unknown> {
    const args = [position(start, textPosition)];
    if (length !== undefined) args.push(position(length, textLength));
    return this.#text("SUBSTRING", ...args);
  }

  /** LEFT(e, length)。 */
  left(length: Position): ExprNode<unknown> {
    return this.#text("LEFT", position(length, textLength));
  }

  /** RIGHT(e, length)。 */
  right(length: Position): ExprNode<unknown> {
    return this.#text("RIGHT", position(length, textLength));
  }

  /** REPLACE(e, search, replacement)。 */
  replace(search: string, replacement: string): ExprNode<unknown> {
    return this.#text("REPLACE", param(text, search), param(text, replacement));
  }

  /** LOCATE(e, search)，由方言决定写法。 */
  locate(search: string | Expr<string | null>): ExprNode<unknown> {
    this.#expect(TEXT, "LOCATE");
    return new Call(integer, LOCATE, [this, typeof search === "string" ? param(text, search) : search]);
  }

  /** 拼接，由方言决定写法。 */
  concat(...parts: (string | Expr<string | null>)[]): ExprNode<unknown> {
    this.#expect(TEXT, "CONCAT");
    if (parts.length === 0) throw new TypeError("concat() needs at least one more operand");
    return new Call(text, CONCAT, [this, ...parts.map((p) => (typeof p === "string" ? param(text, p) : p))]);
  }

  /** LPAD(e, length, pad)。 */
  lpad(length: number, pad: string): ExprNode<unknown> {
    return this.#text("LPAD", literal(whole(length, "lpad")), literal(pad));
  }

  /** RPAD(e, length, pad)。 */
  rpad(length: number, pad: string): ExprNode<unknown> {
    return this.#text("RPAD", literal(whole(length, "rpad")), literal(pad));
  }

  /** STRING_AGG(e, separator ORDER BY ...)，由方言决定写法。 */
  stringAgg(separator: string, ...order: OrderItem[]): ExprNode<unknown> {
    this.#expect(TEXT, "STRING_AGG");
    const args = [this, literal(separator), ...order.map(toOrder)];
    return new Call(text, STRING_AGG, args);
  }

  /** `this op 模式`，模式按文本绑定（以文本存别的类型的列，模式也是库里的文本）。 */
  #pattern(operator: string, pattern: string | null | undefined): Cond {
    this.#expect(TEXT, operator);
    if (skips(pattern)) return Cond.EMPTY;
    return compare(this, operator, param(text, pattern as string));
  }

  /** prefix + 转义后的 value + suffix 作为 LIKE 模式；用 ! 作转义符，各库的写法相同。 */
  #escapedLike(prefix: string, value: string | null | undefined, suffix: string): Cond {
    this.#expect(TEXT, "LIKE");
    if (skips(value)) return Cond.EMPTY;
    const pattern = prefix + param(text, value as string).value.replaceAll("!", "!!").replaceAll("%", "!%").replaceAll("_", "!_") + suffix;
    return condition((w) => w.render(this).sql(" LIKE ").render(param(text, pattern)).sql(" ESCAPE '!'"));
  }

  /** 结果是文本的函数调用 `NAME(this, ...args)`。 */
  #text(name: string, ...args: Expr<unknown>[]): ExprNode<unknown> {
    this.#expect(TEXT, name);
    return new Call(text, name, [this, ...args]);
  }

  // ---------- 数值（ExprNumber、ExprBigint、ExprDecimal） ----------

  /** this + other；`a.plus(b).plus(c)` 并成 `(a + b + c)`。 */
  plus(other: unknown): ExprNode<unknown> {
    return this.#arithmetic("+", other);
  }

  /** this - other。 */
  minus(other: unknown): ExprNode<unknown> {
    return this.#arithmetic("-", other);
  }

  /** this * other；连乘并成一个括号。 */
  times(other: unknown): ExprNode<unknown> {
    return this.#arithmetic("*", other);
  }

  /** this / other；只允许小数或浮点数的被除数：整数相除在有的数据库截断、有的不截断。 */
  divide(other: unknown): ExprNode<unknown> {
    const kind = this.type.kind;
    if (kind === "integer" || kind === "bigint") throw new TypeError(`divide() needs a decimal or double dividend, got ${this.type.name}; cast it first`);
    return this.#arithmetic("/", other);
  }

  /** MOD(this, divisor)。 */
  mod(divisor: unknown): ExprNode<unknown> {
    return this.#numeric("MOD", this.#stored(), numericOperand(divisor, literal));
  }

  /** `-(this)`；加括号是为了避免负的字面量连成 `--` 变成注释。 */
  negate(): ExprNode<unknown> {
    this.#expect(NUMERIC, "negate");
    return new Custom(this.#stored(), (w) => w.sql("-(").render(this).sql(")"));
  }

  /** ABS(this)。 */
  abs(): ExprNode<unknown> {
    return this.#numeric("ABS", this.#stored());
  }

  /** FLOOR(this)。 */
  floor(): ExprNode<unknown> {
    return this.#numeric("FLOOR", this.#stored());
  }

  /** CEIL(this)。 */
  ceil(): ExprNode<unknown> {
    return this.#numeric("CEIL", this.#stored());
  }

  /** ROUND(this, scale)。 */
  round(scale: number): ExprNode<unknown> {
    return this.#numeric("ROUND", this.#stored(), literal(scale));
  }

  /** POWER(this, exponent)，浮点数。 */
  power(exponent: unknown): ExprNode<unknown> {
    return this.#numeric("POWER", double, numericOperand(exponent, literal));
  }

  /** SQRT(this)，浮点数。 */
  sqrt(): ExprNode<unknown> {
    return this.#numeric("SQRT", double);
  }

  /** SIGN(this)，整数。 */
  sign(): ExprNode<unknown> {
    return this.#numeric("SIGN", integer);
  }

  /** SUM(this)。 */
  sum(): ExprNode<unknown> {
    this.#expect(NUMERIC, "SUM");
    return new Aggregate(this.#stored(), "SUM", false, [this]);
  }

  /** SUM(DISTINCT this)。 */
  sumDistinct(): ExprNode<unknown> {
    this.#expect(NUMERIC, "SUM");
    return new Aggregate(this.#stored(), "SUM", true, [this]);
  }

  /** AVG(this)，精确的小数。 */
  avg(): ExprNode<unknown> {
    this.#expect(NUMERIC, "AVG");
    return new Aggregate(decimal, "AVG", false, [this]);
  }

  /** AVG(DISTINCT this)，精确的小数。 */
  avgDistinct(): ExprNode<unknown> {
    this.#expect(NUMERIC, "AVG");
    return new Aggregate(decimal, "AVG", true, [this]);
  }

  /** STDDEV_SAMP(CAST(this AS DOUBLE))。 */
  stddevSamp(): ExprNode<unknown> {
    return this.#statistic("STDDEV_SAMP");
  }

  /** STDDEV_POP(CAST(this AS DOUBLE))。 */
  stddevPop(): ExprNode<unknown> {
    return this.#statistic("STDDEV_POP");
  }

  /** VAR_SAMP(CAST(this AS DOUBLE))。 */
  varSamp(): ExprNode<unknown> {
    return this.#statistic("VAR_SAMP");
  }

  /** VAR_POP(CAST(this AS DOUBLE))。 */
  varPop(): ExprNode<unknown> {
    return this.#statistic("VAR_POP");
  }

  /** 算术运算；同一个 + 或 * 接着写时并进前一个括号，渲染与一次写全相同。 */
  #arithmetic(operator: string, other: unknown): ExprNode<unknown> {
    this.#expect(NUMERIC, operator);
    const right = numericOperand(other, (v) => param(typeOf(v) as SqlType<{}>, v as {}));
    right.#expect(NUMERIC, operator);
    const chained = this instanceof Arithmetic && this.operator === operator && (operator === "+" || operator === "*");
    const operands = chained ? [...(this as Arithmetic<unknown>).operands, right] : [this, right];
    return new Arithmetic(promoted(operands, operator), operator, operands);
  }

  /** 数值函数 `NAME(this, ...args)`，结果类型是 type。 */
  #numeric(name: string, type: SqlType<unknown>, ...args: Expr<unknown>[]): ExprNode<unknown> {
    this.#expect(NUMERIC, name);
    return new Call(type as SqlType<{}>, name, [this, ...args]);
  }

  /** 浮点结果的统计聚合。MySQL 的 STDDEV 是总体标准差，与另两库不同，所以只提供带 _SAMP / _POP 的名字。 */
  #statistic(name: string): ExprNode<unknown> {
    this.#expect(NUMERIC, name);
    return new Aggregate(double, name, false, [new CastExpr(this, CastType.double() as CastType<unknown>)]);
  }

  // ---------- 日期与时间戳（ExprDate、ExprTimestamp） ----------

  /** YEAR(this)。 */
  year(): ExprNode<unknown> {
    return this.#temporal(YEAR, TEMPORAL, integer);
  }

  /** MONTH(this)。 */
  month(): ExprNode<unknown> {
    return this.#temporal(MONTH, TEMPORAL, integer);
  }

  /** DAYOFMONTH(this)。 */
  day(): ExprNode<unknown> {
    return this.#temporal(DAY, TEMPORAL, integer);
  }

  /** ADD_DAYS(this, days)。 */
  addDays(days: number): ExprNode<unknown> {
    return this.#temporal(ADD_DAYS, DATE_ONLY, date, literal(whole(days, "ADD_DAYS")));
  }

  /** ADD_MONTHS(this, months)。 */
  addMonths(months: number): ExprNode<unknown> {
    return this.#temporal(ADD_MONTHS, DATE_ONLY, date, literal(whole(months, "ADD_MONTHS")));
  }

  /** ADD_YEARS(this, years)。 */
  addYears(years: number): ExprNode<unknown> {
    return this.#temporal(ADD_YEARS, DATE_ONLY, date, literal(whole(years, "ADD_YEARS")));
  }

  /** 当月第一天。 */
  monthStart(): ExprNode<unknown> {
    return this.#temporal(MONTH_START, DATE_ONLY, date);
  }

  /** DAYS_BETWEEN(this, to)。 */
  daysBetween(to: unknown): ExprNode<unknown> {
    return this.#temporal(DAYS_BETWEEN, DATE_ONLY, integer, temporalOperand(to, date, DATE_ONLY, DAYS_BETWEEN.name));
  }

  /** HOUR(this)。 */
  hour(): ExprNode<unknown> {
    return this.#temporal(HOUR, TIMESTAMP_ONLY, integer);
  }

  /** MINUTE(this)。 */
  minute(): ExprNode<unknown> {
    return this.#temporal(MINUTE, TIMESTAMP_ONLY, integer);
  }

  /** 秒的整数部分。 */
  second(): ExprNode<unknown> {
    return this.#temporal(SECOND, TIMESTAMP_ONLY, integer);
  }

  /** 加 hours 小时：换算成秒。 */
  addHours(hours: number): ExprNode<unknown> {
    return this.addSeconds(whole(hours, "addHours") * 3600);
  }

  /** 加 minutes 分钟：换算成秒。 */
  addMinutes(minutes: number): ExprNode<unknown> {
    return this.addSeconds(whole(minutes, "addMinutes") * 60);
  }

  /** ADD_SECONDS(this, seconds)。 */
  addSeconds(seconds: number): ExprNode<unknown> {
    return this.#temporal(ADD_SECONDS, TIMESTAMP_ONLY, timestamp, literal(whole(seconds, "addSeconds")));
  }

  /** SECONDS_BETWEEN(this, to)。 */
  secondsBetween(to: unknown): ExprNode<unknown> {
    return this.#temporal(SECONDS_BETWEEN, TIMESTAMP_ONLY, integer, temporalOperand(to, timestamp, TIMESTAMP_ONLY, SECONDS_BETWEEN.name));
  }

  /** 日期函数 `fn(this, ...args)`：先检查种类，结果类型是 type。 */
  #temporal(fn: FunctionDef, kinds: readonly TypeKind[], type: SqlType<unknown>, ...args: Expr<unknown>[]): ExprNode<unknown> {
    this.#expect(kinds, fn.name);
    return new Call(type as SqlType<{}>, fn, [this, ...args]);
  }

  // ---------- 共用 ----------

  /** 要求本表达式是 kinds 之一，否则抛出 TypeError 并指出函数名。 */
  #expect(kinds: readonly TypeKind[], fn: string): void {
    if (!kinds.includes(this.type.kind)) throw new TypeError(`${fn} needs ${KIND_NAMES.get(kinds)} expression, got ${this.type.name}`);
  }

  /** 本表达式的种类不带转换器的类型，用于算出新值的运算。 */
  #stored(): SqlType<{}> {
    return STORED[this.type.kind] as SqlType<{}>;
  }

  /** 把值按本表达式的类型绑定成参数。 */
  #param(value: unknown): Param<unknown> {
    return param(this.type as SqlType<unknown>, value) as Param<unknown>;
  }

  /** 表达式原样使用，值则按本表达式的类型绑定成参数。 */
  #operand(arg: unknown): Expr<unknown> {
    return arg instanceof ExprNode ? (arg as Expr<unknown>) : this.#param(arg);
  }
}

/** 文本的位置或长度：数字写成字面量，表达式则现场计算。 */
type Position = number | Expr<number | null>;

/** 把位置参数转成表达式；数字先经 `check` 校验并写成字面量，调用才能作为 GROUP BY 的键。 */
function position(p: Position, check: (n: number) => number): Expr<unknown> {
  return typeof p === "number" ? literal(check(p)) : p;
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

/** 检查 n 是整数并返回它；what 用于报错。 */
function whole(n: number, what: string): number {
  if (!Number.isInteger(n)) throw new RangeError(`${what} needs a whole number, got ${n}`);
  return n;
}

/** 数值运算的另一个操作数：表达式原样使用，常量交给 bind（参数或字面量）。 */
function numericOperand(value: unknown, bind: (value: unknown) => ExprNode<unknown>): ExprNode<unknown> {
  return value instanceof ExprNode ? (value as ExprNode<unknown>) : bind(value);
}

/** 日期函数的另一端：表达式要是 kinds 之一，值按 type 绑定。 */
function temporalOperand(value: unknown, type: SqlType<unknown>, kinds: readonly TypeKind[], fn: string): Expr<unknown> {
  if (!(value instanceof ExprNode)) return param(type, value);
  if (!kinds.includes(value.type.kind)) throw new TypeError(`${fn} needs ${KIND_NAMES.get(kinds)} expression, got ${value.type.name}`);
  return value as Expr<unknown>;
}

/**
 * 算术结果的类型，与静态类型（typed.ts 的 `Wider`）一致：有小数为小数；有大整数为大整数（同时有浮点数时报错，
 * 因为静态类型是 bigint，浮点数的结果读不成它）；否则有浮点数为浮点数，都是整数为整数。
 */
function promoted(operands: readonly Expr<unknown>[], operator: string): SqlType<{}> {
  const kinds = new Set(operands.map((e) => e.type.kind));
  if (kinds.has("decimal")) return decimal as SqlType<{}>;
  if (kinds.has("bigint")) {
    if (kinds.has("double")) throw new TypeError(`${operator} mixes bigint and double operands; cast one of them first`);
    return bigint as SqlType<{}>;
  }
  if (kinds.has("double")) return double as SqlType<{}>;
  return (kinds.has("integer") ? integer : untyped) as SqlType<{}>;
}

// ---------- 参数与自定义节点 ----------

/** 绑定参数，按表达式的类型绑定，挂载的转换器作用于这个值。 */
export function param<T>(type: SqlType<T>, value: T): Param<T> {
  if (value == null) throw new TypeError("Comparing with NULL never matches; use isNull(), or skipEmpty() on the query to drop conditions on empty values");
  return new Param(type as SqlType<NonNullable<T>>, value);
}

/** 写语句的值：与 {@link param} 相同，但 null 也绑定为参数（插入或 SET 成 NULL）。 */
export function bindValue<T>(type: SqlType<T>, value: T | null): Expr<T | null> {
  return new Param(type as SqlType<NonNullable<T>>, value);
}

/** 直接写进 SQL 的常量，类型取自值本身。 */
function literal(value: unknown): ExprNode<unknown> {
  return new Literal(typeOf(value) as SqlType<{}>, value);
}

/** 用一个函数写出的表达式节点；方言包增加自己语法的方式，结果按 T 给出类型接口。 */
export function expression<T>(type: SqlType<NonNullable<T>>, render: (w: SqlWriter) => void): ExprOf<T> {
  return new Custom<T>(type, render) as never;
}

/** {@link expression} 的节点。 */
class Custom<T> extends ExprNode<T> {
  readonly #render: (w: SqlWriter) => void;

  constructor(type: SqlType<NonNullable<T>>, render: (w: SqlWriter) => void) {
    super(type);
    this.#render = render;
  }

  render(w: SqlWriter): void {
    this.#render(w);
  }
}

/** 构造函数调用表达式，`type` 是结果类型：{@link FunctionDef} 按方言渲染，字符串名先找方言按名字登记的写法。 */
export function functionCall<T>(type: SqlType<NonNullable<T>>, fn: FunctionDef | string, args: readonly Renderable[]): ExprOf<T> {
  return new Call(type, fn, args) as never;
}

/** 固定的 SQL 文本，例如 NULL 或 CURRENT_DATE。 */
export function raw<T>(type: SqlType<NonNullable<T>>, sql: string): ExprOf<T> {
  return expression<T>(type, (w) => w.sql(sql));
}

// ---------- 排序 ----------

/** ORDER BY 的一项。 */
export class Order implements Renderable {
  constructor(
    readonly expr: Expr<unknown>,
    readonly descending: boolean,
    readonly nullsPlacement: boolean | undefined,
  ) {}

  /** NULL 排在前面。 */
  nullsFirst(): Order {
    return new Order(this.expr, this.descending, true);
  }

  /** NULL 排在后面。 */
  nullsLast(): Order {
    return new Order(this.expr, this.descending, false);
  }

  /** 由方言写出这一项，因为 NULL 位置的写法因库而异。 */
  render(w: SqlWriter): void {
    w.dialect.orderItem(w, this.expr, this.descending, this.nullsPlacement);
  }
}

/** 用户写的排序项：Order，或表示升序的表达式。 */
export type OrderItem = Order | Expr<unknown>;

/** 把排序项规整成 Order；表达式视为升序。 */
export function toOrder(item: OrderItem): Order {
  return item instanceof Order ? item : item.asc();
}

// ---------- 表达式节点 ----------

/** 绑定参数；写入器内联取值且方言有对应字面量时写成字面量。 */
export class Param<T> extends ExprNode<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly value: T,
  ) {
    super(type);
  }

  /** 绑定参数，或在内联时写成字面量。 */
  render(w: SqlWriter): void {
    const type = this.type as SqlType<unknown>;
    if (w.inlining && w.dialect.hasLiteral(this.value, type)) w.literal(this.value, type);
    else w.bind(this.value, type);
  }
}

/** 直接写进 SQL 的字面量，不绑定参数。 */
export class Literal<T> extends ExprNode<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly value: T,
  ) {
    super(type);
  }

  /** 写出字面量。 */
  render(w: SqlWriter): void {
    w.literal(this.value, this.type as SqlType<unknown>);
  }
}

/** 写出某个数据源出现位置上的列，使用语句为它分配的别名。 */
export interface ColumnOwner {
  renderColumn(w: SqlWriter, column: SourceColumn): void;
}

/** 数据源提供的一列：属性名和类型。 */
export interface SourceColumn {
  readonly name: string;
  readonly type: SqlType<unknown>;
}

/** 某个数据源的列引用；查询回调里按字段类型给出列接口（`ColText`、`ColDecimal` 等，见 typed.ts）。 */
export class Column<T> extends ExprNode<T> {
  constructor(
    readonly owner: ColumnOwner,
    readonly column: SourceColumn,
  ) {
    super(column.type as SqlType<NonNullable<T>>);
  }

  /** 由所属数据源写出带别名的列。 */
  render(w: SqlWriter): void {
    this.owner.renderColumn(w, this.column);
  }

  /** 列标签是属性名。 */
  override get label(): string {
    return this.column.name;
  }
}

/** 带列标签的包装，渲染时与内部表达式相同。 */
class Labeled<T> extends ExprNode<T> {
  readonly #label: string;

  constructor(
    readonly inner: Expr<T>,
    label: string,
  ) {
    super(inner.type);
    if (label.trim() === "") throw new TypeError("A label must not be blank");
    this.#label = label;
  }

  /** 与内部表达式相同，标签只影响投影。 */
  render(w: SqlWriter): void {
    w.render(this.inner);
  }

  /** 显式指定的标签。 */
  override get label(): string {
    return this.#label;
  }
}

/** 函数调用：{@link FunctionDef} 按方言渲染，字符串名则直接写成 NAME(args)。 */
class Call<T> extends ExprNode<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly fn: FunctionDef | string,
    readonly args: readonly Renderable[],
  ) {
    super(type);
  }

  /** 按方言渲染函数定义；字符串名先找方言按名字登记的写法，没有时写成 NAME(args)。 */
  render(w: SqlWriter): void {
    if (typeof this.fn !== "string") {
      this.fn.render(w, this.args);
      return;
    }
    const custom = w.dialect.callRenderer(this.fn);
    if (custom !== undefined) custom(w, this.args);
    else FunctionDef.call(w, this.fn, this.args);
  }
}

/** 原生 SQL 模板的参数：节点原位渲染，其他值绑定（内联时写成字面量），数组展开成逗号分隔的多个参数。 */
export type TemplateArg = Renderable | TemplateValue | readonly TemplateValue[];

/** 原生 SQL 模板里绑定的值。 */
export type TemplateValue = string | number | bigint | boolean | Uint8Array | Decimal | CalendarDate | CalendarDateTime;

/** 渲染 SQL 模板：交错写出文本片段和参数。 */
export function renderTemplate(w: SqlWriter, parts: readonly string[], args: readonly TemplateArg[]): void {
  parts.forEach((part, i) => {
    w.sql(part);
    if (i < args.length) {
      const arg = args[i];
      if (arg == null) throw new TypeError(`Template argument ${i} is ${arg}; write NULL in the template instead`);
      if (Array.isArray(arg)) {
        if (arg.length === 0) throw new TypeError(`Template argument ${i} is an empty array`);
        (arg as readonly TemplateValue[]).forEach((v, j) => {
          if (j > 0) w.sql(", ");
          w.render(param(typeOf(v), v));
        });
      } else if (isRenderable(arg)) w.render(arg);
      else w.render(param(typeOf(arg), arg));
    }
  });
}

/** 模板参数是否是要原位渲染的节点；小数、日期等值对象不是。 */
function isRenderable(arg: unknown): arg is Renderable {
  return typeof arg === "object" && arg !== null && !(arg instanceof Uint8Array) && typeof (arg as Partial<Renderable>).render === "function";
}

/** 算术表达式，整体加括号：`(a + b + ...)`。 */
export class Arithmetic<T> extends ExprNode<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly operator: string,
    readonly operands: readonly Expr<unknown>[],
  ) {
    super(type);
  }

  /** 写出带括号、用运算符连接的操作数，由方言决定（SQLite 的除法先转成浮点）。 */
  render(w: SqlWriter): void {
    w.dialect.arithmetic(w, this.operator, this.operands);
  }
}

/** CAST(inner AS 目标类型)，类型名由方言决定。 */
class CastExpr extends ExprNode<unknown> {
  constructor(
    readonly inner: Expr<unknown>,
    readonly target: CastType<unknown>,
  ) {
    super(target.type as SqlType<{}>);
  }

  /** 写出 CAST(内部表达式 AS 目标类型)，由方言决定（SQLite 的日期写成 date(...)）。 */
  render(w: SqlWriter): void {
    w.dialect.cast(w, this.inner, this.target);
  }
}

/** 标量子查询：必须恰好选取一列，结果可能为 NULL。 */
export class Scalar<T> extends ExprNode<T | null> {
  constructor(readonly query: Query<T>) {
    const types = query[COLUMN_TYPES];
    if (types.length !== 1) throw new Error(`A scalar subquery must select exactly one column, this one selects ${types.length}`);
    super(types[0] as SqlType<NonNullable<T>>);
  }

  /** 把子查询写在括号里。 */
  render(w: SqlWriter): void {
    w.sql("(");
    this.query[SUBQUERY](w);
    w.sql(")");
  }
}

/** `ANY (子查询)` 或 `ALL (子查询)`，只能放在比较的右侧；子查询只选一列，类型取自那一列。 */
export function quantified<T>(quantifier: "ANY" | "ALL", query: Query<T>): ExprOf<T> {
  const types = query[COLUMN_TYPES];
  if (types.length !== 1) throw new Error(`${quantifier} needs a subquery of one column, this one selects ${types.length}`);
  return new Quantified(types[0] as SqlType<NonNullable<T>>, quantifier, query) as never;
}

/** `ANY (子查询)` 或 `ALL (子查询)`，只能放在比较的右侧；比较交给方言渲染（SQLite 改写成 EXISTS）。 */
export class Quantified<T> extends ExprNode<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly quantifier: "ANY" | "ALL",
    readonly query: Query<T>,
  ) {
    super(type);
  }

  /** 单独渲染时写成 `ANY (子查询)`。 */
  render(w: SqlWriter): void {
    w.sql(this.quantifier).sql(" (");
    this.query[SUBQUERY](w);
    w.sql(")");
  }
}

// ---------- 窗口与聚合 ----------

/** node 是否是加了 OVER (...) 的窗口函数；QUALIFY 用它找出条件里的窗口函数。 */
export function isWindow(node: Renderable): boolean {
  return node instanceof Over;
}

/** 带窗口的函数：`fn OVER (window)`。 */
export class Over<T> extends ExprNode<T> {
  constructor(
    readonly fn: Expr<T>,
    readonly window: Window,
  ) {
    super(fn.type);
  }

  /** 写出 `fn OVER (window)`；方言不能把这个聚合当窗口函数时报错。 */
  render(w: SqlWriter): void {
    if (this.fn instanceof Aggregate && !w.dialect.windowable(this.fn.name)) throw new Error(`${this.fn.name}(...) OVER (...) is not supported on ${w.dialect.name}`);
    w.render(this.fn).sql(" OVER (").render(this.window).sql(")");
  }
}

/** 聚合函数，例如 SUM(x)；可以直接使用，加过滤条件（{@link filter}），或通过 {@link over} 作为窗口函数。 */
export class Aggregate<T> extends ExprNode<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly name: string,
    readonly distinct: boolean,
    readonly args: readonly Expr<unknown>[],
    /** 只聚合满足它的行；没有时不过滤。 */
    readonly condition?: Cond,
  ) {
    super(type);
  }

  /** 只聚合满足块里条件的行，见 {@link Expr.filter}。 */
  override filter(block: () => void): ExprOf<T> {
    return this.filtered(collect(block)) as never;
  }

  /** 只聚合满足 cond 的行，多次调用按 AND 合并；供一对多关系的 groupJoin 把关系上的筛选写进聚合。 */
  filtered(cond: Cond): Aggregate<T> {
    if (cond.isEmpty) return this;
    return new Aggregate(this.type, this.name, this.distinct, this.args, this.condition ? and(this.condition, cond) : cond);
  }

  /** 把聚合作为窗口函数使用。 */
  override over(window: (w: Window) => Window): ExprOf<T> {
    return new Over(this, window(Window.EMPTY)) as never;
  }

  /**
   * 写出 NAME([DISTINCT ]args)；没有参数时是 NAME(*)，有过滤条件时参数换成 CASE WHEN 条件 THEN 参数 END。
   * 方言按名字登记了写法时（不带 DISTINCT）交给它，参数同样先套上过滤条件。
   */
  render(w: SqlWriter): void {
    const custom = this.distinct ? undefined : w.dialect.callRenderer(this.name);
    if (custom !== undefined) {
      const condition = this.condition;
      custom(w, condition === undefined ? this.args : this.args.map((a) => ({ render: (x: SqlWriter) => void x.sql("CASE WHEN ").render(condition).sql(" THEN ").render(a).sql(" END") })));
      return;
    }
    w.sql(this.name + "(");
    if (this.distinct) w.sql("DISTINCT ");
    if (this.condition) {
      w.sql("CASE WHEN ").render(this.condition).sql(" THEN ");
      if (this.args.length === 0) w.sql("1");
      else w.join(this.args, ", ");
      w.sql(" END");
    } else if (this.args.length === 0) w.sql("*");
    else w.join(this.args, ", ");
    w.sql(")");
  }
}

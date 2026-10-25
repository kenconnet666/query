import type { CalendarDate, CalendarDateTime } from "@internationalized/date";
import type { Decimal } from "decimal.js";
import type { ColumnOwner, Expr, KeepNull, OrderItem, SourceColumn } from "./expr.js";

/**
 * 按逻辑 SQL 类型分的表达式与列接口，与 Java 的 `ExprText`、`ColText` 等同名：类型接口表示库里怎么存，
 * 泛型参数是字段的 TS 类型。表达式和列在运行时都是 `ExprNode`，接口只决定能点出哪些方法，
 * 所以文本列点不出 `sum()`，以文本存的日期点不出 `addDays()`。
 *
 * | 字段类型 | 表达式接口 | 列接口 |
 * | --- | --- | --- |
 * | `string`（含字面量联合、字符串枚举） | `ExprText` | `ColText` |
 * | `number`（整数和浮点数） | `ExprNumber` | `ColNumber` |
 * | `bigint` | `ExprBigint` | `ColBigint` |
 * | `Decimal` | `ExprDecimal` | `ColDecimal` |
 * | `CalendarDate` | `ExprDate` | `ColDate` |
 * | `CalendarDateTime` | `ExprTimestamp` | `ColTimestamp` |
 * | 其他（布尔、字节、JSON） | `Expr` | `Col` |
 *
 * 以另一种类型存的字段写成 `Stored<字段类型, 存储类型>`，按存储类型选接口。
 */

declare const STORED: unique symbol;

/**
 * 以另一种类型存储的字段：J 是字段的值，D 是库里存的值。例如 SAP 以 'yyyyMMdd' 文本存的日期
 * `@column("ERDAT", sapDate) createdOn!: Stored<CalendarDate, string> | null`：列是 `ColText`，比较时传 `CalendarDate`
 * （经转换器绑定成文本），文本函数作用在库里的文本上，日期函数点不出来。标记只存在于类型里，值就是 J。
 */
export type Stored<J, D> = J & { readonly [STORED]?: D };

/** 值类型属于哪一类逻辑类型；`Stored` 字段取存储类型的那一类。 */
export type Family = "text" | "number" | "bigint" | "decimal" | "date" | "timestamp" | "boolean" | "bytes" | "other";

/** 值类型 V（不含 null）所属的逻辑类型；时间戳先于日期判断，两者的类各自带私有标记，互不兼容。 */
export type FamilyOf<V> = [V] extends [never]
  ? "other"
  : typeof STORED extends keyof V
    ? V extends { readonly [STORED]?: infer D }
      ? FamilyOf<D>
      : "other"
    : [V] extends [string]
      ? "text"
      : [V] extends [number]
        ? "number"
        : [V] extends [bigint]
          ? "bigint"
          : [V] extends [Decimal]
            ? "decimal"
            : [V] extends [CalendarDateTime]
              ? "timestamp"
              : [V] extends [CalendarDate]
                ? "date"
                : [V] extends [boolean]
                  ? "boolean"
                  : [V] extends [Uint8Array]
                    ? "bytes"
                    : "other";

/** 各类逻辑类型的表达式接口。 */
interface ExprFamilies<T> {
  text: ExprText<T>;
  number: ExprNumber<T>;
  bigint: ExprBigint<T>;
  decimal: ExprDecimal<T>;
  date: ExprDate<T>;
  timestamp: ExprTimestamp<T>;
  boolean: Expr<T>;
  bytes: Expr<T>;
  other: Expr<T>;
}

/** 各类逻辑类型的列接口。 */
interface ColFamilies<T> {
  text: ColText<T>;
  number: ColNumber<T>;
  bigint: ColBigint<T>;
  decimal: ColDecimal<T>;
  date: ColDate<T>;
  timestamp: ColTimestamp<T>;
  boolean: Col<T>;
  bytes: Col<T>;
  other: Col<T>;
}

/** 值类型为 T 的表达式接口：`ExprOf<string | null>` 是 `ExprText<string | null>`。函数的结果都用它，所以结果能接着点。 */
export type ExprOf<T> = ExprFamilies<T>[FamilyOf<NonNullable<T>>];

/** 字段类型为 T 的列接口：查询回调里 `l.delivery` 的类型。 */
export type ColOf<T> = ColFamilies<T>[FamilyOf<NonNullable<T>>];

/** 可以参与算术的值。 */
export type NumericValue = number | bigint | Decimal;

/** 两个数值运算的结果类：小数 > 大整数 > 数字，与 Java 的提升规则一致（数字和大整数相加得到大整数）。 */
export type Wider<A, B> = [A] extends [Decimal] ? Decimal : [B] extends [Decimal] ? Decimal : [A] extends [bigint] ? bigint : [B] extends [bigint] ? bigint : number;

/** 文本的位置或长度：数字写成字面量，表达式则现场计算，例如来自 `locate`。 */
export type Position = number | Expr<number> | Expr<number | null>;

// ---------- 文本 ----------

/** 文本表达式。计算出的文本是 `ExprText<string>`，即使列本身以文本存别的类型（`Stored`）。 */
export interface ExprText<T> extends Expr<T> {
  /** LIKE；模式原样使用，% 和 _ 是通配符。skipEmpty 下模式为空时没有条件。 */
  like(pattern: string | null | undefined): void;
  /** NOT LIKE；skipEmpty 下模式为空时没有条件。 */
  notLike(pattern: string | null | undefined): void;
  /** 包含 text：`LIKE '%text%'`，text 里的 % 和 _ 按普通字符匹配。skipEmpty 下 text 为空时没有条件。 */
  contains(text: string | null | undefined): void;
  /** 以 text 开头，规则同 {@link contains}。 */
  startsWith(text: string | null | undefined): void;
  /** 以 text 结尾，规则同 {@link contains}。 */
  endsWith(text: string | null | undefined): void;
  /** UPPER：转大写。 */
  upper(): ExprText<KeepNull<T, string>>;
  /** LOWER：转小写。 */
  lower(): ExprText<KeepNull<T, string>>;
  /** TRIM：去掉两端空白。 */
  trim(): ExprText<KeepNull<T, string>>;
  /** SUBSTRING(e, start[, length])，从 1 开始计数。位置用字面量，调用才能作为 GROUP BY 的键。 */
  substring(start: Position, length?: Position): ExprText<KeepNull<T, string>>;
  /** LEFT：取左边 `length` 个字符。 */
  left(length: Position): ExprText<KeepNull<T, string>>;
  /** RIGHT：取右边 `length` 个字符。 */
  right(length: Position): ExprText<KeepNull<T, string>>;
  /** REPLACE：把 `search` 全部替换为 `replacement`。 */
  replace(search: string, replacement: string): ExprText<KeepNull<T, string>>;
  /** `search` 第一次出现的位置，从 1 开始计数；没有出现时为 0。 */
  locate(search: string | Expr<string> | Expr<string | null>): ExprNumber<KeepNull<T, number>>;
  /** 依次拼接：`l.unit.concat("-", l.material)`；各方言写法不同（MySQL 是 CONCAT）。 */
  concat(...parts: (string | Expr<string>)[]): ExprText<KeepNull<T, string>>;
  concat(...parts: (string | Expr<string> | Expr<string | null>)[]): ExprText<string | null>;
  /** 左边用 pad 补足到 length 个字符，例如物料号补零 `m.material.lpad(18, "0")`；比 length 长时截断成前 length 个字符。 */
  lpad(length: number, pad: string): ExprText<KeepNull<T, string>>;
  /** 右边用 pad 补足到 length 个字符；比 length 长时截断成前 length 个字符。 */
  rpad(length: number, pad: string): ExprText<KeepNull<T, string>>;
  /**
   * 把组内的文本用 separator 拼接，可以指定拼接顺序：`l.delivery.stringAgg(",", l.delivery.asc())`。
   * HANA、PostgreSQL 写成 STRING_AGG，MySQL 写成 GROUP_CONCAT（结果长度受 group_concat_max_len 限制，默认 1024 字节）。
   */
  stringAgg(separator: string, ...order: OrderItem[]): ExprText<string | null>;
}

// ---------- 数值 ----------

/**
 * 数值表达式的共同方法，B 是这一类的值（number、bigint、Decimal）。加减乘按"宽"的一方提升（{@link Wider}）；
 * `a.plus(b).plus(c)` 并成一个括号 `(a + b + c)`。
 */
export interface ExprNumeric<T, B extends NumericValue> extends Expr<T> {
  /** 加上常量。 */
  plus<U extends NumericValue>(value: U): ExprOf<KeepNull<T, Wider<B, U>>>;
  /** 加上另一个数值表达式。 */
  plus<U extends NumericValue | null>(other: Expr<U>): ExprOf<KeepNull<T | U, Wider<B, NonNullable<U>>>>;
  /** 减去常量。 */
  minus<U extends NumericValue>(value: U): ExprOf<KeepNull<T, Wider<B, U>>>;
  /** 减去另一个数值表达式。 */
  minus<U extends NumericValue | null>(other: Expr<U>): ExprOf<KeepNull<T | U, Wider<B, NonNullable<U>>>>;
  /** 乘以常量。 */
  times<U extends NumericValue>(value: U): ExprOf<KeepNull<T, Wider<B, U>>>;
  /** 乘以另一个数值表达式。 */
  times<U extends NumericValue | null>(other: Expr<U>): ExprOf<KeepNull<T | U, Wider<B, NonNullable<U>>>>;
  /** 余数，符号与被除数相同：`-7 mod 3` 是 -1。除数为 0 时 HANA、PostgreSQL 报错，MySQL 得到 null。 */
  mod(divisor: NumericValue | Expr<NumericValue | null>): ExprOf<KeepNull<T, B>>;
  /** 取负：`-(e)`；加括号是为了避免负的字面量连成 `--` 变成注释。 */
  negate(): ExprOf<KeepNull<T, B>>;
  /** ABS：绝对值。 */
  abs(): ExprOf<KeepNull<T, B>>;
  /** FLOOR：向下取整。 */
  floor(): ExprOf<KeepNull<T, B>>;
  /** CEIL：向上取整。 */
  ceil(): ExprOf<KeepNull<T, B>>;
  /** ROUND：四舍五入到 `scale` 位小数。 */
  round(scale: number): ExprOf<KeepNull<T, B>>;
  /** exponent 次方，结果是浮点数。 */
  power(exponent: number | Expr<NumericValue | null>): ExprNumber<KeepNull<T, number>>;
  /** 平方根，结果是浮点数；负数时报错。 */
  sqrt(): ExprNumber<KeepNull<T, number>>;
  /** 符号：负数为 -1，零为 0，正数为 1。 */
  sign(): ExprNumber<KeepNull<T, number>>;
  /** SUM：求和，没有行时为 NULL。 */
  sum(): ExprOf<B | null>;
  /** SUM(DISTINCT e)：相同的值只加一次。 */
  sumDistinct(): ExprOf<B | null>;
  /** AVG：平均值，以精确的小数返回。 */
  avg(): ExprDecimal<Decimal | null>;
  /** AVG(DISTINCT e)，以精确的小数返回。 */
  avgDistinct(): ExprDecimal<Decimal | null>;
  /**
   * 样本标准差 STDDEV_SAMP，只有一行时为 NULL。参数先转成 DOUBLE：HANA 对整数按定点小数计算，
   * 只保留 6 位小数，转换后各库结果一致。
   */
  stddevSamp(): ExprNumber<number | null>;
  /** 总体标准差 STDDEV_POP；参数先转成 DOUBLE，见 {@link stddevSamp}。 */
  stddevPop(): ExprNumber<number | null>;
  /** 样本方差 VAR_SAMP，只有一行时为 NULL；参数先转成 DOUBLE，见 {@link stddevSamp}。 */
  varSamp(): ExprNumber<number | null>;
  /** 总体方差 VAR_POP；参数先转成 DOUBLE，见 {@link stddevSamp}。 */
  varPop(): ExprNumber<number | null>;
}

/**
 * `number` 的表达式：整数和浮点数在 TS 里是同一个类型，所以 `divide` 在这里，被除数是整数列时运行时报错
 * （整数相除各库有的取整、有的不取整，先 `cast(CastType.double())` 或 `cast(CastType.decimal(...))`）。
 */
export interface ExprNumber<T> extends ExprNumeric<T, number> {
  /** 除以常量。 */
  divide(value: number): ExprNumber<KeepNull<T, number>>;
  divide(value: Decimal): ExprDecimal<KeepNull<T, Decimal>>;
  /** 除以另一个表达式。 */
  divide<U extends number | null>(other: Expr<U>): ExprNumber<KeepNull<T | U, number>>;
  divide<U extends Decimal | null>(other: Expr<U>): ExprDecimal<KeepNull<T | U, Decimal>>;
}

/** `bigint` 的表达式；没有除法，整数相除先 `cast`。 */
export interface ExprBigint<T> extends ExprNumeric<T, bigint> {}

/** 小数（`Decimal`）的表达式。 */
export interface ExprDecimal<T> extends ExprNumeric<T, Decimal> {
  /** 除以常量。 */
  divide(value: NumericValue): ExprDecimal<KeepNull<T, Decimal>>;
  /** 除以另一个数值表达式。 */
  divide<U extends NumericValue | null>(other: Expr<U>): ExprDecimal<KeepNull<T | U, Decimal>>;
}

// ---------- 日期与时间戳 ----------

/** 日期（`CalendarDate`）的表达式；日期加减和相隔天数只在日期上，时间戳在 PostgreSQL 上加整数会失败。 */
export interface ExprDate<T> extends Expr<T> {
  /** 年份：`groupBy((l) => [l.createdOn.year(), l.createdOn.month()])`。 */
  year(): ExprNumber<KeepNull<T, number>>;
  /** 月份，1 到 12。 */
  month(): ExprNumber<KeepNull<T, number>>;
  /** 月中的第几天，1 到 31。 */
  day(): ExprNumber<KeepNull<T, number>>;
  /** 加上 days 天，可以为负。 */
  addDays(days: number): ExprDate<KeepNull<T, CalendarDate>>;
  /** 加上 months 个月；目标月没有那一天时取月末，例如 1 月 31 日加一个月是 2 月末。 */
  addMonths(months: number): ExprDate<KeepNull<T, CalendarDate>>;
  /** 加上 years 年；2 月 29 日加一年是 2 月 28 日。 */
  addYears(years: number): ExprDate<KeepNull<T, CalendarDate>>;
  /** 当月第一天，用于按月汇总时得到一个日期：`groupBy((l) => l.createdOn.monthStart())`。 */
  monthStart(): ExprDate<KeepNull<T, CalendarDate>>;
  /** 从本日期到 to 相隔的天数，to 较早时为负。 */
  daysBetween(to: CalendarDate): ExprNumber<KeepNull<T, number>>;
  daysBetween<U extends CalendarDate | null>(to: Expr<U>): ExprNumber<KeepNull<T | U, number>>;
}

/** 时间戳（`CalendarDateTime`）的表达式。 */
export interface ExprTimestamp<T> extends Expr<T> {
  /** 年份。 */
  year(): ExprNumber<KeepNull<T, number>>;
  /** 月份，1 到 12。 */
  month(): ExprNumber<KeepNull<T, number>>;
  /** 月中的第几天，1 到 31。 */
  day(): ExprNumber<KeepNull<T, number>>;
  /** 小时，0 到 23。 */
  hour(): ExprNumber<KeepNull<T, number>>;
  /** 分钟，0 到 59。 */
  minute(): ExprNumber<KeepNull<T, number>>;
  /** 秒，0 到 59，不足一秒的部分舍去。 */
  second(): ExprNumber<KeepNull<T, number>>;
  /** 加上 hours 小时，可以为负。 */
  addHours(hours: number): ExprTimestamp<KeepNull<T, CalendarDateTime>>;
  /** 加上 minutes 分钟，可以为负。 */
  addMinutes(minutes: number): ExprTimestamp<KeepNull<T, CalendarDateTime>>;
  /** 加上 seconds 秒，可以为负。 */
  addSeconds(seconds: number): ExprTimestamp<KeepNull<T, CalendarDateTime>>;
  /** 从本时间戳到 to 相隔的整秒数（不足一秒的部分舍去），to 较早时为负。 */
  secondsBetween(to: CalendarDateTime): ExprNumber<KeepNull<T, number>>;
  secondsBetween<U extends CalendarDateTime | null>(to: Expr<U>): ExprNumber<KeepNull<T | U, number>>;
}

// ---------- 列 ----------

/** 数据源的一列：查询回调里实体的字段。各类型的列接口同时是那一类的表达式。 */
export interface Col<T> extends Expr<T> {
  /** 列所属的那次数据源出现。 */
  readonly owner: ColumnOwner;
  /** 列的属性名和类型。 */
  readonly column: SourceColumn;
}

/** 文本列。 */
export interface ColText<T> extends Col<T>, ExprText<T> {}
/** `number` 列（整数和浮点数）。 */
export interface ColNumber<T> extends Col<T>, ExprNumber<T> {}
/** `bigint` 列。 */
export interface ColBigint<T> extends Col<T>, ExprBigint<T> {}
/** 小数列。 */
export interface ColDecimal<T> extends Col<T>, ExprDecimal<T> {}
/** 日期列。 */
export interface ColDate<T> extends Col<T>, ExprDate<T> {}
/** 时间戳列。 */
export interface ColTimestamp<T> extends Col<T>, ExprTimestamp<T> {}

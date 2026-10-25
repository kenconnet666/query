import { FunctionDef } from "./dialect.js";

/**
 * 核心函数的定义：写法不同的方言按这些定义登记自己的写法（`register(LOCATE, ...)`），
 * 表达式的实例方法（`e.locate(...)`、`d.addDays(n)` 等）调用它们。
 */

/** `a || b || ...`；没有该运算符的方言（MySQL）注册自己的渲染。 */
export const CONCAT = FunctionDef.of("CONCAT", (w, args) => w.sql("(").join(args, " || ").sql(")"));

/** `LOCATE(text, search)`；PostgreSQL 和 MySQL 注册自己的写法。 */
export const LOCATE = FunctionDef.of("LOCATE");

/** 当前日期；SQLite 写成本地时区的 `date('now', 'localtime')`。 */
export const CURRENT_DATE = FunctionDef.of("CURRENT_DATE", (w) => void w.sql("CURRENT_DATE"));

/** 当前时间戳（会话时区）；SQLite 写成本地时区的 `strftime(..., 'now', 'localtime')`。 */
export const CURRENT_TIMESTAMP = FunctionDef.of("CURRENT_TIMESTAMP", (w) => void w.sql("CURRENT_TIMESTAMP"));

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

/** `HOUR(t)`；PostgreSQL 写成 EXTRACT，下面的 MINUTE 相同。 */
export const HOUR = FunctionDef.of("HOUR");

/** `MINUTE(t)`。 */
export const MINUTE = FunctionDef.of("MINUTE");

/** 秒的整数部分：HANA 的 SECOND 带小数，写成 `CAST(FLOOR(SECOND(t)) AS INTEGER)`；PostgreSQL、MySQL 注册自己的写法。 */
export const SECOND = FunctionDef.of("SECOND", (w, args) => void w.sql("CAST(FLOOR(SECOND(").render(args[0]!).sql(")) AS INTEGER)"));

/** `ADD_SECONDS(t, n)`；PostgreSQL 加 INTERVAL，MySQL 写成 TIMESTAMPADD。加小时、分钟也换算成秒用它。 */
export const ADD_SECONDS = FunctionDef.of("ADD_SECONDS");

/** 当月第一天：`ADD_DAYS(d, 1 - DAYOFMONTH(d))`；PostgreSQL 写成 DATE_TRUNC，MySQL 写成 DATE_SUB。 */
export const MONTH_START = FunctionDef.of("MONTH_START", (w, args) => void w.sql("ADD_DAYS(").render(args[0]!).sql(", 1 - DAYOFMONTH(").render(args[0]!).sql("))"));

/** `STRING_AGG(value, separator ORDER BY ...)`，参数依次是值、分隔符和排序项；MySQL 写成 GROUP_CONCAT。 */
export const STRING_AGG = FunctionDef.of("STRING_AGG", (w, args) => {
  w.sql("STRING_AGG(").render(args[0]!).sql(", ").render(args[1]!);
  if (args.length > 2) w.sql(" ORDER BY ").join(args.slice(2), ", ");
  w.sql(")");
}).aggregating();

import {
  addCondition,
  aliasOf,
  Bound,
  call,
  condition,
  Cte,
  double,
  ExprNode,
  expression,
  FunctionDef,
  lit,
  param,
  skips,
  text as textType,
  type Clause,
  type Cond,
  type Expr,
  type ExprNumber,
  type ExprOf,
  type ExprText,
  type KeepNull,
  type Lock,
  type Query,
  type Renderable,
  type SqlWriter,
} from "@zerodep-dev/query-core";
import { Hint, MysqlDialect } from "./dialect.js";

/**
 * MySQL 专有的函数和子句；在其他方言上拒绝渲染。有主操作数的函数经由视图 {@link mysql} 点出来：
 * `mysql(e.onDay).dateFormat("%Y%m")`、`mysql(t.payload).jsonText("$.id")`。
 */

const DATE_FORMAT = FunctionDef.only(MysqlDialect, "DATE_FORMAT");

// ---------- 视图 ----------

/** 文本表达式上 MySQL 专有的方法。 */
export function mysql<T>(e: ExprText<T>): ViewText<T>;
/** 任意表达式上 MySQL 专有的方法。 */
export function mysql<T>(e: Expr<T>): ViewAny<T>;
export function mysql<T>(e: Expr<T>): ViewAny<T> {
  return e.type.kind === "text" ? new ViewText(e) : new ViewAny(e);
}

/** 任意表达式上 MySQL 专有的方法，由 {@link mysql} 得到。 */
export class ViewAny<T> {
  constructor(protected readonly e: Expr<T>) {}

  /** DATE_FORMAT(e, format)，例如 `mysql(day).dateFormat("%Y%m%d")`。 */
  dateFormat(format: string): ExprText<KeepNull<T, string>> {
    return call(DATE_FORMAT, textType, this.e as Expr<unknown>, lit(format)) as never;
  }
}

/** 文本表达式上 MySQL 专有的方法；JSON 列也按文本声明。 */
export class ViewText<T> extends ViewAny<T> {
  /**
   * JSON 文档里一个路径上的标量文本：`JSON_UNQUOTE(JSON_EXTRACT(doc, ?))`，路径如 `"$.order.id"` 绑定成参数；
   * 路径不存在、是 JSON null、对象或数组时为 NULL。
   */
  jsonText(path: string): ExprText<string | null> {
    const e = this.e;
    const p = utf8(path);
    return expression<string | null>(textType, (w) => {
      requireMysql(w);
      w.sql("CASE WHEN JSON_TYPE(JSON_EXTRACT(").render(e).sql(", ").render(p).sql(")) IN ('OBJECT', 'ARRAY', 'NULL') THEN NULL ELSE JSON_UNQUOTE(JSON_EXTRACT(").render(e).sql(", ").render(p).sql(")) END");
    }) as never;
  }

  /** JSON 文档包含 json 这段内容 `JSON_CONTAINS(doc, CAST(? AS JSON))`，写进当前的条件块；skipEmpty 下 json 为空时没有条件。 */
  jsonContains(json: string | null | undefined): void {
    if (skips(json)) return;
    const e = this.e;
    const value = utf8(json as string);
    addCondition(mysqlCond((w) => w.sql("JSON_CONTAINS(").render(e).sql(", CAST(").render(value).sql(" AS JSON))")));
  }
}

/** 优化器提示 MAX_EXECUTION_TIME：语句超过给定毫秒数后中止。与其他提示合并进同一个注释。 */
export function maxExecutionTime(milliseconds: number): Clause {
  if (!Number.isInteger(milliseconds) || milliseconds < 1) throw new RangeError("maxExecutionTime needs a positive number of milliseconds");
  return new Hint((w) => void w.sql(`MAX_EXECUTION_TIME(${milliseconds})`));
}

/** 优化器提示 `INDEX(别名 索引, ...)`：只考虑这些索引；table 是查询的表参数。 */
export function index(table: object, ...indexes: string[]): Clause {
  return indexHint("INDEX", table, indexes);
}

/** 优化器提示 `NO_INDEX(别名 索引, ...)`：不用这些索引；不写索引时不用这张表的任何索引。 */
export function noIndex(table: object, ...indexes: string[]): Clause {
  return indexHint("NO_INDEX", table, indexes);
}

/** `NAME(别名 [索引, ...])`。 */
function indexHint(name: string, table: object, indexes: readonly string[]): Clause {
  return new Hint((w) => {
    w.sql(name + "(").alias(aliasOf(w, table));
    indexes.forEach((idx, i) => void w.sql(i === 0 ? " " : ", ").identifier(idx));
    w.sql(")");
  });
}

const GREATEST = FunctionDef.only(MysqlDialect, "GREATEST");
const LEAST = FunctionDef.only(MysqlDialect, "LEAST");

/** GREATEST / LEAST 的参数：至少两个，值按第一个参数的类型绑定。 */
function extremeArgs<T>(name: string, first: Expr<T>, rest: readonly unknown[]): Expr<unknown>[] {
  if (rest.length === 0) throw new TypeError(`${name} needs at least two arguments`);
  return [first, ...rest.map((v) => (v instanceof ExprNode ? (v as Expr<unknown>) : param(first.type, v as NonNullable<T>)))];
}

/** GREATEST(a, b, ...)：只要有一个参数为 NULL，结果就是 NULL；结果与第一个参数同一类。 */
export function greatest<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): ExprOf<T> {
  return call<T>(GREATEST, first.type, ...extremeArgs("GREATEST", first, rest));
}

/** LEAST(a, b, ...)：只要有一个参数为 NULL，结果就是 NULL。 */
export function least<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): ExprOf<T> {
  return call<T>(LEAST, first.type, ...extremeArgs("LEAST", first, rest));
}

/** 渲染时确认当前方言是 MySQL，否则报错。 */
function requireMysql(w: SqlWriter): void {
  if (!(w.dialect instanceof MysqlDialect)) throw new Error(`A MySQL-only operation cannot run on ${w.dialect.name}`);
}

// ---------- 集合运算、窗口帧、递归 CTE、加锁 ----------

/** INTERSECT ALL：两边都有的行，保留重复（取两边次数的较小值）。规则同 `q1.unionAll(q2)`。 */
export function intersectAll<R>(left: Query<R>, right: Query<R>): Query<R> {
  return left.combine("INTERSECT ALL", right);
}

/** EXCEPT ALL：左边有、右边没有的行，按次数相减。规则同 `q1.unionAll(q2)`。 */
export function exceptAll<R>(left: Query<R>, right: Query<R>): Query<R> {
  return left.combine("EXCEPT ALL", right);
}

/**
 * RANGE 窗口帧：按排序值的距离取行，排序值相同的行一起进出，例如近 7 天的滚动合计
 * `over((w) => w.orderBy(l.createdOn).frame(range(daysPreceding(6), Bound.currentRow())))`。
 * 有限的偏移要求窗口只有一个排序项；数值偏移用 `Bound.preceding(n)`。
 */
export function range(start: Bound, end: Bound): Renderable {
  const sql = `RANGE BETWEEN ${start.sql} AND ${end.sql}`;
  return {
    render: (w) => {
      requireMysql(w);
      w.sql(sql);
    },
  };
}

/** RANGE 帧的起点：排序值（日期或时间戳）之前 days 天。 */
export function daysPreceding(days: number): Bound {
  return Bound.custom(`INTERVAL ${wholeDays(days)} DAY PRECEDING`);
}

/** RANGE 帧的终点：排序值之后 days 天。 */
export function daysFollowing(days: number): Bound {
  return Bound.custom(`INTERVAL ${wholeDays(days)} DAY FOLLOWING`);
}

/** 校验天数是非负整数。 */
function wholeDays(days: number): number {
  if (!Number.isInteger(days) || days < 0) throw new RangeError("Frame offsets must be non-negative integers");
  return days;
}

/**
 * 递归 CTE：`anchor UNION ALL step(自己)`，step 里 `from(self)` 取上一轮的行，例如逐层展开：
 * `recursiveCte("steps", from(Lips).where(...).select((l) => ({ delivery: l.delivery, depth: lit(1) })), (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: s.depth.plus(1) })))`。
 * 语句里有递归 CTE 时写成 `WITH RECURSIVE`；step 必须有终止条件。
 */
export function recursiveCte<E>(name: string, anchor: Query<E>, step: (self: Cte<E>) => Query<E>): Cte<E> {
  return Cte.recursive(name, anchor, step, MysqlDialect);
}

/** 共享锁读 `FOR SHARE`：别人可以读、也可以再加共享锁，但改不了；lock 同 `forUpdate(lock)`。 */
export function forShare(lock: Lock = "wait"): Clause {
  return lockClause("FOR SHARE", lock, []);
}

/** 只锁 tables 这几张表的行：`.with((l, m) => forUpdateOf("skipLocked", l))` 写成 `FOR UPDATE OF 别名 SKIP LOCKED`。 */
export function forUpdateOf(lock: Lock, ...tables: object[]): Clause {
  if (tables.length === 0) throw new TypeError("forUpdateOf(...) needs at least one table");
  return lockClause("FOR UPDATE", lock, tables);
}

/** `FOR UPDATE / FOR SHARE [OF 别名, ...] [NOWAIT | SKIP LOCKED]`。 */
function lockClause(mode: string, lock: Lock, tables: readonly object[]): Clause {
  return {
    position: "end",
    dialect: MysqlDialect,
    render: (w) => {
      w.sql(mode);
      tables.forEach((t, i) => void w.sql(i === 0 ? " OF " : ", ").alias(aliasOf(w, t)));
      if (lock === "nowait") w.sql(" NOWAIT");
      else if (lock === "skipLocked") w.sql(" SKIP LOCKED");
    },
  };
}

// ---------- JSON ----------

/** 绑定成 utf8mb4 文本的参数 `CONVERT(? USING utf8mb4)`，与 Java 端写法一致。 */
function utf8(value: string): Expr<string> {
  const p = param(textType, value);
  return expression<string>(textType, (w) => void w.sql("CONVERT(").render(p).sql(" USING utf8mb4)"));
}

/** MySQL 专属的条件，渲染时检查方言。 */
function mysqlCond(render: (w: SqlWriter) => void): Cond {
  return condition((w) => {
    requireMysql(w);
    render(w);
  });
}

// ---------- 全文检索 ----------

/** `MATCH (...) AGAINST (? IN mode)`。 */
function fulltext(mode: string, query: string, columns: readonly Expr<string | null>[]): Cond {
  if (columns.length === 0) throw new TypeError("match(...) needs at least one column");
  const q = param(textType, query);
  return mysqlCond((w) => w.sql("MATCH (").join(columns, ", ").sql(") AGAINST (").render(q).sql(" IN " + mode + ")"));
}

/**
 * 全文检索 `MATCH (a, b) AGAINST (? IN NATURAL LANGUAGE MODE)`；列必须正好是某个 FULLTEXT 索引的列。
 * InnoDB 的全文索引在提交时才更新，同一个事务里刚插入的行搜不到。写进当前的条件块，skipEmpty 下搜索词为空时没有条件。
 */
export function match(query: string | null | undefined, ...columns: Expr<string | null>[]): void {
  if (!skips(query)) addCondition(fulltext("NATURAL LANGUAGE MODE", query as string, columns));
}

/** 布尔模式的全文检索 `AGAINST (? IN BOOLEAN MODE)`：`+必须 -排除 "短语" 前缀*`。 */
export function matchBoolean(query: string | null | undefined, ...columns: Expr<string | null>[]): void {
  if (!skips(query)) addCondition(fulltext("BOOLEAN MODE", query as string, columns));
}

/** 自然语言模式的相关度，用来排序；不能跨库比较。 */
export function matchScore(query: string, ...columns: Expr<string | null>[]): ExprNumber<number> {
  const c = fulltext("NATURAL LANGUAGE MODE", query, columns);
  return expression<number>(double, (w) => void w.render(c));
}

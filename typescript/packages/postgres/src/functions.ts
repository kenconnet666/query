import {
  addCondition,
  aliasOf,
  Bound,
  call,
  CastType,
  condition,
  Cte,
  double,
  ExprNode,
  expression,
  FunctionDef,
  integer,
  lit,
  noteAggregate,
  param,
  skips,
  text as textType,
  toOrder,
  untyped,
  type Clause,
  type Cond,
  type Expr,
  type ExprNumber,
  type ExprOf,
  type ExprText,
  type Lock,
  type NumericValue,
  type OrderItem,
  type Query,
  type Renderable,
  type SqlType,
  type SqlWriter,
} from "@zerodep-dev/query-core";
import { PostgresDialect } from "./dialect.js";

/**
 * PostgreSQL 专有的函数和子句；在其他方言上拒绝渲染。有主操作数的函数经由视图 {@link postgres} 点出来：
 * `postgres(l.unit).ilike("k%")`、`postgres(l.unit).eqAny(units)`。
 */

/** 渲染时确认当前方言是 PostgreSQL，否则报错。 */
function requirePostgres(w: SqlWriter): void {
  if (!(w.dialect instanceof PostgresDialect)) throw new Error(`A PostgreSQL-only operation cannot run on ${w.dialect.name}`);
}

/** PostgreSQL 专有的条件，渲染时检查方言。 */
function pgCond(render: (w: SqlWriter) => void): Cond {
  return condition((w) => {
    requirePostgres(w);
    render(w);
  });
}

/** DISTINCT ON (keys)：每个键只留第一行；ORDER BY 必须以相同的键开头。 */
export function distinctOn(...keys: Expr<unknown>[]): Clause {
  if (keys.length === 0) throw new TypeError("distinctOn() needs at least one key");
  return { position: "distinct", dialect: PostgresDialect, render: (w) => void w.sql("DISTINCT ON (").join(keys, ", ").sql(")") };
}

// ---------- 视图 ----------

/** 文本表达式上 PostgreSQL 专有的方法。 */
export function postgres<T>(e: ExprText<T>): ViewText<T>;
/** 任意表达式上 PostgreSQL 专有的方法。 */
export function postgres<T>(e: Expr<T>): ViewAny<T>;
export function postgres<T>(e: Expr<T>): ViewAny<T> {
  return e.type.kind === "text" ? new ViewText(e) : new ViewAny(e);
}

/** 任意表达式上 PostgreSQL 专有的方法，由 {@link postgres} 得到。 */
export class ViewAny<T> {
  constructor(protected readonly e: Expr<T>) {}

  /**
   * 与一组值中任一个相等：`e = ANY($1)`，整个数组绑定成一个参数，不像 `IN (...)` 那样随个数变长，
   * 语句可以复用执行计划，也不受参数个数上限的限制。空数组恒假；写进当前的条件块，skipEmpty 下数组为空时没有条件。
   */
  eqAny(values: readonly NonNullable<T>[] | null | undefined): void {
    if (skips(values)) return;
    const e = this.e;
    const p = param(arrayOf(e.type as SqlType<NonNullable<T>>), [...(values as NonNullable<T>[])]);
    addCondition(pgCond((w) => w.render(e).sql(" = ANY(").render(p).sql(")")));
  }
}

/** 文本表达式上 PostgreSQL 专有的方法；JSONB 列也按文本声明。 */
export class ViewText<T> extends ViewAny<T> {
  /** ILIKE：不区分大小写的 LIKE，写进当前的条件块。skipEmpty 下模式为空时没有条件。 */
  ilike(pattern: string | null | undefined): void {
    if (skips(pattern)) return;
    const e = this.e;
    const p = param(textType, pattern as string);
    addCondition(pgCond((w) => w.render(e).sql(" ILIKE ").render(p)));
  }

  /** JSONB 里一个路径上的文本 `(doc #>> CAST(ARRAY[$1, ...] AS TEXT[]))`，路径不存在或是 JSON null 时为 NULL。 */
  jsonText(...path: string[]): ExprText<string | null> {
    if (path.length === 0) throw new TypeError("jsonText(...) needs a path");
    const e = this.e;
    const keys = path.map((p) => param(textType, p));
    return expression<string | null>(textType, (w) => {
      requirePostgres(w);
      w.sql("(").render(e).sql(" #>> CAST(ARRAY[").join(keys, ", ").sql("] AS TEXT[]))");
    }) as never;
  }

  /** JSONB 包含 json 这段文档：`doc @> CAST($1 AS JSONB)`；能用 GIN 索引。写进当前的条件块。 */
  jsonContains(json: string | null | undefined): void {
    if (skips(json)) return;
    const e = this.e;
    const value = param(textType, json as string);
    addCondition(pgCond((w) => w.render(e).sql(" @> CAST(").render(value).sql(" AS JSONB)")));
  }

  /** JSONB 的顶层有这个键：`jsonb_exists(doc, $1)`（即 `?` 运算符）。写进当前的条件块。 */
  jsonHasKey(key: string | null | undefined): void {
    if (skips(key)) return;
    const e = this.e;
    const value = param(textType, key as string);
    addCondition(pgCond((w) => w.sql("jsonb_exists(").render(e).sql(", ").render(value).sql(")")));
  }
}

/** CUBE 与 GROUPING SETS 只能出现在 GROUP BY 里。 */
const CUBE = FunctionDef.only(PostgresDialect, "CUBE");
const GROUPING_SETS = FunctionDef.only(PostgresDialect, "GROUPING SETS", (w, args) => void w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

/** CUBE(keys) 作为 GROUP BY 的键：对键的每种组合输出小计。 */
export function cube(...keys: Expr<unknown>[]): Expr<unknown> {
  return call<unknown>(CUBE, untyped as SqlType<{}>, ...keys);
}

/** GROUPING SETS ((a, b), (a), ()) 作为 GROUP BY 的键：只输出列出的分组；空列表表示总计。 */
export function groupingSets(...sets: (readonly Expr<unknown>[])[]): Expr<unknown> {
  const args = sets.map((set) => ({ render: (w: SqlWriter) => void w.sql("(").join(set, ", ").sql(")") }));
  return expression<unknown>(untyped as SqlType<{}>, (w) => GROUPING_SETS.render(w, args));
}

const GREATEST = FunctionDef.only(PostgresDialect, "GREATEST");
const LEAST = FunctionDef.only(PostgresDialect, "LEAST");

/** GREATEST / LEAST 的参数：至少两个，值按第一个参数的类型绑定。 */
function extremeArgs<T>(name: string, first: Expr<T>, rest: readonly unknown[]): Expr<unknown>[] {
  if (rest.length === 0) throw new TypeError(`${name} needs at least two arguments`);
  return [first, ...rest.map((v) => (v instanceof ExprNode ? (v as Expr<unknown>) : param(first.type, v as NonNullable<T>)))];
}

/** GREATEST(a, b, ...)：跳过 NULL 参数，只有全部为 NULL 时结果才是 NULL；结果与第一个参数同一类。 */
export function greatest<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): ExprOf<T> {
  return call<T>(GREATEST, first.type, ...extremeArgs("GREATEST", first, rest));
}

/** LEAST(a, b, ...)：跳过 NULL 参数，只有全部为 NULL 时结果才是 NULL。 */
export function least<T>(first: Expr<T>, ...rest: (NonNullable<T> | Expr<T> | Expr<T | null>)[]): ExprOf<T> {
  return call<T>(LEAST, first.type, ...extremeArgs("LEAST", first, rest));
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
      requirePostgres(w);
      w.sql(sql);
    },
  };
}

/** RANGE 帧的起点：排序值（日期或时间戳）之前 days 天。 */
export function daysPreceding(days: number): Bound {
  return Bound.custom(`INTERVAL '${wholeDays(days)} days' PRECEDING`);
}

/** RANGE 帧的终点：排序值之后 days 天。 */
export function daysFollowing(days: number): Bound {
  return Bound.custom(`INTERVAL '${wholeDays(days)} days' FOLLOWING`);
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
  return Cte.recursive(name, anchor, step, PostgresDialect);
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
    dialect: PostgresDialect,
    render: (w) => {
      w.sql(mode);
      tables.forEach((t, i) => void w.sql(i === 0 ? " OF " : ", ").alias(aliasOf(w, t)));
      if (lock === "nowait") w.sql(" NOWAIT");
      else if (lock === "skipLocked") w.sql(" SKIP LOCKED");
    },
  };
}

// ---------- 统计 ----------

const PERCENTILE_CONT = FunctionDef.only(PostgresDialect, "PERCENTILE_CONT", (w, args) => void w.sql("PERCENTILE_CONT(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")")).aggregating();
const PERCENTILE_DISC = FunctionDef.only(PostgresDialect, "PERCENTILE_DISC", (w, args) => void w.sql("PERCENTILE_DISC(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")")).aggregating();
const MODE = FunctionDef.only(PostgresDialect, "MODE", (w, args) => void w.sql("MODE() WITHIN GROUP (ORDER BY ").render(args[0]!).sql(")")).aggregating();
const CORR = FunctionDef.only(PostgresDialect, "CORR").aggregating();

/** 连续百分位数 `PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY e)`，在相邻两行之间插值；p 取 0 到 1，中位数是 0.5。只能作聚合。 */
export function percentileCont(p: number, e: Expr<NumericValue | null>): ExprNumber<number | null> {
  return call<number | null>(PERCENTILE_CONT, double, lit(fraction(p)), e.cast(CastType.double()));
}

/** 离散百分位数 `PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY e)`：取排序后第一个累计比例不小于 p 的值。只能作聚合。 */
export function percentileDisc<T>(p: number, e: Expr<T>): ExprOf<T | null> {
  return call<T | null>(PERCENTILE_DISC, e.type as SqlType<NonNullable<T | null>>, lit(fraction(p)), e as Expr<unknown>);
}

/** 众数 `MODE() WITHIN GROUP (ORDER BY e)`：出现次数最多的值，并列时取最小的。只能作聚合。 */
export function mode<T>(e: Expr<T>): ExprOf<T | null> {
  return call<T | null>(MODE, e.type as SqlType<NonNullable<T | null>>, e as Expr<unknown>);
}

/** 相关系数 `CORR(y, x)`，取值 -1 到 1。 */
export function corr(y: Expr<NumericValue | null>, x: Expr<NumericValue | null>): ExprNumber<number | null> {
  return call<number | null>(CORR, double, y, x);
}

/** 校验百分位数在 0 到 1 之间。 */
function fraction(p: number): number {
  if (!(p >= 0 && p <= 1)) throw new RangeError(`A percentile must be between 0 and 1, got ${p}`);
  return p;
}

// ---------- 数组 ----------

/** 解析 PostgreSQL 一维数组的文本形式 `{a,"b c",NULL}`；已经是数组时原样返回。 */
function parseArray(raw: unknown): readonly unknown[] {
  if (Array.isArray(raw)) return raw;
  if (typeof raw !== "string" || !raw.startsWith("{") || !raw.endsWith("}")) throw new TypeError(`Cannot read ${JSON.stringify(raw)} as a PostgreSQL array`);
  const out: (string | null)[] = [];
  const body = raw.slice(1, -1);
  let i = 0;
  while (i < body.length) {
    if (body[i] === '"') {
      let value = "";
      for (i++; body[i] !== '"'; i++) value += body[i] === "\\" ? body[++i] : body[i];
      out.push(value);
      i += 2;
    } else {
      let end = body.indexOf(",", i);
      if (end < 0) end = body.length;
      const value = body.slice(i, end);
      out.push(value === "NULL" ? null : value);
      i = end + 1;
    }
  }
  return out;
}

/** 元素类型为 element 的一维数组列或参数，例如 `@column("TAGS", arrayOf(text)) tags!: string[]`；元素为 NULL 时读成 null。 */
export function arrayOf<T>(element: SqlType<T>): SqlType<(T | null)[]> {
  return Object.freeze({
    name: `${element.name}[]`,
    kind: "unknown" as const,
    encode: (values: (T | null)[]) => values.map((v) => (v === null ? null : element.encode(v))),
    decode: (raw: unknown) => parseArray(raw).map((v) => (v === null ? null : element.decode(v))),
  });
}

/** 数组列包含 values 的全部元素：`arr @> $1`，写进当前的条件块。 */
export function arrayContains<T>(array: Expr<(T | null)[] | null>, values: readonly T[]): void {
  addCondition(arrayOp(array, " @> ", values));
}

/** 数组列与 values 至少有一个共同元素：`arr && $1`，写进当前的条件块。 */
export function arrayOverlaps<T>(array: Expr<(T | null)[] | null>, values: readonly T[]): void {
  addCondition(arrayOp(array, " && ", values));
}

/** 数组的元素个数 `cardinality(arr)`，空数组为 0。 */
export function cardinality(array: Expr<unknown>): ExprNumber<number | null> {
  return call<number | null>(CARDINALITY, integer, array);
}

/** 把组内的值收集成数组 `ARRAY_AGG(e ORDER BY ...)`。 */
export function arrayAgg<T>(e: Expr<T>, ...order: OrderItem[]): Expr<(T | null)[] | null> {
  const orders = order.map(toOrder);
  return expression<(T | null)[] | null>(arrayOf(e.type) as SqlType<(T | null)[]>, (w) => {
    requirePostgres(w);
    noteAggregate(w);
    w.sql("ARRAY_AGG(").render(e);
    if (orders.length > 0) w.sql(" ORDER BY ").join(orders, ", ");
    w.sql(")");
  });
}

const CARDINALITY = FunctionDef.only(PostgresDialect, "cardinality");

/** `arr op $1`，values 绑定成同类型的数组参数。 */
function arrayOp<T>(array: Expr<(T | null)[] | null>, op: string, values: readonly T[]): Cond {
  const p = param(array.type as SqlType<(T | null)[]>, [...values]);
  return pgCond((w) => w.render(array).sql(op).render(p));
}

// ---------- JSONB ----------

/** 写入 JSONB 列的值 `CAST($1 AS JSONB)`。 */
export function jsonb(json: string): ExprText<string> {
  const value = param(textType, json);
  return expression<string>(textType, (w) => {
    requirePostgres(w);
    w.sql("CAST(").render(value).sql(" AS JSONB)");
  });
}

// ---------- 全文检索 ----------

/** 全文检索条件，写进当前的条件块；tsquery 是生成查询的函数名。 */
function textSearch(tsquery: string, config: string, document: Expr<string | null>, query: string | null | undefined): void {
  if (skips(query)) return;
  const cfg = param(textType, config);
  const q = param(textType, query as string);
  addCondition(textSearchCondition(tsquery, cfg, document, q));
}

/** `to_tsvector(配置, 文本) @@ tsquery(配置, 查询)`。 */
function textSearchCondition(tsquery: string, cfg: Expr<string>, document: Expr<string | null>, q: Expr<string>): Cond {
  return pgCond((w) => w.sql("to_tsvector(CAST(").render(cfg).sql(" AS regconfig), ").render(document).sql(") @@ " + tsquery + "(CAST(").render(cfg).sql(" AS regconfig), ").render(q).sql(")"));
}

/**
 * 全文检索 `to_tsvector(配置, 文本) @@ plainto_tsquery(配置, $1)`：query 里的词都要出现（忽略大小写、按配置做词干化）。
 * config 是文本检索配置，例如 `"simple"`、`"english"`；写进当前的条件块，skipEmpty 下 query 为空时没有条件。
 */
export function textMatches(config: string, document: Expr<string | null>, query: string | null | undefined): void {
  textSearch("plainto_tsquery", config, document, query);
}

/** 同 {@link textMatches}，但 query 用网页搜索的写法（`websearch_to_tsquery`）：引号短语、`or`、`-排除`。 */
export function webMatches(config: string, document: Expr<string | null>, query: string | null | undefined): void {
  textSearch("websearch_to_tsquery", config, document, query);
}

/** 相关度 `ts_rank(...)`，用来排序；不匹配的行也有分数，筛选要另用 {@link textMatches}。 */
export function textRank(config: string, document: Expr<string | null>, query: string): ExprNumber<number> {
  const cfg = param(textType, config);
  const q = param(textType, query);
  return expression<number>(double, (w) => {
    requirePostgres(w);
    w.sql("CAST(ts_rank(to_tsvector(CAST(").render(cfg).sql(" AS regconfig), ").render(document).sql("), plainto_tsquery(CAST(").render(cfg).sql(" AS regconfig), ").render(q).sql(")) AS DOUBLE PRECISION)");
  });
}

import type { CalendarDate } from "@internationalized/date";
import {
  addCondition,
  call,
  CastType,
  condition,
  customSource,
  Derived,
  ExprNode,
  expression,
  from,
  FunctionDef,
  integer,
  lit,
  param,
  skips,
  tableSource,
  text,
  typeOf,
  untyped,
  WindowFunction,
  date,
  double,
  type Clause,
  type Cond,
  type Expr,
  type ExprBigint,
  type ExprDecimal,
  type ExprNumber,
  type ExprOf,
  type ExprText,
  type From,
  type KeepNull,
  type NumericValue,
  type Query,
  type Ref,
  type Source,
  type SourceColumn,
  type SqlType,
  type SqlWriter,
  type Table,
} from "@zerodep-dev/query-core";
import { HanaDialect } from "./dialect.js";

/**
 * HANA 专有的函数和子句；在其他方言上拒绝渲染。有主操作数的函数经由视图 {@link hana} 点出来，
 * 结果回到核心的类型接口：`hana(l.createdOn).toVarchar("YYYYMMDD").upper()`。
 */

const TO_VARCHAR = FunctionDef.only(HanaDialect, "TO_VARCHAR");
const TO_DATE = FunctionDef.only(HanaDialect, "TO_DATE");
const TO_NVARCHAR = FunctionDef.only(HanaDialect, "TO_NVARCHAR");
const LENGTH = FunctionDef.only(HanaDialect, "LENGTH");
const TO_INTEGER = FunctionDef.only(HanaDialect, "TO_INTEGER");
const MEDIAN = FunctionDef.only(HanaDialect, "MEDIAN").aggregating();

// ---------- 视图 ----------

/** 文本表达式上 HANA 专有的方法。 */
export function hana<T>(e: ExprText<T>): ViewText<T>;
/** 数值表达式上 HANA 专有的方法。 */
export function hana<T>(e: ExprNumber<T> | ExprBigint<T> | ExprDecimal<T>): ViewNumber<T>;
/** 任意表达式上 HANA 专有的方法。 */
export function hana<T>(e: Expr<T>): ViewAny<T>;
export function hana<T>(e: Expr<T>): ViewAny<T> {
  const kind = e.type.kind;
  if (kind === "text") return new ViewText(e);
  if (kind === "integer" || kind === "bigint" || kind === "double" || kind === "decimal") return new ViewNumber(e);
  return new ViewAny(e);
}

/** 任意表达式上 HANA 专有的方法，由 {@link hana} 得到。 */
export class ViewAny<T> {
  constructor(protected readonly e: Expr<T>) {}

  /** TO_VARCHAR(e, format)，例如日期写成 'yyyyMMdd'：`hana(day).toVarchar("YYYYMMDD")`。 */
  toVarchar(format: string): ExprText<KeepNull<T, string>> {
    return call(TO_VARCHAR, text, this.e as Expr<unknown>, lit(format)) as never;
  }

  /** TO_NVARCHAR(e[, format])，{@link toVarchar} 的 Unicode 形式。 */
  toNvarchar(format?: string): ExprText<KeepNull<T, string>> {
    const args = format === undefined ? [this.e as Expr<unknown>] : [this.e as Expr<unknown>, lit(format)];
    return call(TO_NVARCHAR, text, ...args) as never;
  }
}

/** 文本表达式上 HANA 专有的方法。 */
export class ViewText<T> extends ViewAny<T> {
  /** TO_DATE(e, format)：按格式把文本解析成日期，例如 SAP 的 'yyyyMMdd' 文本 `hana(l.createdOn).toDate("YYYYMMDD").addDays(1)`。 */
  toDate(format: string): ExprOf<KeepNull<T, CalendarDate>> {
    return call<KeepNull<T, CalendarDate>>(TO_DATE, date as SqlType<NonNullable<KeepNull<T, CalendarDate>>>, this.e as Expr<unknown>, lit(format));
  }

  /** TO_INTEGER(e)，也写作 TO_INT：把文本转成整数；不是数字的文本会使查询失败。 */
  toInteger(): ExprNumber<KeepNull<T, number>> {
    return call(TO_INTEGER, integer, this.e as Expr<unknown>) as never;
  }

  /** LENGTH(e)：HANA 按 UTF-16 码元计数，所以 BMP 之外的字符算 2。 */
  length(): ExprNumber<KeepNull<T, number>> {
    return call(LENGTH, integer, this.e as Expr<unknown>) as never;
  }

  /** `e LIKE_REGEXPR pattern`，HANA 的正则表达式谓词，写进当前的条件块。skipEmpty 下模式为空时没有条件。 */
  likeRegexpr(pattern: string | null | undefined): void {
    if (skips(pattern)) return;
    const p = param(text, pattern as string);
    addCondition(
      condition((w) => {
        requireHana(w);
        w.render(this.e).sql(" LIKE_REGEXPR ").render(p);
      }),
    );
  }

  /**
   * JSON 文档里的一个标量 `JSON_VALUE(doc, 'path' RETURNING NVARCHAR(5000) ERROR ON ERROR)`，例如 `hana(t.payload).jsonValue("$.order.id")`。
   * HANA 要求路径是字面量，所以路径写在代码里，不能来自用户输入；值超过 5000 个字符时报错而不是截断。
   */
  jsonValue(path: string): ExprText<string | null> {
    return expression<string | null>(text, (w) => {
      requireHana(w);
      w.sql("JSON_VALUE(").render(this.e).sql(", ").literal(path, text).sql(" RETURNING NVARCHAR(5000) ERROR ON ERROR)");
    }) as never;
  }
}

/** 数值表达式上 HANA 专有的方法。 */
export class ViewNumber<T> extends ViewAny<T> {
  /** TO_INTEGER(e)：转成整数。 */
  toInteger(): ExprNumber<KeepNull<T, number>> {
    return call(TO_INTEGER, integer, this.e as Expr<unknown>) as never;
  }

  /** 中位数 `MEDIAN(e)`，偶数行时取中间两个值的平均；参数先转成 DOUBLE。可以作聚合。 */
  median(): ExprNumber<number | null> {
    return call(MEDIAN, double, this.e.cast(CastType.double())) as never;
  }
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
  return call<unknown>(CUBE, untyped as SqlType<{}>, ...keys);
}

/** GROUPING SETS ((a, b), (a), ()) 作为 GROUP BY 的键：只输出列出的分组；空列表表示总计。 */
export function groupingSets(...sets: (readonly Expr<unknown>[])[]): Expr<unknown> {
  const args = sets.map((set) => ({ render: (w: SqlWriter) => void w.sql("(").join(set, ", ").sql(")") }));
  return expression<unknown>(untyped as SqlType<{}>, (w) => GROUPING_SETS.render(w, args));
}

const GREATEST = FunctionDef.only(HanaDialect, "GREATEST");
const LEAST = FunctionDef.only(HanaDialect, "LEAST");

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

// ---------- 统计 ----------

const CORR = FunctionDef.only(HanaDialect, "CORR").aggregating();
const PERCENTILE_CONT = FunctionDef.only(HanaDialect, "PERCENTILE_CONT", (w, args) => void w.sql("PERCENTILE_CONT(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")")).aggregating();
const PERCENTILE_DISC = FunctionDef.only(HanaDialect, "PERCENTILE_DISC", (w, args) => void w.sql("PERCENTILE_DISC(").render(args[0]!).sql(") WITHIN GROUP (ORDER BY ").render(args[1]!).sql(")")).aggregating();

/**
 * 连续百分位数 `PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY e)`，再加 `.over((w) => w.partitionBy(...))`：
 * HANA 只把它当窗口函数（PostgreSQL 只当聚合），分组的百分位数用 `partitionBy` 加 `distinct()`。p 取 0 到 1。
 */
export function percentileCont(p: number, e: Expr<NumericValue | null>): WindowFunction<number | null> {
  return new WindowFunction<number | null>(double, PERCENTILE_CONT, [lit(fraction(p)), e.cast(CastType.double())]);
}

/** 离散百分位数 `PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY e)`，再加 `.over(...)`；规则同 {@link percentileCont}。 */
export function percentileDisc<T>(p: number, e: Expr<T>): WindowFunction<T | null> {
  return new WindowFunction<T | null>(e.type as SqlType<NonNullable<T | null>>, PERCENTILE_DISC, [lit(fraction(p)), e]);
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

// ---------- 数据来源 ----------

/** 渲染时确认当前方言是 HANA，否则报错。 */
function requireHana(w: SqlWriter): void {
  if (!(w.dialect instanceof HanaDialect)) throw new Error(`A HANA-only source cannot run on ${w.dialect.name}`);
}

/** 参数值按值推断类型绑定。 */
function bindValue(w: SqlWriter, value: unknown): void {
  w.render(param(typeOf(value) as SqlType<unknown>, value));
}

/** 列取自表实体、名字和 schema 来自 `@table` 的带参数来源；值为 null 或 undefined 的参数不写。 */
function parameterized<E>(table: Table<E>, parameters: Readonly<Record<string, unknown>>, fn: boolean): Source<E> {
  const base = tableSource(table);
  return customSource<E>({
    entity: table,
    filtered: true,
    columns: base.columns,
    preferredAlias: base.preferredAlias,
    columnName: (c, w) => base.columnName(c, w),
    renderSource: (w) => {
      requireHana(w);
      base.renderSource(w);
      const given = Object.entries(parameters).filter(([, v]) => v != null);
      if (given.length === 0 && !fn) return;
      w.sql(" (");
      given.forEach(([name, value], i) => {
        if (i > 0) w.sql(", ");
        if (fn) w.identifier(name);
        else w.sql(`PLACEHOLDER."$$${name.replaceAll('"', '""')}$$"`);
        w.sql(" => ");
        bindValue(w, value);
      });
      w.sql(")");
    },
  });
}

/**
 * 带输入参数的计算视图：`fromView(SalesView, { P_YEAR: "2026" })` 写成 `FROM "S"."CV_SALES" (PLACEHOLDER."$$P_YEAR$$" => ?)`。
 * 视图的列声明成带 `@table` 的类；参数都绑定，值为空的参数不写，由视图的默认值决定。
 */
export function fromView<E>(view: Table<E>, placeholders: Readonly<Record<string, unknown>>): From<[Ref<E>]> {
  return from(parameterized(view, placeholders, false));
}

/** 表函数：`fromFunction(LipsByUnit, { P_UNIT: "EA" })` 写成 `FROM "S"."FN" (P_UNIT => ?)`，命名实参，可以省略有默认值的参数。 */
export function fromFunction<E>(fn: Table<E>, args: Readonly<Record<string, unknown>>): From<[Ref<E>]> {
  return from(parameterized(fn, args, true));
}

/** 层级生成函数产生的列。 */
export interface HierarchyColumns {
  readonly hierarchyRank: number;
  readonly hierarchyTreeSize: number;
  readonly hierarchyParentRank: number;
  readonly hierarchyRootRank: number;
  readonly hierarchyLevel: number;
  readonly hierarchyIsCycle: number;
  readonly hierarchyIsOrphan: number;
}

/** 层级导航（后代、祖先）另外产生的列。 */
export interface NavigationColumns {
  readonly startRank: number;
  readonly hierarchyDistance: number;
}

const TREE_COLUMNS: Readonly<Record<string, string>> = {
  hierarchyRank: "HIERARCHY_RANK",
  hierarchyTreeSize: "HIERARCHY_TREE_SIZE",
  hierarchyParentRank: "HIERARCHY_PARENT_RANK",
  hierarchyRootRank: "HIERARCHY_ROOT_RANK",
  hierarchyLevel: "HIERARCHY_LEVEL",
  hierarchyIsCycle: "HIERARCHY_IS_CYCLE",
  hierarchyIsOrphan: "HIERARCHY_IS_ORPHAN",
};
const NAVIGATION_COLUMNS: Readonly<Record<string, string>> = { startRank: "START_RANK", hierarchyDistance: "HIERARCHY_DISTANCE" };

/** 层级来源：来源查询要有 nodeId 和 parentId；navigation 为 undefined 时是整棵树。 */
function hierarchySource<E>(source: Query<unknown>, navigation: "HIERARCHY_DESCENDANTS" | "HIERARCHY_ANCESTORS" | undefined, start: readonly unknown[]): Source<E> {
  const derived = new Derived(source);
  const names = derived.columns.map((c) => c.name);
  if (!names.includes("nodeId") || !names.includes("parentId")) throw new Error(`A hierarchy source must select nodeId and parentId, it selects ${names.join(", ")}`);
  if (navigation !== undefined && start.length === 0) throw new Error("Hierarchy navigation needs at least one start node");
  const generated = navigation === undefined ? TREE_COLUMNS : { ...TREE_COLUMNS, ...NAVIGATION_COLUMNS };
  const columns: SourceColumn[] = [...derived.columns, ...Object.keys(generated).map((name) => ({ name, type: integer as SqlType<unknown> }))];
  return customSource<E>({
    columns,
    preferredAlias: "hierarchy",
    columnName: (c) => generated[c.name] ?? c.name,
    renderSource: (w) => {
      requireHana(w);
      if (navigation !== undefined) w.sql(`${navigation}(SOURCE `);
      const s = w.aliasFor({}, "src");
      w.sql("HIERARCHY(SOURCE (SELECT ").alias(s).sql(".*, ").alias(s).sql(".").identifier("nodeId").sql(" AS NODE_ID, ").alias(s).sql(".").identifier("parentId").sql(" AS PARENT_ID FROM ");
      new Derived(source).renderSource(w);
      w.sql(" ").alias(s).sql(") SIBLING ORDER BY NODE_ID ASC)");
      if (navigation === undefined) return;
      w.sql(" START WHERE NODE_ID IN (");
      start.forEach((v, i) => {
        if (i > 0) w.sql(", ");
        bindValue(w, v);
      });
      w.sql("))");
    },
  });
}

/**
 * 层级：`from(hierarchy(来源查询))`。来源查询要有 nodeId 和 parentId 两列（类型相同，根的 parentId 为 NULL），
 * 写成 `HIERARCHY(SOURCE (...) SIBLING ORDER BY NODE_ID)`；每行带上 hierarchyRank、hierarchyLevel（从 1 起）、hierarchyParentRank（根为 0）等列。
 */
export function hierarchy<R>(source: Query<R>): Source<R & HierarchyColumns> {
  return hierarchySource(source as Query<unknown>, undefined, []);
}

/** 从 start 这些节点出发的后代（含自身），用 `HIERARCHY_DESCENDANTS`；另有 startRank、hierarchyDistance（自身为 0）。 */
export function descendants<R>(source: Query<R>, start: readonly unknown[]): Source<R & HierarchyColumns & NavigationColumns> {
  return hierarchySource(source as Query<unknown>, "HIERARCHY_DESCENDANTS", start);
}

/** 从 start 这些节点出发的祖先（含自身），用 `HIERARCHY_ANCESTORS`；hierarchyDistance 为负数。 */
export function ancestors<R>(source: Query<R>, start: readonly unknown[]): Source<R & HierarchyColumns & NavigationColumns> {
  return hierarchySource(source as Query<unknown>, "HIERARCHY_ANCESTORS", start);
}

// ---------- 全文检索与 JSON ----------

const SCORE = FunctionDef.only(HanaDialect, "SCORE");

/** CONTAINS 条件，写进当前的条件块；option 是 FUZZY(...) 之类的固定文本。 */
function search(term: string | null | undefined, columns: readonly Expr<string | null>[], option: string | undefined): void {
  if (columns.length === 0) throw new TypeError("contains(...) needs at least one column");
  if (skips(term)) return;
  const value = param(text, term as string);
  addCondition(searchCondition(columns, value, option));
}

/** `CONTAINS(列, ?[, 选项])`。 */
function searchCondition(columns: readonly Expr<string | null>[], value: Expr<string>, option: string | undefined): Cond {
  return condition((w) => {
    if (!(w.dialect instanceof HanaDialect)) throw new Error(`A HANA-only operation cannot run on ${w.dialect.name}`);
    w.sql("CONTAINS(");
    if (columns.length === 1) w.render(columns[0]!);
    else w.sql("(").join(columns, ", ").sql(")");
    w.sql(", ").render(value);
    if (option !== undefined) w.sql(", " + option);
    w.sql(")");
  });
}

/**
 * 全文检索 `CONTAINS(列, ?)`，多列写成 `CONTAINS((a, b), ?)`；搜索词是 HANA 的 freestyle 语法（空格是与、OR、引号短语）。
 * 只能用于列存表；写进当前的条件块，skipEmpty 下搜索词为空时没有条件。与 {@link score} 配合排序。
 */
export function contains(term: string | null | undefined, ...columns: Expr<string | null>[]): void {
  search(term, columns, undefined);
}

/** 模糊检索 `CONTAINS(列, ?, FUZZY(threshold))`，threshold 取 0 到 1，越大越严格，常用 0.8。 */
export function containsFuzzy(threshold: number, term: string | null | undefined, ...columns: Expr<string | null>[]): void {
  if (!(threshold >= 0 && threshold <= 1)) throw new RangeError(`A fuzzy threshold must be between 0 and 1, got ${threshold}`);
  search(term, columns, `FUZZY(${threshold})`);
}

/** 查询里全部 CONTAINS 的综合相关度 `SCORE()`，0 到 1。 */
export function score(): ExprNumber<number> {
  return call<number>(SCORE, double);
}

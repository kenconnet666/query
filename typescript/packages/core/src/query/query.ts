import type { Clause, Dialect } from "../sql/dialect.js";
import { all, Cond, Expr, Literal, toOrder, withSkipEmpty, type Order, type OrderItem, type SourceColumn } from "../sql/expr.js";
import { COLUMN_TYPES, COLUMNS, NAMED, SUBQUERY } from "../internal.js";
import { shapeFields, type Ctor, type Table } from "../schema/metadata.js";
import { Many } from "./many.js";
import { createRef, Cte, Derived, ownerOf, RELATED, tableSource, type NullableRef, type Ref, type RefOwner, type Relation, type Source } from "./source.js";
import { double, integer, typeOf, type SqlType } from "../schema/types.js";
import { SqlWriter, type Renderable, type Rendered } from "../sql/writer.js";

/** 只在类型层面携带行类型的标记，运行时不存在。 */
declare const rowType: unique symbol;

// ---------- 全局过滤器 ----------

/** 作用于整个数据库的条件：凡实体是 `type` 或其子类的表都会套用。 */
export interface SourceFilter {
  readonly type: Ctor<unknown>;
  readonly condition: (ref: Ref<never>) => Cond;
}

/** FROM 的表加到 WHERE，连接的表加到 ON；`.unfiltered()` 可跳过。 */
export function filter<E>(type: Ctor<E>, condition: (ref: Ref<E>) => Cond): SourceFilter {
  return Object.freeze({ type, condition: condition as (ref: Ref<never>) => Cond });
}

/** 取适用于该数据源引用的全局过滤条件。 */
function conditionsFor(filters: readonly SourceFilter[], ref: object): Cond[] {
  const entity = ownerOf(ref).source.entity;
  if (filters.length === 0 || entity === undefined || !ownerOf(ref).source.filtered) return [];
  return filters.filter((f) => entity === f.type || entity.prototype instanceof f.type).map((f) => f.condition(ref as Ref<never>));
}

// ---------- 不含投影的一个 SELECT ----------

/** FROM 子句中的一项连接。 */
type JoinKind = "FROM" | "JOIN" | "LEFT JOIN" | "CROSS JOIN";

/** 一个已加入的数据源：连接方式、引用和 ON 条件。 */
interface Join {
  readonly kind: JoinKind;
  readonly ref: object;
  readonly on: Cond;
}

/** 一个 SELECT 除投影以外的全部内容；不可变，每次修改都生成新的副本。 */
interface SelectSpec {
  readonly joins: readonly Join[];
  readonly where: readonly Cond[];
  readonly groupBy: readonly Expr<unknown>[];
  /** 对全部键做 GROUP BY ROLLUP：按键的每个前缀小计，再加总计。 */
  readonly rollup: boolean;
  readonly having: readonly Cond[];
  readonly orderBy: readonly Order[];
  readonly distinct: boolean;
  readonly limit: number | undefined;
  readonly offset: number | undefined;
  readonly filtered: boolean;
  /** 是否开启 skipEmpty：与空值比较的条件直接省略。 */
  readonly skipEmpty: boolean;
  readonly clauses: readonly Clause[];
}

/** 校验 limit / offset 是非负整数。 */
function rows(n: number, what: string): number {
  if (!Number.isSafeInteger(n) || n < 0) throw new RangeError(`${what} must be a non-negative integer`);
  return n;
}

/** 条件回调返回 null 或 undefined 是调用错误，没有条件应返回 Cond.EMPTY。 */
function nonNull(c: Cond): Cond {
  if (c == null) throw new TypeError("A condition is null or undefined; use Cond.EMPTY for no condition");
  return c;
}

/** 把单个值或数组统一成数组。 */
function list<T>(value: T | readonly T[]): readonly T[] {
  return Array.isArray(value) ? (value as readonly T[]) : [value as T];
}

// ---------- 投影 ----------

/**
 * 对象投影：每个叶子是一个表达式，嵌套的对象得到嵌套的结果。
 * 一对多（{@link Many}）和关系目标的引用不是列，会被略过，所以展开带关系的引用也能直接投影。
 */
export type Projection = { readonly [key: string]: Expr<any> | Projection | Many<any> };

/** 投影的结果类型；略过一对多和关系目标的引用，与运行时一致。 */
export type Shape<P> = {
  -readonly [K in keyof P as K extends string ? (P[K] extends Many<any> | { readonly [RELATED]: true } ? never : K) : never]: P[K] extends Expr<infer V>
    ? V
    : Shape<P[K]>;
};

/** 是否是字面量对象，用来区分嵌套投影与类的实例。 */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

/** 把嵌套投影展平成表达式列表和对应的键路径。 */
function flatten(p: object, path: readonly string[], items: Expr<unknown>[], paths: string[][]): void {
  for (const [key, value] of Object.entries(p)) {
    if (value instanceof Expr) {
      items.push(value as Expr<unknown>);
      paths.push([...path, key]);
    } else if (value instanceof Many || (isPlainObject(value) && RELATED in value)) {
      continue;
    } else if (isPlainObject(value)) {
      flatten(value, [...path, key], items, paths);
    } else {
      throw new TypeError(`Projection field '${[...path, key].join(".")}' must be an expression or a nested object; use val(...) for constants`);
    }
  }
}

/** 按键路径把一行的值组装回对象；路径都只有一层时走更快的扁平分支。 */
function assembler(paths: readonly (readonly string[])[]): (values: readonly unknown[]) => unknown {
  if (paths.every((p) => p.length === 1)) {
    const keys = paths.map((p) => p[0]!);
    return (values) => {
      const row: Record<string, unknown> = {};
      keys.forEach((k, i) => (row[k] = values[i]));
      return row;
    };
  }
  return (values) => {
    const root: Record<string, unknown> = {};
    paths.forEach((path, i) => {
      let node = root;
      for (const key of path.slice(0, -1)) node = (node[key] ??= {}) as Record<string, unknown>;
      node[path.at(-1)!] = values[i];
    });
    return root;
  };
}

// ---------- 查询 ----------

/** UNION 的一个分支：SELECT 内容和投影项。 */
interface Arm {
  readonly spec: SelectSpec;
  readonly items: readonly Expr<unknown>[];
}

/** 查询的不可变状态。 */
interface State {
  readonly arms: readonly Arm[];
  readonly operators: readonly string[];
  /**
   * 需要加括号的分支范围。UNION 与 UNION ALL 从左到右结合，
   * 所以只有混用两者的右侧才需要自己的括号。
   */
  readonly groups: readonly (readonly [first: number, last: number])[];
  readonly labels: readonly string[];
  /** 列标签是对象投影的键；这样的分支按名称合并。 */
  readonly keyed: boolean;
  /** 扁平的对象投影：可以做 CTE 或派生表。 */
  readonly flat: boolean;
  readonly assemble: (values: readonly unknown[]) => unknown;
  readonly limit: number | undefined;
  readonly offset: number | undefined;
  readonly ttl: number | undefined;
  /** 数据库取消语句前的毫秒数；undefined 表示用数据库实例的默认值。 */
  readonly timeout: number | undefined;
}

// 由 Query 的 static 块赋值，让模块内的函数能访问它的私有状态而不公开。
let makeQuery: <R>(s: State) => Query<R>;
let stateOf: (q: Query<unknown>) => State;
let memoOf: (q: Query<unknown>) => WeakMap<object, Rendered>;

/**
 * 完整的不可变查询，产生类型为 R 的行。由作用域的 `select...` 方法构建，
 * 可用 {@link unionAll} 组合，由某个方言的数据库实例执行。
 * 渲染结果按数据库实例缓存：同一个查询对象只渲染一次。
 */
export class Query<R> {
  declare readonly [rowType]: R;
  readonly #s: State;
  readonly #memo = new WeakMap<object, Rendered>();

  private constructor(s: State) {
    this.#s = s;
  }

  static {
    makeQuery = <R>(s: State) => new Query<R>(s);
    stateOf = (q) => q.#s;
    memoOf = (q) => q.#memo;
  }

  /** 按 SELECT 顺序排列的列标签；嵌套投影的键用点连接。 */
  get labels(): readonly string[] {
    return this.#s.labels;
  }

  /** UNION ALL。两边必须产生相同的行类型；对象投影按键而不是按位置匹配。 */
  unionAll(other: Query<R>): Query<R> {
    return this.#combine("UNION ALL", other);
  }

  /** UNION（去重），规则同 {@link unionAll}。 */
  union(other: Query<R>): Query<R> {
    return this.#combine("UNION", other);
  }

  /** 用集合运算符合并两个查询，校验列数、排序与分页，并按需重排对象投影的列。 */
  #combine(operator: string, other: Query<R>): Query<R> {
    const a = this.#s;
    const b = other.#s;
    if (a.labels.length !== b.labels.length) throw new Error(`UNION arms select ${a.labels.length} and ${b.labels.length} columns`);
    if ([a, b].some((s) => s.limit !== undefined || s.offset !== undefined)) throw new Error("Apply limit/offset after combining queries, not before");
    if ([...a.arms, ...b.arms].some((arm) => hasOrderOrLimit(arm.spec))) {
      throw new Error("A UNION arm cannot have ORDER BY or LIMIT; wrap it in a CTE");
    }
    let arms = b.arms;
    if (a.keyed && b.keyed && a.labels.some((l, i) => l !== b.labels[i])) {
      const position = new Map(b.labels.map((l, i) => [l, i]));
      if (a.labels.some((l) => !position.has(l))) throw new Error(`UNION arms select different columns: ${a.labels} and ${b.labels}`);
      arms = b.arms.map((arm) => ({ spec: arm.spec, items: a.labels.map((l) => arm.items[position.get(l)!]!) }));
    }
    const shift = a.arms.length;
    const groups = [...a.groups, ...b.groups.map(([first, last]) => [first + shift, last + shift] as const)];
    if (b.operators.some((op) => op !== operator)) groups.push([shift, shift + b.arms.length - 1]);
    return makeQuery({ ...a, arms: [...a.arms, ...arms], operators: [...a.operators, operator, ...b.operators], groups, ttl: a.ttl, timeout: a.timeout });
  }

  // ---------- 动态列与排序 ----------

  /**
   * 不取这些列：SQL 里写成 NULL，结果里为 null，结果的形态不变。只被它们用到的导航关系不再连接；
   * 有分组时，与它们相同的分组键（以及按这些键的排序）一起去掉，于是同一个查询可以按用户选的维度汇总。
   * 名字是结果的键，嵌套对象的键去掉它下面的全部列；不分大小写和下划线，不存在的名字报错。
   */
  exclude<K extends keyof R & string>(...names: K[]): Query<{ [P in keyof R]: P extends K ? Excluded<R[P]> : R[P] }> {
    return this.#omit(this.#positionsOf(names, "exclude"));
  }

  /** condition 为真时才 {@link exclude}，例如 `.excludeIf(!withText, "text")`。 */
  excludeIf<K extends keyof R & string>(condition: boolean, ...names: K[]): Query<{ [P in keyof R]: P extends K ? Excluded<R[P]> : R[P] }> {
    return condition ? this.exclude(...names) : (this as never);
  }

  /**
   * 只取这些列，其余的按 {@link exclude} 处理。names 为 null、undefined 或空时保持不变，所以可以直接传入用户选的列；
   * 只认结果里已有的名字，不存在的名字报错。
   */
  only(names: readonly string[] | null | undefined): Query<{ [P in keyof R]: Excluded<R[P]> }> {
    if (names == null || names.length === 0) return this as never;
    const kept = this.#positionsOf(names, "only");
    return this.#omit(this.#s.labels.map((_, i) => i).filter((i) => !kept.has(i)));
  }

  /**
   * 按排序串排序，例如 `"partNumber desc, material"`：逗号分隔，每项是一个结果列名，可以跟 asc 或 desc。
   * 只能按结果里的列排序，相当于白名单，所以可以直接传入用户的排序参数；未知列名或写法报错。
   * spec 为空时保持原来的 ORDER BY，否则替换它。合并后的查询先放进 CTE 再排序。
   */
  sortBy(spec: string | null | undefined): Query<R> {
    if (spec == null || spec.trim() === "") return this;
    if (this.#s.arms.length > 1) throw new Error("sortBy(...) works on a single SELECT; wrap the UNION in a CTE first");
    const items = this.#s.arms[0]!.items;
    const orders: Order[] = [];
    for (const part of spec.split(",")) {
      const words = part.trim().split(/\s+/);
      const name = words[0]!;
      if (name === "") continue;
      const direction = (words[1] ?? "asc").toLowerCase();
      if (words.length > 2 || (direction !== "asc" && direction !== "desc")) {
        throw new Error(`Cannot read sort item '${part.trim()}'; write 'column', 'column asc' or 'column desc'`);
      }
      const at = [...this.#positionsOf([name], "sortBy")];
      if (at.length !== 1) throw new Error(`Cannot sort by '${name}'; it names a nested object, sort by one of its columns`);
      const item = items[at[0]!]!;
      if (item instanceof Omitted) throw new Error(`Cannot sort by '${name}', it is excluded`);
      orders.push(direction === "desc" ? item.desc() : item.asc());
    }
    return this.#withSpec({ ...this.#s.arms[0]!.spec, orderBy: orders });
  }

  /** 名字在结果里的列位置：同名的列，或嵌套对象下的全部列；未知的名字报错并列出可用的列。 */
  #positionsOf(names: readonly string[], use: string): Set<number> {
    const labels = this.#s.labels.map(normalize);
    const found = new Set<number>();
    for (const name of names) {
      const key = normalize(name);
      const hits = labels.flatMap((label, i) => (label === key || label.startsWith(`${key}.`) ? [i] : []));
      if (hits.length === 0) throw new Error(`${use}(...): unknown column '${name}'; the query has [${this.#s.labels.join(", ")}]`);
      for (const i of hits) found.add(i);
    }
    return found;
  }

  /** 把各段里这些位置的列换成 NULL。 */
  #omit(positions: Iterable<number>): Query<never> {
    const at = new Set(positions);
    if (at.size === 0) return this as never;
    const arms = this.#s.arms.map((arm) => ({
      spec: arm.spec,
      items: arm.items.map((e, i) => (at.has(i) && !(e instanceof Omitted) ? new Omitted(e) : e)),
    }));
    return makeQuery({ ...this.#s, arms });
  }

  /** SQL 不变，对每一行应用一个函数。 */
  map<V>(fn: (row: R) => V): Query<V> {
    const inner = this.#s.assemble;
    return makeQuery({ ...this.#s, flat: false, keyed: false, assemble: (values) => fn(inner(values) as R) });
  }

  /**
   * 同一条 SQL，每行按字段名复制到类的新实例里。行类型必须提供类的全部字段（编译期检查），多出来的列被忽略。
   */
  into<C extends object>(type: new () => C, ..._check: [R] extends [Fields<C>] ? [] : [error: { "the row type does not provide the fields of": C }]): Query<C> {
    const inner = this.#s.assemble;
    let fields: readonly string[] | undefined;
    return makeQuery({
      ...this.#s,
      flat: false,
      keyed: false,
      assemble: (values) => {
        const row = inner(values) as Record<string, unknown>;
        const target = new type() as Record<string, unknown>;
        for (const f of (fields ??= shapeFields(type))) target[f] = row[f];
        return target;
      },
    });
  }

  /** 结果在数据库实例的缓存里保留 ttl 毫秒，以 SQL 和参数为键。 */
  cached(ttlMs: number): Query<R> {
    if (!(ttlMs > 0)) throw new RangeError("ttl must be positive");
    return makeQuery({ ...this.#s, ttl: ttlMs });
  }

  /** 语句运行超过该毫秒数时取消；优先于数据库实例的 queryTimeoutMs。 */
  timeout(ms: number): Query<R> {
    if (!(ms > 0)) throw new RangeError("timeout must be positive");
    return makeQuery({ ...this.#s, timeout: ms });
  }

  /** 最多返回 n 行；单个分支直接限制该分支，合并后的查询则限制整体。 */
  limit(n: number): Query<R> {
    if (this.#s.arms.length === 1) return this.#withSpec({ ...this.#s.arms[0]!.spec, limit: rows(n, "limit") });
    return makeQuery({ ...this.#s, limit: rows(n, "limit") });
  }

  /** 跳过前 n 行，用法同 {@link limit}。 */
  offset(n: number): Query<R> {
    if (this.#s.arms.length === 1) return this.#withSpec({ ...this.#s.arms[0]!.spec, offset: rows(n, "offset") });
    return makeQuery({ ...this.#s, offset: rows(n, "offset") });
  }

  /** 替换唯一分支的 SELECT 内容。 */
  #withSpec(spec: SelectSpec): Query<R> {
    return makeQuery({ ...this.#s, arms: [{ spec, items: this.#s.arms[0]!.items }] });
  }

  /** 作为派生表使用：`from(query.asTable())`。需要扁平的对象投影。 */
  asTable(): Source<R> {
    return new Derived(this);
  }

  /** 按某个方言渲染，不带任何数据库实例的过滤器和改写。方便写测试。 */
  toSql(dialect: Dialect): Rendered {
    return renderStatement(new SqlWriter(dialect), this);
  }

  /** 列标签的概要，便于日志。 */
  toString(): string {
    return `Query(${this.#s.labels.join(", ")})`;
  }

  /** 作为子查询渲染（EXISTS、IN、标量子查询）。 */
  [SUBQUERY](w: SqlWriter): void {
    renderQuery(w, this.#s, true, false);
  }

  /** 带列名渲染，用于 CTE 主体或派生表。 */
  [NAMED](w: SqlWriter): void {
    renderQuery(w, this.#s, true, true);
  }

  /** 各列的类型，取自第一个分支。 */
  get [COLUMN_TYPES](): readonly SqlType<unknown>[] {
    return this.#s.arms[0]!.items.map((e) => e.type as SqlType<unknown>);
  }

  /** 作为 CTE 或派生表时的列；只有扁平的对象投影才有列名，`use` 用于报错信息。 */
  [COLUMNS](use: string): readonly SourceColumn[] {
    if (!this.#s.flat) throw new Error(`Only a query with a flat object projection can be used as ${use}; its columns need names`);
    const types = this[COLUMN_TYPES];
    return this.#s.labels.map((name, i) => Object.freeze({ name, type: types[i]! }));
  }
}

/** 判断两个类型是否完全相同；用来区分只读与可写的属性。 */
type IfEquals<X, Y, A, B> = (<T>() => T extends X ? 1 : 2) extends <T>() => T extends Y ? 1 : 2 ? A : B;
/** 类 C 中可写属性的键。 */
type WritableKeys<C> = { [K in keyof C]-?: IfEquals<{ [Q in K]: C[K] }, { -readonly [Q in K]: C[K] }, K, never> }[keyof C];

/** `into` 填充的类字段：不含方法，也不含 getter（它们在类型上是只读的）。 */
export type Fields<C> = { [K in keyof C as K extends WritableKeys<C> ? (C[K] extends (...args: never[]) => unknown ? never : K) : never]: C[K] };

/** 被排除后的值：叶子可能为 null，嵌套对象保持结构（它的每一列都是 null）。 */
export type Excluded<V> = V extends Record<string, unknown> ? { [K in keyof V]: Excluded<V[K]> } : V | null;

/** 列名与名字的比较形式：去掉下划线并忽略大小写，与 Java 一致。 */
function normalize(name: string): string {
  return name.replaceAll("_", "").toLowerCase();
}

/**
 * {@link Query.exclude} 去掉的一列：SQL 里写成 NULL，所以结果的形态不变，这一列读出来是 null。
 * 保留原来的表达式，渲染时用它找出要一起去掉的分组键。
 */
class Omitted extends Expr<unknown> {
  constructor(readonly original: Expr<unknown>) {
    super(original.type);
  }

  /** 写出 `NULL`。 */
  render(w: SqlWriter): void {
    w.sql("NULL");
  }
}

/** 分支是否带 ORDER BY 或分页；UNION 的分支不允许。 */
function hasOrderOrLimit(s: SelectSpec): boolean {
  return s.orderBy.length > 0 || s.limit !== undefined || s.offset !== undefined;
}

// ---------- 供数据库使用的包内部函数 ----------

/** 把查询渲染成完整语句。 */
export function renderStatement(w: SqlWriter, q: Query<unknown>): Rendered {
  renderQuery(w, stateOf(q), true, true);
  return w.statement();
}

/** 对查询做 SELECT COUNT(*)；除非 LIMIT 需要，否则去掉 ORDER BY。 */
export function renderCount(w: SqlWriter, q: Query<unknown>): Rendered {
  w.sql("SELECT COUNT(*) FROM (");
  renderQuery(w, stateOf(q), false, true);
  w.sql(") ").alias(w.aliasFor({}, "counted"));
  return w.statement();
}

/** 查询在某个数据库实例上的渲染结果，只计算一次。 */
export function rendered(q: Query<unknown>, key: object, render: () => Rendered): Rendered {
  const memo = memoOf(q);
  let r = memo.get(key);
  if (r === undefined) {
    r = render();
    memo.set(key, r);
  }
  return r;
}

/** 解码驱动返回的行所需的信息：先按列类型解码，再按投影组装。 */
export function decoderOf<R>(q: Query<R>): { readonly types: readonly SqlType<unknown>[]; readonly assemble: (values: readonly unknown[]) => R } {
  return { types: q[COLUMN_TYPES], assemble: stateOf(q).assemble as (values: readonly unknown[]) => R };
}

/** 查询的缓存时长（毫秒），未标记缓存时为 undefined。 */
export function ttlOf(q: Query<unknown>): number | undefined {
  return stateOf(q).ttl;
}

/** 查询自带的超时（毫秒），未设置时为 undefined。 */
export function timeoutOf(q: Query<unknown>): number | undefined {
  return stateOf(q).timeout;
}

/** 把 limit 压到至多 n，已有更小的 limit 时保持不变。 */
export function limitAtMost<R>(q: Query<R>, n: number): Query<R> {
  const s = stateOf(q);
  const current = s.arms.length === 1 ? s.arms[0]!.spec.limit : s.limit;
  return current !== undefined && current <= n ? q : q.limit(n);
}

/** 渲染各分支及其运算符和括号；`labeled` 为真时给首个分支的列加上 AS 标签。 */
function renderQuery(w: SqlWriter, s: State, ordered: boolean, labeled: boolean): void {
  s.arms.forEach((arm, i) => {
    if (i > 0) w.sql(`\n${s.operators[i - 1]}\n`);
    w.sql("(".repeat(s.groups.filter(([first]) => first === i).length));
    renderArm(w, arm, i === 0 && labeled ? s.labels : undefined, ordered);
    w.sql(")".repeat(s.groups.filter(([, last]) => last === i).length));
  });
  if (s.arms.length > 1) w.dialect.limit(w, s.limit, s.offset);
}

/** 渲染一个 SELECT 分支。@param ordered 为 false 时，对没有 LIMIT 或 OFFSET 的分支去掉 ORDER BY */
function renderArm(w: SqlWriter, arm: Arm, labels: readonly string[] | undefined, ordered: boolean): void {
  const s = arm.spec;
  // 按连接顺序分配别名，这样即使 SELECT 先提到被连接的表，FROM 的表仍得到最简单的名字。
  for (const j of s.joins) ownerOf(j.ref).alias(w);
  const dialect: Dialect = w.dialect;
  for (const c of s.clauses) {
    if (c.dialect !== undefined && !(dialect instanceof c.dialect)) throw new Error(`A ${c.dialect.name} clause cannot run on ${w.dialect.name}`);
  }
  w.sql("SELECT ");
  for (const c of s.clauses) if (c.position === "hint") w.render(c).sql(" ");
  const distinctClauses = s.clauses.filter((c) => c.position === "distinct");
  for (const c of distinctClauses) w.render(c).sql(" ");
  if (s.distinct && distinctClauses.length === 0) w.sql("DISTINCT ");
  let groupBy = s.groupBy;
  let orderBy = s.orderBy;
  const excluded = excludedKeys(w, arm);
  if (excluded.size > 0) {
    const probe = w.probe();
    groupBy = groupBy.filter((g) => !excluded.has(textOf(probe, g)));
    orderBy = orderBy.filter((o) => !excluded.has(textOf(probe, o.expr)));
  }
  // SELECT 列表里的常量写成字面量：HANA 无法确定那里参数的类型（CTE 里的 COALESCE(x, ?)
  // 对外层的 SUM 来说不是数值）。GROUP BY、DISTINCT 及其 ORDER BY 按文本匹配表达式，
  // 所以其中的常量也必须是字面量。
  const grouped = groupBy.length > 0 || s.distinct || distinctClauses.length > 0;
  const outer = w.inline(true);
  arm.items.forEach((item, i) => {
    if (i > 0) w.sql(", ");
    w.render(item);
    if (labels !== undefined) w.sql(" AS ").identifier(labels[i]!);
  });
  w.inline(false);
  if (s.joins.length === 0) w.dialect.fromNothing(w);
  const filters = s.filtered ? ((w.settings.filters as readonly SourceFilter[] | undefined) ?? []) : [];
  const where: Cond[] = [];
  const implicit = usedRelations(w, arm, groupBy, orderBy);
  for (const j of s.joins) {
    const extra = conditionsFor(filters, j.ref);
    const owner = ownerOf(j.ref);
    if (j.kind === "FROM" || j.kind === "CROSS JOIN") {
      w.sql(`\n${j.kind} `);
      owner.renderSource(w);
      where.push(...extra);
    } else {
      w.sql(`\n${j.kind} `);
      owner.renderSource(w);
      // 被连接的表的过滤条件放进 ON，LEFT JOIN 才仍是 LEFT JOIN。
      w.sql(" ON ").render(all(j.on, ...extra));
    }
    emitRelations(w, owner, implicit, filters);
  }
  const whereAll = all(...where, ...s.where);
  if (!whereAll.isEmpty) w.sql("\nWHERE ").render(whereAll);
  w.inline(grouped);
  if (groupBy.length > 0) {
    w.sql("\nGROUP BY ");
    w.dialect.groupBy(w, groupBy, s.rollup);
  }
  const having = all(...s.having);
  if (!having.isEmpty) w.sql("\nHAVING ").render(having);
  const limited = s.limit !== undefined || s.offset !== undefined;
  if (orderBy.length > 0 && (ordered || limited)) w.sql("\nORDER BY ").join(orderBy, ", ");
  w.inline(outer);
  w.dialect.limit(w, s.limit, s.offset);
  for (const c of s.clauses) if (c.position === "end") w.sql("\n").render(c);
}

/**
 * {@link Query.exclude} 去掉的列原来的写法，用来去掉与它们相同的分组键和排序项；没有分组时为空，
 * 因为不分组时按不在结果里的列排序仍然成立。
 */
function excludedKeys(w: SqlWriter, arm: Arm): Set<string> {
  const keys = new Set<string>();
  if (arm.spec.groupBy.length === 0) return keys;
  let probe: SqlWriter | undefined;
  for (const e of arm.items) if (e instanceof Omitted) keys.add(textOf((probe ??= w.probe()), e.original));
  return keys;
}

/** node 在 probe 里的写法；同一个 probe 里别名一致，所以写法相同就是同一个表达式。常量按字面量写出。 */
function textOf(probe: SqlWriter, node: Renderable): string {
  const f = probe.fork();
  f.inline(true);
  f.render(node);
  return f.text;
}

/**
 * 这一段真正用到的隐式连接（导航关系）。先用探查写入器把 SELECT、条件、分组、排序试渲染一遍，记下引用到的数据源；
 * 用到了某个关系的目标（或它更深的关系）就连接它，并把它的连接条件也试渲染，直到不再增加。没有关系时不做探查。
 * groupBy 和 orderBy 是去掉被排除的列之后实际要写出的。
 */
function usedRelations(w: SqlWriter, arm: Arm, groupBy: readonly Expr<unknown>[], orderBy: readonly Order[]): Set<Relation> {
  const s = arm.spec;
  if (s.joins.every((j) => ownerOf(j.ref).relations.length === 0)) return new Set();
  const probe = w.probe();
  for (const e of arm.items) probe.render(e);
  for (const j of s.joins) if (!j.on.isEmpty) probe.render(j.on);
  for (const c of [...s.where, ...s.having]) if (!c.isEmpty) probe.render(c);
  for (const g of groupBy) probe.render(g);
  for (const o of orderBy) probe.render(o);
  for (const c of s.clauses) probe.render(c);
  const used = new Set<Relation>();
  let grew = true;
  while (grew) {
    grew = false;
    for (const j of s.joins) grew = collect(ownerOf(j.ref), probe, used) || grew;
  }
  return used;
}

/** 把 owner 出发的、用到了的关系加入 used，并试渲染它们的连接条件；有新增时返回 true。 */
function collect(owner: RefOwner, probe: SqlWriter, used: Set<Relation>): boolean {
  let grew = false;
  for (const r of owner.relations) {
    if (!used.has(r) && reaches(r.owner, probe.touched)) {
      used.add(r);
      probe.render(r.on);
      grew = true;
    }
    if (used.has(r)) grew = collect(r.owner, probe, used) || grew;
  }
  return grew;
}

/** owner 或从它出发的某个关系的目标是否被引用到。 */
function reaches(owner: RefOwner, touched: ReadonlySet<object>): boolean {
  return touched.has(owner) || owner.relations.some((r) => reaches(r.owner, touched));
}

/** 紧跟在 owner 的 FROM 或 JOIN 之后写出它用到的隐式连接（LEFT JOIN），再写更深的关系；全局过滤器放进 ON。 */
function emitRelations(w: SqlWriter, owner: RefOwner, used: ReadonlySet<Relation>, filters: readonly SourceFilter[]): void {
  for (const r of owner.relations) {
    if (!used.has(r)) continue;
    w.sql("\nLEFT JOIN ");
    r.owner.renderSource(w);
    w.sql(" ON ").render(all(r.on, ...conditionsFor(filters, r.ref)));
    emitRelations(w, r.owner, used, filters);
  }
}

// ---------- 作用域 ----------

/** FROM 或 JOIN 的对象：表实体，或 CTE、派生表这类数据源。 */
type Target<E> = Table<E> | Source<E>;

/** 为目标创建一次新的引用，同一个表连接两次得到两个引用。 */
function refTo<E>(target: Target<E>): Ref<E> {
  return createRef(typeof target === "function" ? tableSource(target) : target);
}

// 由 From 的 static 块赋值，让模块内的函数能构造 From 而不公开构造函数。
let makeFrom: <T extends readonly unknown[]>(spec: SelectSpec, refs: T) => From<T>;

/**
 * 构建中的查询。每连接一个数据源，回调就按连接顺序多一个带类型的参数；
 * `leftJoin` 之后该参数的列可能为 null。不可变：每次调用都返回新的作用域，
 * 所以一个作用域可以被共享，并向不同方向扩展。
 */
export class From<T extends readonly unknown[]> {
  readonly #spec: SelectSpec;
  readonly #refs: T;

  private constructor(spec: SelectSpec, refs: T) {
    this.#spec = spec;
    this.#refs = refs;
  }

  static {
    makeFrom = <T extends readonly unknown[]>(spec: SelectSpec, refs: T) => new From<T>(spec, refs);
  }

  /** INNER JOIN；`on` 的参数依次是已连接的全部数据源。 */
  join<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => Cond): From<[...T, Ref<E>]> {
    return this.#join("JOIN", target, on);
  }

  /** LEFT JOIN；新数据源的列在回调里是可空的。 */
  leftJoin<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => Cond): From<[...T, NullableRef<E>]> {
    return this.#join("LEFT JOIN", target, on);
  }

  /** CROSS JOIN（笛卡尔积），不需要条件。 */
  crossJoin<E>(target: Target<E>): From<[...T, Ref<E>]> {
    return this.#join("CROSS JOIN", target, undefined);
  }

  /** 加入一个数据源；除 CROSS JOIN 外，连接条件不能为空。 */
  #join<U extends readonly unknown[], E>(kind: JoinKind, target: Target<E>, on: ((...refs: [...T, Ref<E>]) => Cond) | undefined): From<U> {
    const ref = refTo(target);
    const refs = [...this.#refs, ref] as unknown as [...T, Ref<E>];
    const condition = on === undefined ? Cond.EMPTY : nonNull(withSkipEmpty(this.#spec.skipEmpty, () => on(...refs)));
    if (kind !== "CROSS JOIN" && condition.isEmpty) throw new Error(`${kind} needs a condition; use crossJoin for a cartesian product`);
    return makeFrom({ ...this.#spec, joins: [...this.#spec.joins, { kind, ref, on: condition }] }, refs as unknown as U);
  }

  /** WHERE 条件；多次调用之间是 AND，空条件（Cond.EMPTY）被丢弃。 */
  where(cond: (...refs: T) => Cond): From<T> {
    return this.#with({ where: [...this.#spec.where, nonNull(withSkipEmpty(this.#spec.skipEmpty, () => cond(...this.#refs)))] });
  }

  /** GROUP BY；多次调用的键依次追加。 */
  groupBy(keys: (...refs: T) => Expr<unknown> | readonly Expr<unknown>[]): From<T> {
    if (this.#spec.rollup) throw new Error("groupByRollup already lists every grouping key");
    return this.#with({ groupBy: [...this.#spec.groupBy, ...list(keys(...this.#refs))] });
  }

  /** 对全部键做 GROUP BY ROLLUP：按键的每个前缀小计，再加总计。 */
  groupByRollup(keys: (...refs: T) => Expr<unknown> | readonly Expr<unknown>[]): From<T> {
    if (this.#spec.groupBy.length > 0) throw new Error("groupByRollup must list every grouping key; it cannot follow groupBy");
    const all = list(keys(...this.#refs));
    if (all.length === 0) throw new Error("groupByRollup needs at least one key");
    return this.#with({ groupBy: all, rollup: true });
  }

  /** HAVING 条件；多次调用之间是 AND。 */
  having(cond: (...refs: T) => Cond): From<T> {
    return this.#with({ having: [...this.#spec.having, nonNull(withSkipEmpty(this.#spec.skipEmpty, () => cond(...this.#refs)))] });
  }

  /** 表达式默认升序；需要其他排序用 `.desc()`、`.nullsLast()` 等。 */
  orderBy(items: (...refs: T) => OrderItem | readonly OrderItem[]): From<T> {
    return this.#with({ orderBy: [...this.#spec.orderBy, ...list(items(...this.#refs)).map(toOrder)] });
  }

  /** SELECT DISTINCT。 */
  distinct(): From<T> {
    return this.#with({ distinct: true });
  }

  /** 最多返回 n 行。 */
  limit(n: number): From<T> {
    return this.#with({ limit: rows(n, "limit") });
  }

  /** 跳过前 n 行。 */
  offset(n: number): From<T> {
    return this.#with({ offset: rows(n, "offset") });
  }

  /** 本查询不套用数据库实例的全局过滤器。 */
  unfiltered(): From<T> {
    return this.#with({ filtered: false });
  }

  /**
   * 之后写的 WHERE、HAVING、连接条件里，与 null、undefined、空白字符串或空数组比较的条件直接省略，
   * 适合按请求参数筛选：`from(Likp).skipEmpty().where((h) => h.customer.eq(req.customer))`。
   * 不开启时与 null 比较会报错，以免漏写条件导致查出全部数据。
   */
  skipEmpty(): From<T> {
    return this.#with({ skipEmpty: true });
  }

  /** 加入方言专属子句，例如 HANA 包的 `hint("NO_CS_JOIN")`。 */
  with(clause: Clause | ((...refs: T) => Clause)): From<T> {
    const c = typeof clause === "function" ? clause(...this.#refs) : clause;
    return this.#with({ clauses: [...this.#spec.clauses, c] });
  }

  /** 套用可复用的片段：一个从作用域到作用域（或到查询）的普通函数。 */
  pipe<R>(fragment: (scope: this) => R): R {
    return fragment(this);
  }

  /**
   * 投影。回调返回一个表达式时是单列查询（标量、IN 子查询或值列表）；返回对象时键就是列名，
   * 嵌套的对象得到嵌套的结果，扁平的对象投影可以做 CTE 或派生表。
   */
  select<V>(projection: (...refs: T) => Expr<V>): Query<V>;
  select<P extends Projection>(projection: (...refs: T) => P): Query<Shape<P>>;
  select(projection: (...refs: T) => Expr<unknown> | Projection): Query<unknown> {
    const selected = projection(...this.#refs);
    if (selected instanceof Expr) return this.#single(selected as Expr<unknown>, selected.label ?? "value");
    const items: Expr<unknown>[] = [];
    const paths: string[][] = [];
    flatten(selected, [], items, paths);
    if (items.length === 0) throw new Error("The projection selects no column");
    return makeQuery({
      arms: [{ spec: this.#spec, items }],
      operators: [],
      groups: [],
      labels: paths.map((p) => p.join(".")),
      keyed: true,
      flat: paths.every((p) => p.length === 1),
      assemble: assembler(paths),
      limit: undefined,
      offset: undefined,
      ttl: undefined,
      timeout: undefined,
    });
  }

  /** 对本作用域做 SELECT 1，`exists(from(...).where(...))` 就是这样渲染的。 */
  [SUBQUERY](w: SqlWriter): void {
    this.#single(new Literal(integer, 1) as Expr<unknown>, "value")[SUBQUERY](w);
  }

  /** 类的每个字段取自作用域里恰好一个来源的同名列，结果是这个类的实例。 */
  selectAs<C extends object>(type: new () => C): Query<C> {
    const fields = shapeFields(type);
    const items = fields.map((f) => this.#autoColumn(type, f).as(f));
    return makeQuery({
      arms: [{ spec: this.#spec, items }],
      operators: [],
      groups: [],
      labels: fields,
      keyed: true,
      flat: true,
      assemble: (values) => {
        const target = new type() as Record<string, unknown>;
        fields.forEach((f, i) => (target[f] = values[i]));
        return target;
      },
      limit: undefined,
      offset: undefined,
      ttl: undefined,
      timeout: undefined,
    });
  }

  /** 在已连接的来源里找名为 field 的列；0 个或多于 1 个匹配都报错。 */
  #autoColumn(type: Ctor<unknown>, field: string): Expr<unknown> {
    const refs = this.#spec.joins.map((j) => j.ref as Record<string, Expr<unknown>>);
    const hits = refs.map((r) => r[field]).filter((c) => c !== undefined);
    if (hits.length === 1) return hits[0]!;
    throw new Error(
      `${type.name}.${field} ` +
        (hits.length === 0
          ? `has no same-named column in the joined sources; select it with select((...) => ({ ... })).into(...)`
          : `matches ${hits.length} sources; select it with select((...) => ({ ... })).into(...)`),
    );
  }

  /** 只有一列的查询，结果就是该列的值。 */
  #single<V>(item: Expr<unknown>, label: string): Query<V> {
    return makeQuery({
      arms: [{ spec: this.#spec, items: [item] }],
      operators: [],
      groups: [],
      labels: [label],
      keyed: false,
      flat: false,
      assemble: (values) => values[0],
      limit: undefined,
      offset: undefined,
      ttl: undefined,
      timeout: undefined,
    });
  }

  /** 返回修改了部分内容的新作用域。 */
  #with(change: Partial<SelectSpec>): From<T> {
    return makeFrom({ ...this.#spec, ...change }, this.#refs);
  }
}

/** 没有任何数据源和条件的空 SELECT 内容。 */
function emptySpec(): SelectSpec {
  return {
    joins: [],
    where: [],
    groupBy: [],
    rollup: false,
    having: [],
    orderBy: [],
    distinct: false,
    limit: undefined,
    offset: undefined,
    filtered: true,
    skipEmpty: false,
    clauses: [],
  };
}

/** 从表实体、CTE 或派生表开始一个查询。 */
export function from<E>(target: Target<E>): From<[Ref<E>]> {
  const ref = refTo(target);
  return makeFrom({ ...emptySpec(), joins: [{ kind: "FROM", ref, on: Cond.EMPTY }] }, [ref] as [Ref<E>]);
}

/** 命名的 CTE，主体是扁平对象投影的查询。 */
export function cte<E>(name: string, body: Query<E>): Cte<E> {
  return new Cte(name, body);
}

/** 常量行中的一个值。 */
export type ConstantValue = string | number | bigint | boolean;

/**
 * 常量行，例如代码表：每行一个由字面量组成的 SELECT，用 UNION ALL 连接（HANA 上带
 * `FROM DUMMY`）。可用作 CTE 或派生表。列类型取自值；日期、decimal 或带转换器的列要通过
 * `types` 指定。每一行的键必须相同，且都要有值。
 */
export function values<R extends Record<string, ConstantValue>>(rows: readonly R[], types: { readonly [K in keyof R]?: SqlType<R[K]> } = {}): Query<R> {
  const first = rows[0];
  if (first === undefined) throw new Error("values() needs at least one row");
  const keys = Object.keys(first);
  rows.forEach((row, n) => {
    const extra = Object.keys(row).filter((k) => !keys.includes(k));
    if (extra.length > 0) throw new Error(`values() row ${n + 1} has keys the first row lacks: ${extra.join(", ")}`);
    const missing = keys.find((k) => row[k] == null);
    if (missing !== undefined) throw new Error(`values() row ${n + 1} has no value for '${missing}'; every column of constant rows needs one`);
  });
  const columnTypes = keys.map((k) => {
    const given = (types as Record<string, SqlType<unknown> | undefined>)[k];
    if (given !== undefined) return given;
    const numbers = rows.map((r) => r[k]).filter((v) => typeof v === "number");
    return numbers.length > 0 && numbers.some((v) => !Number.isInteger(v)) ? (double as SqlType<unknown>) : typeOf(first[k]);
  });
  const none = emptySpec();
  const arms = rows.map((row) => ({ spec: none, items: keys.map((k, i) => new Literal<unknown>(columnTypes[i] as SqlType<{}>, row[k])) }));
  return makeQuery({
    arms,
    operators: arms.slice(1).map(() => "UNION ALL"),
    groups: [],
    labels: keys,
    keyed: true,
    flat: true,
    assemble: assembler(keys.map((k) => [k])),
    limit: undefined,
    offset: undefined,
    ttl: undefined,
    timeout: undefined,
  });
}

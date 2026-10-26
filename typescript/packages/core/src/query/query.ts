import type { Clause, Dialect } from "../sql/dialect.js";
import type { CalendarDate, CalendarDateTime } from "@internationalized/date";
import type { Decimal } from "decimal.js";
import { and, collect, Cond, outside, withSkipEmpty } from "../sql/cond.js";
import { Aggregate, ExprNode, isWindow, Literal, toOrder, type Expr, type Order, type OrderItem, type SourceColumn } from "../sql/expr.js";
import type { Col, ColOf } from "../sql/typed.js";
import { COLUMN_TYPES, COLUMNS, NAMED, SUBQUERY } from "../internal.js";
import { entityMeta, shapeFields, type Ctor, type Table } from "../schema/metadata.js";
import { conditionFields, conditionTreeCond, parseConditionTree, type ConditionTree } from "./condition-tree.js";
import { filterCondition, type FilterObject } from "./filter.js";
import { Children, isGrouped, Many, type AnyMany, type Grouped } from "./many.js";
import { createRef, Cte, Derived, ownerOf, RELATED, tableSource, type NullableRef, type Ref, type RefOwner, type Relation, type Source } from "./source.js";
import { double, integer, parseColumnText, typeOf, type SqlType } from "../schema/types.js";
import { nested, rowKindOf } from "../sql/aggregation.js";
import { SqlWriter, type Renderable, type Rendered } from "../sql/writer.js";
import { Sharding, type ShardChoice } from "./sharding.js";

/** 只在类型层面携带行类型的标记，运行时不存在。 */
declare const rowType: unique symbol;

// ---------- 全局过滤器 ----------

/** 作用于整个数据库的条件：凡实体是 `type` 或其子类的表都会套用。有名字的可以用 `unfiltered(name)` 单独关闭。 */
export interface SourceFilter {
  readonly name: string | undefined;
  readonly type: Ctor<unknown>;
  /** 条件块，参数是这张表的这次出现。 */
  readonly condition: (ref: Ref<never>) => void;
}

/** FROM 的表加到 WHERE，连接的表加到 ON；`.unfiltered()` 可跳过。条件写成块：`filter(Client, (c) => c.client.eq(tenant()))`。 */
export function filter<E>(type: Ctor<E>, condition: (ref: Ref<E>) => void): SourceFilter;
/**
 * 具名的全局过滤器，其余同上；单个查询或写语句可以用 `.unfiltered(name)` 只关闭它，例如
 * `filter("tenant", Client, (c) => c.client.eq(tenant()))` 后，`from(...).unfiltered("tenant")` 跨租户查询，逻辑删除的条件仍然生效。
 */
export function filter<E>(name: string, type: Ctor<E>, condition: (ref: Ref<E>) => void): SourceFilter;
export function filter<E>(...args: [Ctor<E>, (ref: Ref<E>) => void] | [string, Ctor<E>, (ref: Ref<E>) => void]): SourceFilter {
  const [name, type, condition] = args.length === 3 ? args : [undefined, ...args];
  return Object.freeze({ name, type, condition: condition as (ref: Ref<never>) => void });
}

/**
 * 一条查询或写语句看得到哪些行：全局过滤器、逻辑删除和分表。默认过滤器全部生效、分表按条件选表；`unfiltered()` 关闭过滤器和逻辑删除；
 * `unfiltered("tenant")` 只关闭这些名字的过滤器；`withDeleted()` 只关闭逻辑删除；`allShards()` 读写全部分表，`onShards(...)` 只读写列出的分表。
 */
export interface Filtering extends ShardChoice {
  readonly enabled: boolean;
  readonly disabled: readonly string[];
  readonly logicDelete: boolean;
}

/** 全部生效，分表按条件选表。 */
export const FILTERING_ON: Filtering = Object.freeze({ enabled: true, disabled: Object.freeze([]), logicDelete: true, allShards: false, onShards: Object.freeze([]) });

/** 在 f 的基础上关闭这些名字的过滤器；没有名字时关闭全部过滤器和逻辑删除。分表的选择不变。 */
export function unfilteredBy(f: Filtering, names: readonly string[]): Filtering {
  if (names.length === 0) return Object.freeze({ ...f, enabled: false, disabled: Object.freeze([]), logicDelete: false });
  return Object.freeze({ ...f, disabled: Object.freeze([...f.disabled, ...names]) });
}

/** 在 f 的基础上读写全部分表，或只读写 tables 列出的分表。 */
export function shardsBy(f: Filtering, tables: readonly string[] | "all"): Filtering {
  if (tables === "all") return Object.freeze({ ...f, allShards: true, onShards: Object.freeze([]) });
  if (tables.length === 0) throw new Error("onShards(...) needs at least one table");
  return Object.freeze({ ...f, allShards: false, onShards: Object.freeze([...tables]) });
}

/** 一条语句实际套用的过滤器：全局过滤器和是否加逻辑删除条件；undefined 表示全部关闭。 */
export interface AppliedFilters {
  readonly filters: readonly SourceFilter[];
  readonly logicDelete: boolean;
}

/**
 * 按一条语句的开关从数据库实例配置的过滤器里取舍。关闭的名字不是任何已配置的过滤器时报错，免得拼错的名字悄悄不起作用；
 * 没有数据库实例（configured 为 undefined）时不检查。
 */
export function applyFiltering(configured: readonly SourceFilter[] | undefined, f: Filtering): AppliedFilters | undefined {
  if (!f.enabled) return undefined;
  if (configured !== undefined) {
    const known = configured.map((x) => x.name).filter((n) => n !== undefined);
    for (const name of f.disabled) if (!known.includes(name)) throw new Error(`unfiltered("${name}") names no configured filter; known: ${known.join(", ")}`);
  }
  return { filters: (configured ?? []).filter((x) => x.name === undefined || !f.disabled.includes(x.name)), logicDelete: f.logicDelete };
}

/**
 * 这张表要追加的条件：全局过滤器，再加 `@logicDelete` 列的"未删除"条件；applied 为 undefined 表示 `unfiltered()`，两者都不加。
 * 派生表、CTE 等不是表的来源没有。
 */
export function conditionsFor(applied: AppliedFilters | undefined, ref: object): Cond[] {
  const owner = ownerOf(ref);
  const entity = owner.source.entity;
  if (applied === undefined || entity === undefined || !owner.source.filtered) return [];
  const out = applied.filters.filter((f) => entity === f.type || entity.prototype instanceof f.type).map((f) => collect(() => f.condition(ref as Ref<never>)));
  const active = applied.logicDelete ? activeCondition(ref) : undefined;
  if (active !== undefined) out.push(active);
  return out;
}

/** 表上有逻辑删除列时"未删除"的条件：时间戳列为 IS NULL，其余等于未删除的值；没有时为 undefined。 */
export function activeCondition(ref: object): Cond | undefined {
  const entity = ownerOf(ref).source.entity;
  const logic = entity === undefined ? undefined : entityMeta(entity).writes.logicDelete;
  if (logic === undefined) return undefined;
  const column = (ref as Record<string, Expr<never>>)[logic.property]!;
  return collect(() => (logic.timestamp ? column.isNull() : column.eq(logic.active as never)));
}

// ---------- 不含投影的一个 SELECT ----------

/** FROM 子句中的一项连接。 */
type JoinKind = "FROM" | "JOIN" | "LEFT JOIN" | "RIGHT JOIN" | "FULL JOIN" | "CROSS JOIN";

/** 合法的集合运算；带 ALL 的 INTERSECT、EXCEPT 只有部分方言支持，由方言包提供入口。 */
const SET_OPERATORS = new Set(["UNION", "UNION ALL", "INTERSECT", "INTERSECT ALL", "EXCEPT", "EXCEPT ALL"]);

/** RIGHT JOIN 之后，之前各数据源的列都可能为 null。 */
type NullableAll<T extends readonly unknown[]> = { [K in keyof T]: NullableColumns<T[K]> };

/** 一个数据源的引用里，列都变成可空的；关系等其他成员不变。 */
type NullableColumns<R> = { readonly [K in keyof R]: R[K] extends Col<infer V> ? ColOf<V | null> : R[K] };

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
  /** 窗口函数上的条件（QUALIFY）：渲染时整段查询包一层派生表，在外层按它过滤。 */
  readonly qualify: readonly Cond[];
  readonly orderBy: readonly Order[];
  readonly distinct: boolean;
  readonly limit: number | undefined;
  readonly offset: number | undefined;
  /** 全局过滤器和逻辑删除的开关。 */
  readonly filtering: Filtering;
  /** 是否开启 skipEmpty：与空值比较的条件直接省略。 */
  readonly skipEmpty: boolean;
  readonly clauses: readonly Clause[];
  /** groupJoin 的一对多关系：它们上的集合运算合成按连接键分组的派生表。 */
  readonly groupJoins: readonly Many<any>[];
  /**
   * 按结果列的条件（{@link Query.filterBy}、{@link Query.searchBy}）：渲染时看列里有没有聚合，没有的写进 WHERE，有的写进 HAVING。
   * 构建时还不知道方言，判断不了列里有没有聚合，所以放到渲染时。
   */
  readonly resultFilters: readonly ResultFilter[];
}

/** 一个按结果列的条件：items 是条件用到的列（filterBy、searchBy 是一列，conditionBy 的一棵子树可能几列），condition 是条件。 */
interface ResultFilter {
  readonly items: readonly Expr<unknown>[];
  readonly condition: Cond;
}

/** 校验 limit / offset 是非负整数。 */
function rows(n: number, what: string): number {
  if (!Number.isSafeInteger(n) || n < 0) throw new RangeError(`${what} must be a non-negative integer`);
  return n;
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
export type Projection = { readonly [key: string]: Expr<any> | Projection | AnyMany | Children<any> };

/** 投影的结果类型；略过一对多和关系目标的引用，与运行时一致。 */
export type Shape<P> = {
  -readonly [K in keyof P as K extends string ? (P[K] extends AnyMany | { readonly [RELATED]: true } ? never : K) : never]: P[K] extends Children<infer C>
    ? C[]
    : P[K] extends Expr<infer V>
      ? V
      : Shape<P[K]>;
};

/** 是否是字面量对象，用来区分嵌套投影与类的实例。 */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

/**
 * 把嵌套投影展平成表达式列表、对应的键路径和列名。子数组（{@link Children}）只取父行的键：第一个键占着它的路径，
 * 加载后那里放的是子数组；其余的键路径为 null，组装时跳过。
 */
function flatten(p: object, path: readonly string[], items: Expr<unknown>[], paths: (string[] | null)[], labels: string[], slots: Slot[]): void {
  for (const [key, value] of Object.entries(p)) {
    const label = [...path, key].join(".");
    if (value instanceof ExprNode) {
      items.push(value as Expr<unknown>);
      paths.push([...path, key]);
      labels.push(label);
    } else if (value instanceof Children) {
      slots.push({ children: value, start: items.length });
      value.outer.forEach((e, i) => {
        items.push(e);
        paths.push(i === 0 ? [...path, key] : null);
        labels.push(i === 0 ? label : `${label}.${i + 1}`);
      });
    } else if (value instanceof Many || (isPlainObject(value) && RELATED in value)) {
      continue;
    } else if (isPlainObject(value)) {
      flatten(value, [...path, key], items, paths, labels, slots);
    } else {
      throw new TypeError(`Projection field '${[...path, key].join(".")}' must be an expression or a nested object; use val(...) for constants`);
    }
  }
}

/** 按键路径把一行的值组装回对象，跳过路径为 null 的列；路径都只有一层时走更快的扁平分支。 */
function assembler(paths: readonly (readonly string[] | null)[]): (values: readonly unknown[]) => unknown {
  if (paths.every((p) => p !== null && p.length === 1)) {
    const keys = paths.map((p) => p![0]!);
    return (values) => {
      const row: Record<string, unknown> = {};
      keys.forEach((k, i) => (row[k] = values[i]));
      return row;
    };
  }
  return (values) => {
    const root: Record<string, unknown> = {};
    paths.forEach((path, i) => {
      if (path === null) return;
      let node = root;
      for (const key of path.slice(0, -1)) node = (node[key] ??= {}) as Record<string, unknown>;
      node[path.at(-1)!] = values[i];
    });
    return root;
  };
}

// ---------- 查询 ----------

/** UNION 的一个分支：SELECT 内容、投影项，以及子数组（`list(...)`）的键在投影项里的位置。 */
interface Arm {
  readonly spec: SelectSpec;
  readonly items: readonly Expr<unknown>[];
  readonly slots?: readonly Slot[] | undefined;
}

/** 一个子数组：它的父键从第 start 列开始；加载后子数组写在第 start 列。 */
interface Slot {
  readonly children: Children<unknown>;
  readonly start: number;
}

/** 查询的不可变状态。 */
interface State {
  readonly arms: readonly Arm[];
  readonly operators: readonly string[];
  /**
   * 需要加括号的分支范围。调用顺序就是求值顺序：`a.union(b).intersect(c)` 是 `(a ∪ b) ∩ c`。
   * PostgreSQL、MySQL 的 INTERSECT 比 UNION、EXCEPT 优先，HANA 一律从左到右（实测），
   * 所以凡是两种读法结果可能不同的地方都写出括号。
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

  /** INTERSECT（两边都有的行，去重），规则同 {@link unionAll}。 */
  intersect(other: Query<R>): Query<R> {
    return this.#combine("INTERSECT", other);
  }

  /** EXCEPT（左边有、右边没有的行，去重），规则同 {@link unionAll}。 */
  except(other: Query<R>): Query<R> {
    return this.#combine("EXCEPT", other);
  }

  /**
   * 用 operator 合并两个查询，供方言包使用（例如 PostgreSQL 的 `intersectAll`）；方言不支持的运算在渲染时报错。
   * 规则同 {@link unionAll}。
   */
  combine(operator: string, other: Query<R>): Query<R> {
    if (!SET_OPERATORS.has(operator)) throw new Error(`Unknown set operator ${operator}`);
    return this.#combine(operator, other);
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
    if (hasChildren(this) || hasChildren(other)) throw new Error("A query with list(...) cannot be part of a UNION");
    let arms = b.arms;
    if (a.keyed && b.keyed && a.labels.some((l, i) => l !== b.labels[i])) {
      const position = new Map(b.labels.map((l, i) => [l, i]));
      if (a.labels.some((l) => !position.has(l))) throw new Error(`UNION arms select different columns: ${a.labels} and ${b.labels}`);
      arms = b.arms.map((arm) => ({ spec: arm.spec, items: a.labels.map((l) => arm.items[position.get(l)!]!) }));
    }
    const shift = a.arms.length;
    const groups: (readonly [number, number])[] = [...a.groups];
    // 左边有比 INTERSECT 低的运算时，PostgreSQL、MySQL 会先算 INTERSECT。
    if (operator.startsWith("INTERSECT") && a.operators.some((op) => op !== operator)) groups.push([0, shift - 1]);
    groups.push(...b.groups.map(([first, last]) => [first + shift, last + shift] as const));
    // 右边与本运算不同时，或本运算是不满足结合律的 EXCEPT 时，右边自成一组。
    if (b.operators.some((op) => op !== operator || op.startsWith("EXCEPT"))) groups.push([shift, shift + b.arms.length - 1]);
    return makeQuery({ ...a, arms: [...a.arms, ...arms], operators: [...a.operators, operator, ...b.operators], groups, ttl: a.ttl, timeout: a.timeout });
  }

  // ---------- 动态列与排序 ----------

  /**
   * 不取这些列：SQL 里写成 NULL，结果里为 null，结果的形态不变。只被它们用到的导航关系不再连接；
   * 有分组时，与它们相同的分组键（以及按这些键的排序）一起去掉，于是同一个查询可以按用户选的维度汇总。
   * 名字是结果的键，嵌套对象的键去掉它下面的全部列；不分大小写和下划线，不存在的名字报错。
   */
  exclude<K extends keyof R & string>(...names: K[]): Query<ExcludedKeys<R, K>> {
    return this.#omit(this.#positionsOf(names, "exclude"));
  }

  /** condition 为真时才 {@link exclude}，例如 `.excludeIf(!withText, "text")`。 */
  excludeIf<K extends keyof R & string>(condition: boolean, ...names: K[]): Query<ExcludedKeys<R, K>> {
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
   * 按排序串排序：逗号分隔，每项是一个结果列名，前面带减号是倒序（同 JSON:API，前端 useTableQuery 发的
   * `sort=-total,material` 就是这种写法），也可以写成 `"total desc, material"`。
   * 只能按结果里的列排序，相当于白名单，所以可以直接传入用户的排序参数；未知列名或写法报错。
   * spec 为空时保持原来的 ORDER BY，否则替换它。合并后的查询先放进 CTE 再排序。
   */
  sortBy(spec: string | null | undefined): Query<R> {
    if (spec == null || spec.trim() === "") return this;
    if (this.#s.arms.length > 1) throw new Error("sortBy(...) works on a single SELECT; wrap the UNION in a CTE first");
    const items = this.#s.arms[0]!.items;
    const orders: Order[] = [];
    for (const part of spec.split(",")) {
      const text = part.trim();
      if (text === "") continue;
      const minus = text.startsWith("-");
      const words = (minus ? text.slice(1) : text).trim().split(/\s+/);
      const name = words[0]!;
      const direction = minus ? "desc" : (words[1] ?? "asc").toLowerCase();
      if (name === "" || words.length > (minus ? 1 : 2) || (direction !== "asc" && direction !== "desc")) {
        throw new TypeError(`Cannot read sort item '${text}'; write 'column', '-column', 'column asc' or 'column desc'`);
      }
      const at = [...this.#positionsOf([name], "sortBy")];
      if (at.length !== 1) throw new TypeError(`Cannot sort by '${name}'; it names a nested object, sort by one of its columns`);
      const item = items[at[0]!]!;
      if (item instanceof Omitted) throw new TypeError(`Cannot sort by '${name}', it is excluded`);
      orders.push(direction === "desc" ? item.desc() : item.asc());
    }
    return this.#withSpec({ ...this.#s.arms[0]!.spec, orderBy: orders });
  }

  /**
   * 按结果列筛选，给表头的多选筛选用：键是结果列名（同 {@link sortBy}，相当于白名单），值是选中的值，这一列的值在其中（IN）。
   * 值是线上的文字（0015），按列的类型读（`parseColumnText`）；空白的值跳过，没有值的列不加条件，filter 为空时保持不变。
   * 逐行计算的列写进 WHERE，聚合的列写进 HAVING（渲染时判断），窗口函数的列不能筛选。
   */
  filterBy(filter: Readonly<Record<string, readonly string[] | undefined>> | null | undefined): Query<R> {
    let q: Query<R> = this;
    for (const [name, texts] of Object.entries(filter ?? {})) {
      const item = this.#filterable(name, "filterBy");
      const values = (texts ?? []).map((t) => parseColumnText(item.type as SqlType<unknown>, t)).filter((v) => v != null);
      if (values.length === 0) continue;
      q = q.#withResultFilter([item], collect(() => (item as ExprNode<never>).in(values as never[])));
    }
    return q;
  }

  /**
   * 按结果列的关键字筛选，给表头的关键字筛选用：键是结果列名，值是关键字，这一列的文字包含它（LIKE，% 和 _ 按普通字符）。
   * 只能用在文字列上；空白的关键字不加条件。其余同 {@link filterBy}。
   */
  searchBy(search: Readonly<Record<string, string | undefined>> | null | undefined): Query<R> {
    let q: Query<R> = this;
    for (const [name, keyword] of Object.entries(search ?? {})) {
      const item = this.#filterable(name, "searchBy");
      if (keyword === undefined || keyword.trim() === "") continue;
      if (item.type.kind !== "text") throw new TypeError(`searchBy(...): '${name}' is not a text column; filter it with filterBy(...)`);
      q = q.#withResultFilter([item], collect(() => (item as ExprNode<string>).contains(keyword.trim())));
    }
    return q;
  }

  /**
   * 按条件树筛选，给前端的条件构造器（vue-tsx-pro 的 FilterBar 高级筛选）用：tree 是 `ConditionTree`，或它的 JSON 文字
   * （地址栏的 `condition` 参数）。字段是结果列名（白名单，同 {@link filterBy}），值按列的类型读；
   * 没填完整的比较与空的组不加条件，tree 为空时保持不变。最外层的 all 拆成几个条件，各自判断进 WHERE 还是 HAVING；
   * 一棵子树里既有逐行计算的列又有聚合的列时报错。形状不对、比较方式不认识时报 TypeError（`db.table` 换成 `query.request-invalid`）。
   */
  conditionBy(tree: ConditionTree | string | null | undefined): Query<R> {
    if (tree == null || (typeof tree === "string" && tree.trim() === "")) return this;
    const parsed = parseConditionTree(tree);
    let q: Query<R> = this;
    for (const part of "all" in parsed ? parsed.all : [parsed]) {
      const items = new Map(conditionFields(part).map((name) => [name, this.#filterable(name, "conditionBy")] as const));
      const condition = conditionTreeCond(part, (name) => items.get(name) as ExprNode<never>);
      if (!condition.isEmpty) q = q.#withResultFilter([...items.values()], condition);
    }
    return q;
  }

  /** 能按它筛选的结果列：单段查询里的一列，不是排除掉的、不是嵌套对象、不是子数组。 */
  #filterable(name: string, use: string): Expr<unknown> {
    if (this.#s.arms.length > 1) throw new Error(`${use}(...) works on a single SELECT; wrap the UNION in a CTE first`);
    const at = [...this.#positionsOf([name], use)];
    if (at.length !== 1) throw new Error(`${use}(...): '${name}' names a nested object; use one of its columns`);
    const arm = this.#s.arms[0]!;
    const i = at[0]!;
    if ((arm.slots ?? []).some((s) => i >= s.start && i < s.start + s.children.outer.length)) throw new Error(`${use}(...): '${name}' is a child list`);
    const item = arm.items[i]!;
    if (item instanceof Omitted) throw new TypeError(`${use}(...): '${name}' is excluded`);
    return item;
  }

  /** 追加一个按结果列的条件。 */
  #withResultFilter(items: readonly Expr<unknown>[], condition: Cond): Query<R> {
    const spec = this.#s.arms[0]!.spec;
    return this.#withSpec({ ...spec, resultFilters: [...spec.resultFilters, { items, condition }] });
  }

  /** 名字在结果里的列位置：同名的列，或嵌套对象下的全部列；未知的名字报错并列出可用的列。 */
  #positionsOf(names: readonly string[], use: string): Set<number> {
    const labels = this.#s.labels.map(normalize);
    const found = new Set<number>();
    for (const name of names) {
      const key = normalize(name);
      const hits = labels.flatMap((label, i) => (label === key || label.startsWith(`${key}.`) ? [i] : []));
      if (hits.length === 0) throw new TypeError(`${use}(...): unknown column '${name}'; the query has [${this.#s.labels.join(", ")}]`);
      for (const i of hits) found.add(i);
    }
    return found;
  }

  /** 把各段里这些位置的列换成 NULL。 */
  #omit(positions: Iterable<number>): Query<never> {
    const at = new Set(positions);
    if (at.size === 0) return this as never;
    // 子数组的父键不是结果列，一直保留
    const covered = (arm: Arm, i: number) => (arm.slots ?? []).some((s) => i >= s.start && i < s.start + s.children.outer.length);
    const arms = this.#s.arms.map((arm) => ({
      spec: arm.spec,
      items: arm.items.map((e, i) => (at.has(i) && !covered(arm, i) && !(e instanceof Omitted) ? new Omitted(e) : e)),
      slots: arm.slots,
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
    return makeQuery({ ...this.#s, arms: [{ ...this.#s.arms[0]!, spec }] });
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

/** `exclude(...names)` 之后的结果形态：names 里的键按 {@link Excluded} 变成可空，其余不变。 */
export type ExcludedKeys<R, K extends keyof R> = { [P in keyof R]: P extends K ? Excluded<R[P]> : R[P] };

/** 列名与名字的比较形式：去掉下划线并忽略大小写，与 Java 一致。 */
function normalize(name: string): string {
  return name.replaceAll("_", "").toLowerCase();
}

/**
 * {@link Query.exclude} 去掉的一列：SQL 里写成 NULL，所以结果的形态不变，这一列读出来是 null。
 * 保留原来的表达式，渲染时用它找出要一起去掉的分组键。
 */
class Omitted extends ExprNode<unknown> {
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

/** 带列名渲染查询，用于 INSERT ... SELECT 等嵌在别的语句里的查询。 */
export function renderNamed(w: SqlWriter, q: Query<unknown>): void {
  renderQuery(w, stateOf(q), true, true);
}

/** 把查询渲染成完整语句。 */
export function renderStatement(w: SqlWriter, q: Query<unknown>): Rendered {
  renderQuery(w, stateOf(q), true, true);
  return w.statement();
}

/**
 * 查询的总行数。能直接数行时（{@link countsRows}）把投影换成 COUNT(*)、去掉 ORDER BY：投影里的标量子查询、只为投影连接的
 * 导航关系（多对一、`first()`，都至多一行）、groupJoin 的派生表随之不写。否则对查询套一层 SELECT COUNT(*)，
 * 除非 LIMIT 需要，否则去掉 ORDER BY。
 */
export function renderCount(w: SqlWriter, q: Query<unknown>): Rendered {
  const s = stateOf(q);
  if (countsRows(w, s)) {
    renderArm(w, { spec: s.arms[0]!.spec, items: [new Aggregate<number>(integer, "COUNT", false, [])] }, ["count"], false, false);
    return w.statement();
  }
  w.sql("SELECT COUNT(*) FROM (");
  renderQuery(w, s, false, true);
  w.sql(") ").alias(w.aliasFor({}, "counted"));
  return w.statement();
}

/**
 * 能不能直接数行：单段 SELECT，没有分组、DISTINCT、HAVING、QUALIFY、LIMIT、OFFSET 和方言子句，投影和按结果列的条件里没有聚合
 * （没有分组的聚合只出一行）。窗口函数不改变行数，可以去掉。
 */
function countsRows(w: SqlWriter, s: State): boolean {
  if (s.arms.length !== 1) return false;
  const arm = s.arms[0]!;
  const spec = arm.spec;
  if (spec.groupBy.length > 0 || spec.distinct || !and(...spec.having).isEmpty || !and(...spec.qualify).isEmpty) return false;
  if (spec.limit !== undefined || spec.offset !== undefined || spec.clauses.length > 0) return false;
  return [...arm.items, ...spec.resultFilters.flatMap((f) => f.items)].every((e) => rowKindOf(w, e) !== "aggregate");
}

/** `SELECT CASE WHEN EXISTS (查询) THEN 1 ELSE 0 END`：总是返回一行，查询找到行时为 1。 */
export function renderExists(w: SqlWriter, q: Query<unknown>): Rendered {
  w.sql("SELECT CASE WHEN EXISTS (");
  renderQuery(w, stateOf(q), true, false);
  w.sql(") THEN 1 ELSE 0 END");
  w.dialect.fromNothing(w);
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

/** 是否带子数组（`list(...)`）；这样的查询要读完全部行才能组装，不能流式读取。 */
export function hasChildren(q: Query<unknown>): boolean {
  return (stateOf(q).arms[0]!.slots ?? []).length > 0;
}

/**
 * 把解码后的全部行组装成结果。带子数组时先加载子行：run 执行一条子查询（沿用本查询的缓存时长和超时）
 * 并返回组装好的行。rows 不会被修改，缓存里的行可以直接传入。
 */
export async function assembleRows<R>(q: Query<R>, rows: readonly (readonly unknown[])[], run: (child: Query<never>) => Promise<readonly unknown[]>): Promise<R[]> {
  const s = stateOf(q);
  const slots = s.arms[0]!.slots ?? [];
  let source = rows;
  if (slots.length > 0) {
    const copies = rows.map((r) => [...r]);
    for (const slot of slots) {
      await slot.children.attach(copies, slot.start, (child) => {
        const c = stateOf(child);
        return run(makeQuery({ ...c, ttl: s.ttl, timeout: s.timeout })) as never;
      });
    }
    source = copies;
  }
  return source.map((r) => s.assemble(r) as R);
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
  // 子查询里的聚合属于子查询自己（rowKindOf 探查外层的列时）
  nested(w, () => {
    s.arms.forEach((arm, i) => {
      if (i > 0) w.sql(`\n${w.dialect.setOperator(s.operators[i - 1]!)}\n`);
      w.sql(w.dialect.groupOpen.repeat(s.groups.filter(([first]) => first === i).length));
      renderArm(w, arm, s.labels, i === 0 && labeled, ordered);
      w.sql(w.dialect.groupClose.repeat(s.groups.filter(([, last]) => last === i).length));
    });
    if (s.arms.length > 1) w.dialect.limit(w, s.limit, s.offset);
  });
}

/** 按结果列的条件分成写进 WHERE 的（逐行计算的列）和写进 HAVING 的（聚合的列）；窗口函数的列不能这样筛选。 */
function splitResultFilters(w: SqlWriter, s: SelectSpec): { readonly where: Cond[]; readonly having: Cond[] } {
  const where: Cond[] = [];
  const having: Cond[] = [];
  for (const f of s.resultFilters) {
    const kinds = new Set(f.items.map((item) => rowKindOf(w, item)));
    if (kinds.has("window")) throw new Error("filterBy(...), searchBy(...) and conditionBy(...) cannot filter a window function column; put the query in a CTE and filter that");
    // 一个条件里逐行的列与聚合的列混在一起（同一个 any、not 里）：WHERE、HAVING 都放不下
    if (kinds.size > 1) throw new Error("conditionBy(...): one branch mixes plain and aggregated columns; split it at the top-level all, or put the query in a CTE and filter that");
    (kinds.has("aggregate") ? having : where).push(f.condition);
  }
  return { where, having };
}

/**
 * 渲染一个 SELECT 分支；有 QUALIFY 时交给 {@link renderQualified}。
 * @param labeled 为 false 时列不带名字
 * @param ordered 为 false 时，对没有 LIMIT 或 OFFSET 的分支去掉 ORDER BY
 */
function renderArm(w: SqlWriter, arm: Arm, labels: readonly string[], labeled: boolean, ordered: boolean): void {
  const shards = Sharding.enter(w, arm.spec.filtering);
  try {
    renderSelect(w, arm, labels, labeled, ordered);
  } finally {
    Sharding.leave(w, shards);
  }
}

/** {@link renderArm} 的主体；分表的选择已经登记在写入器上。 */
function renderSelect(w: SqlWriter, arm: Arm, labels: readonly string[], labeled: boolean, ordered: boolean): void {
  const s = arm.spec;
  if (!and(...s.qualify).isEmpty) return renderQualified(w, arm, labels, labeled, ordered);
  // 按连接顺序分配别名，这样即使 SELECT 先提到被连接的表，FROM 的表仍得到最简单的名字。
  for (const j of s.joins) ownerOf(j.ref).alias(w);
  const dialect: Dialect = w.dialect;
  for (const c of s.clauses) {
    if (c.dialect !== undefined && !(dialect instanceof c.dialect)) throw new Error(`A ${c.dialect.name} clause cannot run on ${w.dialect.name}`);
  }
  w.sql("SELECT ");
  w.dialect.hints(w, s.clauses.filter((c) => c.position === "hint"));
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
  const byColumn = splitResultFilters(w, s);
  const filtered = [...byColumn.where, ...byColumn.having];
  const groupJoins = collectGroupJoins(w, arm, groupBy, orderBy, filtered);
  const previous = w.substitute(undefined);
  w.substitute(
    groupJoins.length === 0
      ? previous
      : (node) => {
          for (const g of groupJoins) {
            const column = g.columns.get(node);
            if (column !== undefined) {
              return { render: (x: SqlWriter) => (node as Grouped).renderGrouped(x, { render: (y: SqlWriter) => void y.alias(g.alias).sql(".").identifier(column) }) };
            }
          }
          return previous === undefined ? node : previous(node);
        },
  );
  const outer = w.inline(true);
  arm.items.forEach((item, i) => {
    if (i > 0) w.sql(", ");
    w.render(item);
    if (labeled) w.sql(" AS ").identifier(labels[i]!);
  });
  w.inline(false);
  if (s.joins.length === 0) w.dialect.fromNothing(w);
  const filters = applyFiltering(w.settings.filters as readonly SourceFilter[] | undefined, s.filtering);
  // FROM、CROSS JOIN 的表的过滤条件先记下：后面有 RIGHT JOIN 时它们变成可空的一侧，
  // 条件要放进那个 RIGHT JOIN 的 ON，否则 WHERE 会把补出来的行过滤掉。
  let pending: Cond[] = [];
  const implicit = usedRelations(w, arm, groupBy, orderBy, filtered);
  Sharding.of(w).routeSelect(w, [...s.joins.map((j) => ownerOf(j.ref)), ...[...implicit].map((r) => ownerOf(r.ref))], s.where, s.filtering);
  for (const j of s.joins) {
    const extra = conditionsFor(filters, j.ref);
    const owner = ownerOf(j.ref);
    if (j.kind === "FROM" || j.kind === "CROSS JOIN") {
      w.sql(`\n${j.kind} `);
      owner.renderSource(w);
      pending.push(...extra);
    } else if (j.kind === "FULL JOIN") {
      if (!w.dialect.supportsFullJoin) throw new Error(`${w.dialect.name} has no FULL JOIN`);
      // 两侧都会补空行：过滤条件放在 ON 里会让被过滤掉的行作为不匹配的行留下，放在 WHERE 里会去掉补出来的行，
      // 所以有全局过滤条件的表不能参与 FULL JOIN。
      if (pending.length > 0 || extra.length > 0) {
        throw new Error("FULL JOIN cannot keep the global filters of its tables; use unfiltered() and filter each side in a CTE or derived table");
      }
      w.sql("\nFULL JOIN ");
      owner.renderSource(w);
      w.sql(" ON ").render(j.on);
    } else if (j.kind === "RIGHT JOIN") {
      w.sql("\nRIGHT JOIN ");
      owner.renderSource(w);
      w.sql(" ON ").render(and(j.on, ...pending));
      pending = [...extra];
    } else {
      w.sql(`\n${j.kind} `);
      owner.renderSource(w);
      // 被连接的表的过滤条件放进 ON，LEFT JOIN 才仍是 LEFT JOIN。
      w.sql(" ON ").render(and(j.on, ...extra));
    }
    emitRelations(w, owner, implicit, filters);
    for (const g of groupJoins) if (g.owner === owner) emitGroupJoin(w, g);
  }
  const whereAll = and(...pending, ...s.where, ...byColumn.where);
  if (!whereAll.isEmpty) w.sql("\nWHERE ").render(whereAll);
  w.inline(grouped);
  if (groupBy.length > 0) {
    w.sql("\nGROUP BY ");
    w.dialect.groupBy(w, groupBy, s.rollup);
  }
  const having = and(...s.having, ...byColumn.having);
  if (!having.isEmpty) w.sql("\nHAVING ").render(having);
  const limited = s.limit !== undefined || s.offset !== undefined;
  if (orderBy.length > 0 && (ordered || limited)) w.sql("\nORDER BY ").join(orderBy, ", ");
  w.inline(outer);
  w.dialect.limit(w, s.limit, s.offset);
  for (const c of s.clauses) if (c.position === "end") w.sql("\n").render(c);
  w.substitute(previous);
}

/** groupJoin 的一个关系：所属的表出现、派生表的别名、派生表，以及每个集合运算节点对应的列名。 */
interface GroupJoin {
  readonly owner: RefOwner;
  readonly alias: string;
  readonly grouping: { readonly query: Query<unknown>; readonly outer: readonly Expr<unknown>[] };
  readonly columns: Map<Renderable, string>;
}

/**
 * 这一段 groupJoin 的关系：用探查写入器找出各子句里用到的、属于这些关系的集合运算（count、sum、any 等），
 * 每个关系生成一个按连接键分组的派生表，每个运算一列。没有用到运算的关系不连接。
 */
function collectGroupJoins(w: SqlWriter, arm: Arm, groupBy: readonly Expr<unknown>[], orderBy: readonly Order[], filtered: readonly Cond[]): GroupJoin[] {
  const s = arm.spec;
  if (s.groupJoins.length === 0) return [];
  const found = new Map<Many<any>, Grouped[]>();
  const probe = w.probe();
  probe.substitute((node) => {
    if (!isGrouped(node)) return node;
    const relation = s.groupJoins.find((r) => r.sameRelation(node.relation));
    if (relation === undefined) return node;
    const ops = found.get(relation) ?? [];
    if (!ops.includes(node)) ops.push(node);
    found.set(relation, ops);
    return { render: () => {} };
  });
  for (const e of arm.items) probe.render(e);
  for (const j of s.joins) if (!j.on.isEmpty) probe.render(j.on);
  for (const c of [...s.where, ...s.having, ...filtered]) if (!c.isEmpty) probe.render(c);
  for (const g of groupBy) probe.render(g);
  for (const o of orderBy) probe.render(o);
  return [...found].map(([relation, ops]) => {
    if (!s.joins.some((j) => ownerOf(j.ref) === relation.owner)) throw new Error("groupJoin(...) needs a relation of a table in from(...) or a join");
    const name = relation.owner.source.entity?.name ?? "t";
    return {
      owner: relation.owner,
      alias: w.aliasFor({}, `${name}_g`),
      grouping: relation.grouping(ops),
      columns: new Map(ops.map((op, i) => [op as Renderable, `c${i + 1}`])),
    };
  });
}

/** 写出 `LEFT JOIN (派生表) 别名 ON 别名.k1 = 所属列1 AND ...`。 */
function emitGroupJoin(w: SqlWriter, g: GroupJoin): void {
  w.sql("\nLEFT JOIN (\n");
  renderQuery(w, stateOf(g.grouping.query), true, true);
  w.sql("\n) ").alias(g.alias).sql(" ON ");
  g.grouping.outer.forEach((o, i) => {
    if (i > 0) w.sql(" AND ");
    w.alias(g.alias).sql(".").identifier(`k${i + 1}`).sql(" = ").render(o);
  });
}

/**
 * 带 QUALIFY 的一段。HANA 没有 QUALIFY 子句，所以三个库都把这段查询包成派生表：条件里的窗口函数、
 * 不在结果里的排序项作为额外的列放进去，外层按条件过滤，再排序和分页。条件只能用窗口函数，
 * 其他条件应写在 where 或 having 里，否则外层引用不到原来的表。
 */
function renderQualified(w: SqlWriter, arm: Arm, labels: readonly string[], labeled: boolean, ordered: boolean): void {
  const s = arm.spec;
  const items = [...arm.items];
  const names = [...labels];
  const columns = new Map<Renderable, string>();
  /** 把 e 作为派生表的一列追加进去，列名是 prefix 加序号。 */
  const hidden = (e: Expr<unknown>, prefix: string): string => {
    const name = prefix + (items.length + 1);
    items.push(e);
    names.push(name);
    return name;
  };
  const probe = w.probe();
  probe.substitute((node) => {
    if (!isWindow(node)) return node;
    if (!columns.has(node)) columns.set(node, hidden(node as Expr<unknown>, "_q"));
    return { render: () => {} };
  });
  for (const c of s.qualify) probe.render(c);
  if (probe.touched.size > 0) throw new Error("qualify(...) may only compare window functions; put other conditions in where(...) or having(...)");
  const excluded = excludedKeys(w, arm);
  const texts = w.probe();
  const orderBy: Order[] = [];
  for (const o of s.orderBy) {
    const text = textOf(texts, o.expr);
    if (excluded.has(text)) continue;
    orderBy.push(o);
    const at = arm.items.findIndex((item) => !(item instanceof Omitted) && textOf(texts, item) === text);
    if (at >= 0) columns.set(o.expr, labels[at]!);
    else if (s.distinct) throw new Error("With distinct() and qualify(...), order by columns that are selected");
    else if (!columns.has(o.expr)) columns.set(o.expr, hidden(o.expr, "_o"));
  }
  const alias = w.aliasFor({}, "q");
  w.sql("SELECT ");
  labels.forEach((label, i) => {
    if (i > 0) w.sql(", ");
    w.alias(alias).sql(".").identifier(label);
    if (labeled) w.sql(" AS ").identifier(label);
  });
  w.sql("\nFROM (\n");
  const inner: SelectSpec = { ...s, qualify: [], orderBy: [], limit: undefined, offset: undefined, clauses: s.clauses.filter((c) => c.position !== "end") };
  renderArm(w, { spec: inner, items, slots: arm.slots }, names, true, false);
  w.sql("\n) ").alias(alias);
  const previous = w.substitute((node) => {
    const name = columns.get(node);
    return name === undefined ? node : { render: (x: SqlWriter) => void x.alias(alias).sql(".").identifier(name) };
  });
  w.sql("\nWHERE ").render(and(...s.qualify));
  const limited = s.limit !== undefined || s.offset !== undefined;
  if (orderBy.length > 0 && (ordered || limited)) w.sql("\nORDER BY ").join(orderBy, ", ");
  w.substitute(previous);
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
 * groupBy 和 orderBy 是去掉被排除的列之后实际要写出的，filtered 是按结果列的条件。
 */
function usedRelations(w: SqlWriter, arm: Arm, groupBy: readonly Expr<unknown>[], orderBy: readonly Order[], filtered: readonly Cond[]): Set<Relation> {
  const s = arm.spec;
  const parts: Renderable[] = [...arm.items, ...s.joins.map((j) => j.on), ...s.where, ...s.having, ...filtered, ...groupBy, ...orderBy, ...s.clauses];
  return implicitJoins(
    w,
    s.joins.map((j) => ownerOf(j.ref)),
    parts,
  );
}

/**
 * roots 出发的导航关系里，parts 渲染时真正用到的那些：先用探查写入器把 parts 试渲染一遍，记下引用到的数据源；
 * 用到了某个关系的目标（或它更深的关系）就连接它，并把它的连接条件也试渲染，直到不再增加。查询和写语句共用。
 */
export function implicitJoins(w: SqlWriter, roots: readonly RefOwner[], parts: readonly Renderable[]): Set<Relation> {
  if (roots.every((r) => r.relations.length === 0)) return new Set();
  const probe = w.probe();
  for (const p of parts) if (!(p instanceof Cond && p.isEmpty)) probe.render(p);
  const used = new Set<Relation>();
  let grew = true;
  while (grew) {
    grew = false;
    for (const root of roots) grew = collectUsed(root, probe, used) || grew;
  }
  return used;
}

/** 把 owner 出发的、用到了的关系加入 used，并试渲染它们的连接条件；有新增时返回 true。 */
function collectUsed(owner: RefOwner, probe: SqlWriter, used: Set<Relation>): boolean {
  let grew = false;
  for (const r of owner.relations) {
    if (!used.has(r) && reaches(r.owner, probe.touched)) {
      used.add(r);
      probe.render(r.on);
      grew = true;
    }
    if (used.has(r)) grew = collectUsed(r.owner, probe, used) || grew;
  }
  return grew;
}

/** owner 或从它出发的某个关系的目标是否被引用到。 */
function reaches(owner: RefOwner, touched: ReadonlySet<object>): boolean {
  return touched.has(owner) || owner.relations.some((r) => reaches(r.owner, touched));
}

/** 紧跟在 owner 的 FROM 或 JOIN 之后写出它用到的隐式连接（LEFT JOIN），再写更深的关系；全局过滤器放进 ON。 */
export function emitRelations(w: SqlWriter, owner: RefOwner, used: ReadonlySet<Relation>, filters: AppliedFilters | undefined): void {
  for (const r of owner.relations) {
    if (!used.has(r)) continue;
    w.sql("\nLEFT JOIN ");
    r.owner.renderSource(w);
    w.sql(" ON ").render(and(r.on, ...conditionsFor(filters, r.ref)));
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

  /** INNER JOIN；`on` 是条件块，参数依次是已连接的全部数据源：`join(Vbak, (l, k) => k.order.eq(l.order))`。 */
  join<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => void): From<[...T, Ref<E>]> {
    return this.#join("JOIN", target, on);
  }

  /** LEFT JOIN；新数据源的列在回调里是可空的。 */
  leftJoin<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => void): From<[...T, NullableRef<E>]> {
    return this.#join("LEFT JOIN", target, on);
  }

  /** RIGHT JOIN；之前各数据源的列在之后的回调里是可空的，新数据源的行全部保留。 */
  rightJoin<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => void): From<[...NullableAll<T>, Ref<E>]> {
    return this.#join("RIGHT JOIN", target, on);
  }

  /** FULL JOIN（HANA、PostgreSQL）：两侧都可能没有匹配，所有参数的列都变成可空；有全局过滤条件的表不能参与。 */
  fullJoin<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => void): From<[...NullableAll<T>, NullableRef<E>]> {
    return this.#join("FULL JOIN", target, on);
  }

  /** CROSS JOIN（笛卡尔积），不需要条件。 */
  crossJoin<E>(target: Target<E>): From<[...T, Ref<E>]> {
    return this.#join("CROSS JOIN", target, undefined);
  }

  /** 加入一个数据源；除 CROSS JOIN 外，连接条件不能为空。 */
  #join<U extends readonly unknown[], E>(kind: JoinKind, target: Target<E>, on: ((...refs: [...T, Ref<E>]) => void) | undefined): From<U> {
    const ref = refTo(target);
    const refs = [...this.#refs, ref] as unknown as [...T, Ref<E>];
    const condition = on === undefined ? Cond.EMPTY : this.#condition(() => on(...refs));
    if (kind !== "CROSS JOIN" && condition.isEmpty) throw new Error(`${kind} needs a condition; use crossJoin for a cartesian product`);
    return makeFrom({ ...this.#spec, joins: [...this.#spec.joins, { kind, ref, on: condition }] }, refs as unknown as U);
  }

  /**
   * WHERE 条件块：块里每个条件之间是 AND，可选条件直接写 `if`：
   * `where((l) => { l.unit.eq("KG"); if (from) l.createdOn.ge(from); })`。多次调用之间也是 AND。
   */
  where(cond: (...refs: T) => void): From<T>;
  /**
   * 按条件对象筛选：对象的每个字段变成一个条件，默认与同名列比较相等（数组用 IN），用 `@where` 指定比较方式和列；
   * 值为空的字段不产生条件，所以可以直接传入请求参数：`from(Lips).where(search)`。字段名找不到对应的列时报错，
   * 不作为条件的字段（分页、排序）标上 `@ignore`。
   */
  where<F extends FilterObject>(filter: F): From<T>;
  where(arg: ((...refs: T) => void) | FilterObject): From<T> {
    const cond = typeof arg === "function" ? this.#condition(() => arg(...this.#refs)) : filterCondition(this.#refs as readonly object[], arg);
    return this.#with({ where: [...this.#spec.where, cond] });
  }

  /** GROUP BY；多次调用的键依次追加。 */
  groupBy(keys: (...refs: T) => Expr<unknown> | readonly Expr<unknown>[]): From<T> {
    if (this.#spec.rollup) throw new Error("groupByRollup already lists every grouping key");
    return this.#with({ groupBy: [...this.#spec.groupBy, ...list(outside(() => keys(...this.#refs)))] });
  }

  /** 对全部键做 GROUP BY ROLLUP：按键的每个前缀小计，再加总计。 */
  groupByRollup(keys: (...refs: T) => Expr<unknown> | readonly Expr<unknown>[]): From<T> {
    if (this.#spec.groupBy.length > 0) throw new Error("groupByRollup must list every grouping key; it cannot follow groupBy");
    const every = list(outside(() => keys(...this.#refs)));
    if (every.length === 0) throw new Error("groupByRollup needs at least one key");
    return this.#with({ groupBy: every, rollup: true });
  }

  /** HAVING 条件块；多次调用之间是 AND。 */
  having(cond: (...refs: T) => void): From<T> {
    return this.#with({ having: [...this.#spec.having, this.#condition(() => cond(...this.#refs))] });
  }

  /**
   * 把一对多关系上的集合运算（count、sum、any 等）合成一个按连接键分组的派生表，LEFT JOIN 一次，
   * 代替每个运算一个相关子查询：`groupJoin((m) => m.deliveries)`。同一关系上的 where 筛选写成各自聚合的 CASE WHEN。
   */
  groupJoin(relation: (...refs: T) => AnyMany): From<T> {
    const many = outside(() => relation(...this.#refs));
    if (!(many instanceof Many)) throw new TypeError("groupJoin(...) needs a one-to-many relation such as (m) => m.deliveries");
    return this.#with({ groupJoins: [...this.#spec.groupJoins, many] });
  }

  /**
   * 窗口函数上的条件（QUALIFY），例如每组取最新一行：
   * `qualify((l) => rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc())).eq(1))`。
   * 查询会包成派生表，在外层按条件过滤；条件只能用窗口函数，其他条件写在 where 或 having 里。多次调用之间是 AND。
   */
  qualify(cond: (...refs: T) => void): From<T> {
    return this.#with({ qualify: [...this.#spec.qualify, this.#condition(() => cond(...this.#refs))] });
  }

  /** 表达式默认升序；需要其他排序用 `.desc()`、`.nullsLast()` 等。 */
  orderBy(items: (...refs: T) => OrderItem | readonly OrderItem[]): From<T> {
    return this.#with({ orderBy: [...this.#spec.orderBy, ...list(outside(() => items(...this.#refs))).map(toOrder)] });
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

  /**
   * 本查询不套用数据库实例的全局过滤器，也不加逻辑删除的条件；给出名字时只关闭这些名字的过滤器（见具名的 `filter(name, ...)`），
   * 例如跨租户但仍排除已删除的行：`unfiltered("tenant")`。
   */
  unfiltered(...names: string[]): From<T> {
    return this.#with({ filtering: unfilteredBy(this.#spec.filtering, names) });
  }

  /** 本查询不加逻辑删除的条件，能看到已删除的行；全局过滤器照常，例如仍限定在当前租户。 */
  withDeleted(): From<T> {
    return this.#with({ filtering: { ...this.#spec.filtering, logicDelete: false } });
  }

  /** 本查询读分表的全部物理表，不按条件选表（见数据库选项 `shards`）；子查询没有自己的选择时沿用。 */
  allShards(): From<T> {
    return this.#with({ filtering: shardsBy(this.#spec.filtering, "all") });
  }

  /** 本查询只读分表的这些物理表，例如 `onShards("EVENT_202601")`；子查询没有自己的选择时沿用。 */
  onShards(...tables: string[]): From<T> {
    return this.#with({ filtering: shardsBy(this.#spec.filtering, tables) });
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
    const c = typeof clause === "function" ? outside(() => clause(...this.#refs)) : clause;
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
    const selected = outside(() => projection(...this.#refs));
    if (selected instanceof ExprNode) return this.#single(selected as Expr<unknown>, selected.label ?? "value");
    const items: Expr<unknown>[] = [];
    const paths: (string[] | null)[] = [];
    const labels: string[] = [];
    const slots: Slot[] = [];
    flatten(selected, [], items, paths, labels, slots);
    if (items.length === 0) throw new Error("The projection selects no column");
    return makeQuery({
      arms: [{ spec: this.#spec, items, slots }],
      operators: [],
      groups: [],
      labels,
      keyed: true,
      flat: slots.length === 0 && paths.every((p) => p !== null && p.length === 1),
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

  /** 运行条件块，按本作用域的 skipEmpty 开关。 */
  #condition(block: () => void): Cond {
    return withSkipEmpty(this.#spec.skipEmpty, () => collect(block));
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
    qualify: [],
    orderBy: [],
    distinct: false,
    limit: undefined,
    offset: undefined,
    filtering: FILTERING_ON,
    skipEmpty: false,
    clauses: [],
    groupJoins: [],
    resultFilters: [],
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
export type ConstantValue = string | number | bigint | boolean | Decimal | CalendarDate | CalendarDateTime;

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

import { COLUMNS, NAMED } from "../internal.js";
import { entityMeta, type Table } from "../schema/metadata.js";
import { integer, type SqlType } from "../schema/types.js";
import { all, any, Column, Comparison, compare, conjuncts, exists, expression, notExists, type Cond, type Expr, type OrderItem } from "../sql/expr.js";
import { avg, count, lit, max, min, rowNumber, scalar, sum } from "../sql/functions.js";
import type { Renderable, SqlWriter } from "../sql/writer.js";
import { from, type Projection, type Query, type Shape } from "./query.js";
import { createRef, ownerOf, type Ref, type RefOwner, type Related, type Source } from "./source.js";

/** 排名子查询里行号列的名字。 */
const ROW_NUMBER = "rn__";

/** 连接条件拆成的三部分：键（目标表的列）、所属行里与键相等的表达式、其余只筛选目标行的条件。 */
interface Split {
  readonly keys: readonly Column<unknown>[];
  readonly outer: readonly Expr<unknown>[];
  readonly filters: readonly Cond[];
}

/** 排序回调：一个或多个排序项。 */
type Ordering<T> = (target: Ref<T>) => OrderItem | readonly OrderItem[];

/**
 * 一对多关系：目标表里满足连接条件的那些行，来自 `@many` 字段。它不连接进主查询（那会让主查询的行重复），
 * 只能通过集合运算使用：`any`、`none` 渲染为 EXISTS；`count`、`sum`、`max`、`min`、`avg` 渲染为相关标量子查询；
 * `first(order)` 每组取第一行，渲染为 ROW_NUMBER 派生表再 LEFT JOIN 回来；`list(...)` 给每个父行一个子行数组。
 * `where` 先筛选目标行，`orderBy` 决定 `list` 的子行顺序。
 */
export class Many<T> {
  readonly #owner: RefOwner;
  readonly #target: () => Table<T>;
  readonly #condition: (target: Ref<T>) => Cond;
  readonly #order: Ordering<T> | undefined;

  constructor(owner: RefOwner, target: () => Table<T>, condition: (target: Ref<T>) => Cond, order?: Ordering<T>) {
    this.#owner = owner;
    this.#target = target;
    this.#condition = condition;
    this.#order = order;
  }

  /** 只看满足 condition 的目标行，返回新的 Many。 */
  where(condition: (target: Ref<T>) => Cond): Many<T> {
    const before = this.#condition;
    return new Many(this.#owner, this.#target, (t) => all(before(t), condition(t)), this.#order);
  }

  /** 子行按 order 排序，用于 {@link list}：`h.items.orderBy((i) => i.position).list(...)`。对 any、count 等没有影响。 */
  orderBy(order: Ordering<T>): Many<T> {
    return new Many(this.#owner, this.#target, this.#condition, order);
  }

  /** 至少有一行（满足 condition）：`EXISTS (...)`。 */
  any(condition?: (target: Ref<T>) => Cond): Cond {
    return exists((condition === undefined ? this : this.where(condition)).#scope());
  }

  /** 一行也没有（满足 condition 的）：`NOT EXISTS (...)`。 */
  none(condition?: (target: Ref<T>) => Cond): Cond {
    return notExists((condition === undefined ? this : this.where(condition)).#scope());
  }

  /** 行数，相关子查询 `(SELECT COUNT(*) ...)`。 */
  count(): Expr<number> {
    return scalar(this.#scope().select(() => count())) as Expr<number>;
  }

  /** 某个值的合计；没有行时为 null。 */
  sum<N extends number | bigint | string>(value: (target: Ref<T>) => Expr<N | null>): Expr<N | null> {
    return scalar(this.#scope().select((t) => sum(value(t) as Expr<N>))) as Expr<N | null>;
  }

  /** 某个值的最大值；没有行时为 null。 */
  max<V>(value: (target: Ref<T>) => Expr<V>): Expr<V | null> {
    return scalar(this.#scope().select((t) => max(value(t)))) as Expr<V | null>;
  }

  /** 某个值的最小值；没有行时为 null。 */
  min<V>(value: (target: Ref<T>) => Expr<V>): Expr<V | null> {
    return scalar(this.#scope().select((t) => min(value(t)))) as Expr<V | null>;
  }

  /** 某个值的平均值（精确小数的字符串）；没有行时为 null。 */
  avg(value: (target: Ref<T>) => Expr<number | bigint | string | null>): Expr<string | null> {
    return scalar(this.#scope().select((t) => avg(value(t) as Expr<number>)));
  }

  /**
   * 每组按 order 排序后的第一行，例如最新的一条记录：`m.deliveries.first((l) => l.createdOn.desc()).delivery`。
   * 连接条件里"目标列 = 所属表的列"的部分作为分组键，其余条件只筛选目标行。渲染为
   * `LEFT JOIN (SELECT 目标表各列, ROW_NUMBER() OVER (PARTITION BY 键 ORDER BY ...) ...) ON 键相等 AND 行号 = 1`，
   * 所以没有匹配行时各列为 null。HANA 没有 LATERAL，三个库都用这一种写法。
   */
  first(order: Ordering<T>): Related<T> {
    const target = this.#target();
    let split: Split | undefined;
    const scope = from(target).where((t) => {
      split = this.#split(t, "first()");
      return all(...split.filters);
    });
    const { keys, outer } = split!;
    const names = entityMeta(target).properties.map((p) => p.name).sort();
    const ranked = scope.select((t) => {
      const projection: Record<string, Expr<unknown>> = {};
      for (const name of names) projection[name] = (t as Record<string, Expr<unknown>>)[name]!;
      projection[ROW_NUMBER] = rowNumber().over((w) => w.partitionBy(...keys).orderBy(...orderItems(order(t))));
      return projection;
    });
    const first = createRef(new RankedSource(ranked, `${target.name.toLowerCase()}_first`), target, true);
    const firstOwner = ownerOf(first);
    const rowNo = expression<number>(integer, (w) => w.alias(firstOwner.alias(w)).sql(".").identifier(ROW_NUMBER));
    const join = keys.map((k, i) => compare((first as Record<string, Expr<unknown>>)[k.column.name]!, "=", outer[i]!));
    this.#owner.relations.push({ owner: firstOwner, ref: first, on: all(...join, compare(rowNo, "=", lit(1))) });
    return first as unknown as Related<T>;
  }

  /**
   * 每个父行的子行数组，放进对象投影：`{ material: m.material, lines: m.deliveries.list((l) => ({ delivery: l.delivery })) }`。
   * 父查询只多取连接键；执行时用一条按键批量的 IN 查询取出全部子行，所以 N 个父行只多一条 SQL。
   * 连接条件里"目标列 = 所属表的列"的部分作为键，其余条件只筛选子行。子投影里还可以再有 `list(...)`。
   */
  list<V>(projection: (target: Ref<T>) => Expr<V>): Children<V>;
  list<P extends Projection>(projection: (target: Ref<T>) => P): Children<Shape<P>>;
  list(projection: (target: Ref<T>) => Expr<unknown> | Projection): Children<unknown> {
    let outer: readonly Expr<unknown>[] = [];
    from(this.#target()).where((t) => {
      outer = this.#split(t, "list()").outer;
      return all();
    });
    const order = this.#order;
    return new Children(outer, (batch) => {
      let scope = from(this.#target()).where((t) => {
        const { keys, filters } = this.#split(t, "list()");
        return all(...filters, match(keys, batch));
      });
      if (order !== undefined) scope = scope.orderBy((t) => orderItems(order(t)));
      return scope.select((t) => {
        const row: Record<string, Expr<unknown> | Projection> = { value: projection(t) };
        this.#split(t, "list()").keys.forEach((k, i) => (row[`key${i}`] = k));
        return row as Projection;
      }) as unknown as Query<ChildRow>;
    });
  }

  /** 在目标表的一次出现 t 上展开连接条件并拆分；没有"目标列 = 所属表的列"时报错，use 是方法名。 */
  #split(t: Ref<T>, use: string): Split {
    const inner = ownerOf(t);
    const keys: Column<unknown>[] = [];
    const outer: Expr<unknown>[] = [];
    const filters: Cond[] = [];
    const link = (a: Renderable, b: Renderable): boolean => {
      if (!(a instanceof Column) || a.owner !== inner || !(b instanceof Column) || b.owner === inner) return false;
      keys.push(a);
      outer.push(b);
      return true;
    };
    for (const c of conjuncts(this.#condition(t))) {
      if (c instanceof Comparison && c.operator === "=" && (link(c.left, c.right) || link(c.right, c.left))) continue;
      filters.push(c);
    }
    if (keys.length === 0) {
      throw new Error(`${use} needs an equality between a column of ${this.#target().name} and a column of the table the relation belongs to`);
    }
    return { keys, outer, filters };
  }

  /** 目标表的一个查询作用域，条件是连接条件加筛选条件。 */
  #scope() {
    return from(this.#target()).where((t) => this.#condition(t));
  }
}

/** 一个或多个排序项统一成数组。 */
function orderItems(items: OrderItem | readonly OrderItem[]): OrderItem[] {
  return Array.isArray(items) ? [...(items as readonly OrderItem[])] : [items as OrderItem];
}

/** 子查询按父键筛选的条件：单列键用 IN，多列键用 (a = ? AND b = ?) OR ...。 */
function match(keys: readonly Column<unknown>[], batch: readonly (readonly unknown[])[]): Cond {
  if (keys.length === 1) return keys[0]!.in(batch.map((t) => t[0]) as never[]);
  return any(...batch.map((t) => all(...keys.map((k, i) => k.eq(t[i] as never)))));
}

/** 子查询的一行：子行的值和它的各个键（key0、key1…）。 */
type ChildRow = { readonly value: unknown } & Readonly<Record<string, unknown>>;

/** 只用于类型：Children 的元素类型。 */
declare const CHILD: unique symbol;

/**
 * 一对多的子行数组，来自 {@link Many.list}。父查询只多取 {@link outer} 这些键；执行时 {@link attach} 用按键批量的
 * IN 查询（每 {@link Children.BATCH} 个父键一批）取出子行，按键分给各父行。
 */
export class Children<C> {
  declare readonly [CHILD]: C;
  /** 每条子查询最多带多少个父键，避免超出各库的参数个数上限。 */
  static readonly BATCH = 500;
  /** 父行里与子表的键对应的表达式，父查询多取的就是它们。 */
  readonly outer: readonly Expr<unknown>[];
  readonly #query: (batch: readonly (readonly unknown[])[]) => Query<ChildRow>;

  constructor(outer: readonly Expr<unknown>[], query: (batch: readonly (readonly unknown[])[]) => Query<ChildRow>) {
    this.outer = outer;
    this.#query = query;
  }

  /**
   * 为 rows 里的父行加载子行，并把每个父行的子数组写到它的第 start 列（原来是第一个键）。
   * run 执行一条子查询并返回映射好的行。
   */
  async attach(rows: unknown[][], start: number, run: (q: Query<ChildRow>) => Promise<readonly ChildRow[]>): Promise<void> {
    const types = this.outer.map((e) => e.type as SqlType<unknown>);
    const wanted = new Map<string, unknown[]>();
    const rowKeys = rows.map((row) => {
      const values = row.slice(start, start + this.outer.length);
      const key = keyOf(values, types);
      if (key !== undefined && !wanted.has(key)) wanted.set(key, values);
      return key;
    });
    const found = new Map<string, C[]>();
    const pending = [...wanted.values()];
    for (let i = 0; i < pending.length; i += Children.BATCH) {
      for (const child of await run(this.#query(pending.slice(i, i + Children.BATCH)))) {
        const key = keyOf(this.outer.map((_, k) => child[`key${k}`]), types)!;
        let group = found.get(key);
        if (group === undefined) found.set(key, (group = []));
        group.push(child.value as C);
      }
    }
    rows.forEach((row, i) => {
      const key = rowKeys[i];
      row[start] = key === undefined ? [] : (found.get(key) ?? []);
    });
  }
}

/** 比较父键与子键用的字符串；有 null 时为 undefined。精确小数去掉尾零，1 与 1.00 相等。 */
function keyOf(values: readonly unknown[], types: readonly SqlType<unknown>[]): string | undefined {
  const parts: string[] = [];
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (v === null || v === undefined) return undefined;
    parts.push(types[i]?.kind === "decimal" && typeof v === "string" && v.includes(".") ? v.replace(/0+$/, "").replace(/\.$/, "") : String(v));
  }
  return JSON.stringify(parts);
}

/** 一对多 `first(...)` 的排名子查询：按表接口的属性名取列，不含行号列。 */
class RankedSource<T> implements Source<T> {
  readonly entity = undefined;
  readonly filtered = false;
  readonly columns: readonly { readonly name: string; readonly type: SqlType<unknown> }[];

  constructor(
    readonly query: Query<unknown>,
    readonly preferredAlias: string,
  ) {
    this.columns = query[COLUMNS]("first()").filter((c) => c.name !== ROW_NUMBER);
  }

  /** 写出带括号的排名子查询。 */
  renderSource(w: SqlWriter): void {
    w.sql("(");
    this.query[NAMED](w);
    w.sql(")");
  }

  /** 列名就是属性名。 */
  columnName(column: { readonly name: string }): string {
    return column.name;
  }
}

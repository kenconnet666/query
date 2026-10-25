import { COLUMNS, NAMED } from "../internal.js";
import { entityMeta, type Table } from "../schema/metadata.js";
import { integer, type SqlType } from "../schema/types.js";
import type { Decimal } from "decimal.js";
import { addCondition, all, and, any, collect, Comparison, compare, Cond, condition, conjuncts, existence } from "../sql/cond.js";
import { Aggregate, Column, expression, ExprNode, type Expr, type OrderItem } from "../sql/expr.js";
import { count, lit, rowNumber, scalar } from "../sql/functions.js";
import type { ExprDecimal, ExprNumber, ExprOf, ExprText, NumericValue } from "../sql/typed.js";
import type { Renderable, SqlWriter } from "../sql/writer.js";
import { filterCondition, type FilterObject } from "./filter.js";
import { from, type Projection, type Query, type Shape } from "./query.js";
import { createRef, ownerOf, type Ref, type RefOwner, type Related, type Source } from "./source.js";

/** 排名子查询里行号列的名字。 */
const ROW_NUMBER = "rn__";

/** 只用于类型：Many 的标记，见 {@link AnyMany}。 */
declare const MANY: unique symbol;

/**
 * 任意一个一对多关系，用于投影和筛选列。不写成 `Many<any>`：私有的连接条件让 T 处在逆变位置，而 `Ref<any>`
 * 是带索引签名的映射类型、不是 any，于是 `Many<Lips>` 能否赋给 `Many<any>` 取决于编译器是否已经算出 T 的变型——
 * 整个项目一起编译时通过，编辑器只检查一个文件时却报 TS2769（TS 7.1 实测）。
 */
export type AnyMany = { readonly [MANY]: true };

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
 * 只能通过集合运算使用：`any`、`none` 渲染为 EXISTS，写进当前的条件块；`count`、`sum`、`max`、`min`、`avg` 渲染为相关标量子查询；
 * `first(order)` 每组取第一行，渲染为 ROW_NUMBER 派生表再 LEFT JOIN 回来；`list(...)` 给每个父行一个子行数组。
 * `where` 先筛选目标行，`orderBy` 决定 `list` 与 `stringAgg` 的顺序，`limit` 限定 `list` 每个父行的子行数。
 * 查询上 `groupJoin((m) => m.deliveries)` 时，同一关系上的 count、sum、any 等合成一个按连接键分组的派生表 LEFT JOIN 一次。
 *
 * 多对多经过中间表：中间表的一对多关系 {@link through} 中间表的多对一关系，得到目标表的 Many（`@manyThrough` 声明）。
 * 这时查询的是中间表，交给回调的是经过导航的目标行，导航在子查询里写成 LEFT JOIN。
 */
export class Many<T> {
  declare readonly [MANY]: true;
  readonly #owner: RefOwner;
  /** 查询的表：一对多是目标表，多对多是中间表。 */
  readonly #table: () => Table<unknown>;
  /** 关系本身的连接条件块（写在 #table 的行上），也是判断两个 Many 是否同一个关系的依据。 */
  readonly #on: (row: Ref<any>) => void;
  /** #table 的行到交给回调的行：一对多原样，多对多经过中间表的导航。 */
  readonly #view: (row: Ref<any>) => Ref<T>;
  /** where 追加的筛选条件块。 */
  readonly #filter: ((target: Ref<T>) => void) | undefined;
  readonly #order: Ordering<T> | undefined;
  /** {@link list} 给每个父行最多取的子行数。 */
  readonly #limit: number | undefined;

  constructor(
    owner: RefOwner,
    table: () => Table<unknown>,
    on: (row: Ref<any>) => void,
    filter?: (target: Ref<T>) => void,
    order?: Ordering<T>,
    limit?: number,
    view: (row: Ref<any>) => Ref<T> = (row) => row as Ref<T>,
  ) {
    this.#owner = owner;
    this.#table = table;
    this.#on = on;
    this.#filter = filter;
    this.#order = order;
    this.#limit = limit;
    this.#view = view;
  }

  /** 连接条件加 where 的筛选条件，写进当前的条件块；row 是 #table 的行。 */
  #conditions(row: Ref<any>): void {
    this.#on(row);
    this.#filter?.(this.#view(row));
  }

  /** 换掉筛选、排序、条数的副本，表、连接条件和导航不变。 */
  #with(filter: ((target: Ref<T>) => void) | undefined, order: Ordering<T> | undefined, limit: number | undefined): Many<T> {
    return new Many(this.#owner, this.#table, this.#on, filter, order, limit, this.#view);
  }

  /**
   * 只看满足条件的目标行，返回新的 Many：`m.deliveries.where((l) => l.unit.eq("KG")).count()`。
   * 也可以给条件对象（同 `from(...).where(filter)`），前端的筛选条件于是能筛子集合：`m.deliveries.where(lineFilter).list(...)`。
   */
  where(condition: (target: Ref<T>) => void): Many<T>;
  where<F extends FilterObject>(filter: F): Many<T>;
  where(condition: ((target: Ref<T>) => void) | FilterObject): Many<T> {
    const block = typeof condition === "function" ? condition : (t: Ref<T>) => addCondition(filterCondition([t], condition));
    const before = this.#filter;
    const filter =
      before === undefined
        ? block
        : (t: Ref<T>) => {
            before(t);
            block(t);
          };
    return this.#with(filter, this.#order, this.#limit);
  }

  /**
   * 多对多：本关系（到中间表的一对多）再沿中间表的多对一关系 hop 走到目标表，得到目标表的 Many：
   * `u.userRoles.through((ur) => ur.role)`，或在实体上声明 `@manyThrough((u) => u.userRoles, (ur) => ur.role) roles!: Many<Role>`。
   * 行数是中间表的行数；where、orderBy、limit 写在 through 之后，回调拿到的是目标行。
   */
  through<U>(hop: (row: Ref<T>) => Related<U>): Many<U> {
    if (this.#filter !== undefined || this.#order !== undefined || this.#limit !== undefined) {
      throw new Error("through(...) goes right after the relation to the junction table; call where, orderBy and limit after it");
    }
    const view = this.#view;
    return new Many<U>(this.#owner, this.#table, this.#on, undefined, undefined, undefined, (row) => hop(view(row)) as unknown as Ref<U>);
  }

  /** 子行按 order 排序，用于 {@link list}：`h.items.orderBy((i) => i.position).list(...)`。对 any、count 等没有影响。 */
  orderBy(order: Ordering<T>): Many<T> {
    return this.#with(this.#filter, order, this.#limit);
  }

  /**
   * {@link list} 给每个父行最多取 rows 个子行，按 {@link orderBy} 的顺序取前面的，例如每个物料最近的 3 张交货单：
   * `m.deliveries.orderBy((l) => l.createdOn.desc()).limit(3).list(...)`。批量查询用 ROW_NUMBER 按父键分区过滤。
   */
  limit(rows: number): Many<T> {
    if (!Number.isInteger(rows) || rows < 1) throw new RangeError("limit needs a whole number of rows, at least 1");
    if (this.#order === undefined) throw new Error("limit(...) keeps the first rows by orderBy(...); call orderBy first");
    return this.#with(this.#filter, this.#order, rows);
  }

  /** 至少有一行（满足块里的条件）：`EXISTS (...)`，写进当前的条件块。 */
  any(condition?: (target: Ref<T>) => void): void {
    const many = condition === undefined ? this : this.where(condition);
    addCondition(new GroupedTest(many as unknown as Many<any>, existence(many.#scope(), false), many.#grouped(() => count() as never), false));
  }

  /** 一行也没有（满足块里的条件的）：`NOT EXISTS (...)`，写进当前的条件块。 */
  none(condition?: (target: Ref<T>) => void): void {
    const many = condition === undefined ? this : this.where(condition);
    addCondition(new GroupedTest(many as unknown as Many<any>, existence(many.#scope(), true), many.#grouped(() => count() as never), true));
  }

  /** 行数，相关子查询 `(SELECT COUNT(*) ...)`。 */
  count(): ExprNumber<number> {
    return this.#value(() => count() as never, true) as never;
  }

  /** 某个值去重后的个数，相关子查询 `(SELECT COUNT(DISTINCT x) ...)`。 */
  countDistinct(value: (target: Ref<T>) => Expr<unknown>): ExprNumber<number> {
    return this.#value((t) => node(value(t)).countDistinct() as never, true) as never;
  }

  /** 某个值的合计；没有行时为 null。 */
  sum<N extends NumericValue>(value: (target: Ref<T>) => Expr<N | null>): ExprOf<N | null> {
    return this.#value((t) => node(value(t)).sum() as never, false) as never;
  }

  /** 某个值的最大值；没有行时为 null。 */
  max<V>(value: (target: Ref<T>) => Expr<V>): ExprOf<V | null> {
    return this.#value((t) => node(value(t)).max() as never, false) as never;
  }

  /** 某个值的最小值；没有行时为 null。 */
  min<V>(value: (target: Ref<T>) => Expr<V>): ExprOf<V | null> {
    return this.#value((t) => node(value(t)).min() as never, false) as never;
  }

  /** 某个值的平均值（精确的小数）；没有行时为 null。 */
  avg(value: (target: Ref<T>) => Expr<NumericValue | null>): ExprDecimal<Decimal | null> {
    return this.#value((t) => node(value(t)).avg() as never, false) as never;
  }

  /**
   * 把目标行的文本用 separator 拼成一个值，按 {@link orderBy} 给的顺序：
   * `m.deliveries.orderBy((l) => l.delivery).stringAgg((l) => l.delivery, ",")`；没有行时为 null。
   */
  stringAgg(value: (target: Ref<T>) => Expr<string | null>, separator: string): ExprText<string | null> {
    const order = this.#order;
    return scalar(
      this.#scope().select((row) => {
        const t = this.#view(row);
        return node(value(t)).stringAgg(separator, ...(order === undefined ? [] : [order(t)].flat())) as never;
      }),
    ) as never;
  }

  /** 一个聚合值：默认是相关子查询，groupJoin 时换成分组派生表的一列；行数类（zeroIfNone）没有行时为 0。aggregate 拿到的是目标行。 */
  #value(aggregate: (t: Ref<T>) => Aggregate<unknown>, zeroIfNone: boolean): Expr<unknown> {
    const onRow = (row: Ref<any>) => aggregate(this.#view(row));
    return new GroupedValue(this as unknown as Many<any>, scalar(this.#scope().select(onRow)) as Expr<unknown>, this.#grouped(onRow), zeroIfNone);
  }

  /**
   * 每组按 order 排序后的第一行，例如最新的一条记录：`m.deliveries.first((l) => l.createdOn.desc()).delivery`。
   * 连接条件里"目标列 = 所属表的列"的部分作为分组键，其余条件只筛选目标行；没有匹配行时各列为 null。渲染时按方言选写法（0018）：
   * 支持 LATERAL 的库（PostgreSQL、MySQL）写成 `LEFT JOIN LATERAL (SELECT 目标表各列 … WHERE 键 = 父行的列 ORDER BY … LIMIT 1) ON TRUE`，
   * 每个父行按索引取一行，给 (键, 排序列) 建索引；其余（HANA、SQLite、PolarDB）写成
   * `LEFT JOIN (SELECT 目标表各列, ROW_NUMBER() OVER (PARTITION BY 键 ORDER BY ...) ...) ON 键相等 AND 行号 = 1`。
   */
  first(order: Ordering<T>): Related<T> {
    const table = this.#table();
    let split: Split | undefined;
    const scope = from(table).where((t) => {
      split = this.#split(t as Ref<any>, "first()");
      split.filters.forEach(addCondition);
    });
    const { keys, outer } = split!;
    const names = entityMeta(table).properties.map((p) => p.name).sort();
    const ranked = scope.select((t) => {
      const projection: Record<string, Expr<unknown>> = {};
      for (const name of names) projection[name] = (t as Record<string, Expr<unknown>>)[name]!;
      projection[ROW_NUMBER] = rowNumber().over((w) => w.partitionBy(...keys).orderBy(...orderItems(order(this.#view(t as Ref<any>)))));
      return projection;
    });
    // LATERAL 的写法在查询的表的另一次出现上：键等于父行的列，排好序取一行
    const lateral = from(table)
      .where((t) => {
        const each = this.#split(t as Ref<any>, "first()");
        each.filters.forEach(addCondition);
        each.keys.forEach((k, i) => addCondition(compare(k, "=", each.outer[i]!)));
      })
      .orderBy((t) => orderItems(order(this.#view(t as Ref<any>))))
      .limit(1)
      .select((t) => {
        const projection: Record<string, Expr<unknown>> = {};
        for (const name of names) projection[name] = (t as Record<string, Expr<unknown>>)[name]!;
        return projection;
      });
    const first = createRef(new FirstRowSource(ranked, lateral, `${table.name.toLowerCase()}_first`), table, true);
    const firstOwner = ownerOf(first);
    const rowNo = expression<number>(integer, (w) => w.alias(firstOwner.alias(w)).sql(".").identifier(ROW_NUMBER));
    const join = keys.map((k, i) => compare((first as Record<string, Expr<unknown>>)[k.column.name]!, "=", outer[i]!));
    const ranking = and(...join, compare(rowNo, "=", lit(1)));
    const on = condition((w) => (w.dialect.supportsLateral ? w.sql("TRUE") : w.render(ranking)));
    this.#owner.relations.push({ owner: firstOwner, ref: first, on });
    return this.#view(first as Ref<any>) as unknown as Related<T>;
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
    from(this.#table()).where((t) => {
      outer = this.#split(t as Ref<any>, "list()").outer;
    });
    const order = this.#order;
    const limit = this.#limit;
    const view = (t: unknown) => this.#view(t as Ref<any>);
    return new Children(outer, (batch) => {
      let scope = from(this.#table()).where((t) => {
        const { keys, filters } = this.#split(t as Ref<any>, "list()");
        filters.forEach(addCondition);
        match(keys, batch);
      });
      if (order !== undefined) scope = scope.orderBy((t) => orderItems(order(view(t))));
      if (order !== undefined && limit !== undefined) {
        scope = scope.qualify((t) => rowNumber().over((w) => w.partitionBy(...this.#split(t as Ref<any>, "list()").keys).orderBy(...orderItems(order(view(t))))).le(limit));
      }
      return scope.select((t) => {
        const row: Record<string, Expr<unknown> | Projection> = { value: projection(view(t)) };
        this.#split(t as Ref<any>, "list()").keys.forEach((k, i) => (row[`key${i}`] = k));
        return row as Projection;
      }) as unknown as Query<ChildRow>;
    });
  }

  // ---------- groupJoin ----------

  /** 所属的那次表出现；groupJoin 把派生表连接在它后面。 */
  get owner(): RefOwner {
    return this.#owner;
  }

  /** 两个 Many 是否是同一个关系：同一次表出现上的同一个 `@many`，where 筛选可以不同。 */
  sameRelation(other: Many<any>): boolean {
    return this.#owner === other.#owner && (this.#on as unknown) === other.#on;
  }

  /** 派生表里计算一个运算的聚合：带上 where 的筛选条件，写成 CASE WHEN。aggregate 与返回的函数都拿 #table 的行。 */
  #grouped(aggregate: (row: Ref<any>) => Aggregate<unknown>): (row: object) => Aggregate<unknown> {
    const filter = this.#filter;
    return (row) => {
      const agg = aggregate(row as Ref<any>);
      return filter === undefined ? agg : agg.filtered(collect(() => filter(this.#view(row as Ref<any>))));
    };
  }

  /**
   * groupJoin 的派生表：按连接键分组，键的列名是 k1、k2……，operations 每个一列，列名是 c1、c2……；
   * outer 是所属表里依次与 k1、k2…… 相等的列。
   */
  grouping(operations: readonly Grouped[]): { readonly query: Query<unknown>; readonly outer: readonly Expr<unknown>[] } {
    let split: Split | undefined;
    const scope = from(this.#table()).where((t) => {
      split = this.#split(t as Ref<any>, "groupJoin(...)", collect(() => this.#on(t as Ref<any>)));
      split.filters.forEach(addCondition);
    });
    const { keys, outer } = split!;
    const query = scope
      .groupBy(() => keys)
      .select((t) => {
        const row: Record<string, Expr<unknown>> = {};
        keys.forEach((k, i) => (row[`k${i + 1}`] = k));
        operations.forEach((op, i) => (row[`c${i + 1}`] = op.aggregate(t as object)));
        return row;
      });
    return { query: query as unknown as Query<unknown>, outer };
  }

  /** 在目标表的一次出现 t 上展开连接条件并拆分；没有"目标列 = 所属表的列"时报错，use 是方法名。 */
  #split(t: Ref<any>, use: string, condition: Cond = collect(() => this.#conditions(t))): Split {
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
    for (const c of conjuncts(condition)) {
      if (c instanceof Comparison && c.operator === "=" && (link(c.left, c.right) || link(c.right, c.left))) continue;
      filters.push(c);
    }
    if (keys.length === 0) {
      throw new Error(`${use} needs an equality between a column of ${this.#table().name} and a column of the table the relation belongs to`);
    }
    return { keys, outer, filters };
  }

  /** 目标表的一个查询作用域，条件是连接条件加筛选条件。 */
  #scope() {
    return from(this.#table()).where((t) => this.#conditions(t as Ref<any>));
  }
}

/** 一对多关系上的一个集合运算；查询 groupJoin 这个关系时，渲染器把它换成分组派生表的一列。 */
export interface Grouped extends Renderable {
  /** 运算所在的关系。 */
  readonly relation: Many<any>;
  /** 在派生表里计算它的聚合，row 是派生表那次目标表出现的引用；关系上的 where 写成聚合的过滤条件。 */
  aggregate(row: object): Aggregate<unknown>;
  /** 换成派生表的列 column 之后的写法，例如行数写成 `COALESCE(列, 0)`。 */
  renderGrouped(w: SqlWriter, column: Renderable): void;
}

/** node 是否是一对多关系上的集合运算。 */
export function isGrouped(node: Renderable): node is Grouped {
  return node instanceof GroupedValue || node instanceof GroupedTest;
}

/** 一对多上的聚合值：默认渲染为相关子查询；groupJoin 时换成分组派生表的列，行数类在没有行时为 0。 */
class GroupedValue<T> extends ExprNode<T> implements Grouped {
  constructor(
    readonly relation: Many<any>,
    readonly correlated: Expr<T>,
    readonly aggregate: (row: object) => Aggregate<unknown>,
    readonly zeroIfNone: boolean,
  ) {
    super(correlated.type);
  }

  render(w: SqlWriter): void {
    w.render(this.correlated);
  }

  renderGrouped(w: SqlWriter, column: Renderable): void {
    if (this.zeroIfNone) w.sql("COALESCE(").render(column).sql(", 0)");
    else w.render(column);
  }
}

/** 一对多上的存在判断：默认渲染为 EXISTS / NOT EXISTS；groupJoin 时比较分组派生表里的行数。 */
class GroupedTest extends Cond implements Grouped {
  constructor(
    readonly relation: Many<any>,
    readonly correlated: Cond,
    readonly aggregate: (row: object) => Aggregate<unknown>,
    readonly none: boolean,
  ) {
    super();
  }

  render(w: SqlWriter): void {
    w.render(this.correlated);
  }

  renderGrouped(w: SqlWriter, column: Renderable): void {
    w.sql("COALESCE(").render(column).sql(this.none ? ", 0) = 0" : ", 0) > 0");
  }
}

/** 一个或多个排序项统一成数组。 */
function orderItems(items: OrderItem | readonly OrderItem[]): OrderItem[] {
  return Array.isArray(items) ? [...(items as readonly OrderItem[])] : [items as OrderItem];
}

/** 子查询按父键筛选的条件，写进当前的条件块：单列键用 IN，多列键用 (a = ? AND b = ?) OR ...。 */
function match(keys: readonly Column<unknown>[], batch: readonly (readonly unknown[])[]): void {
  if (keys.length === 1) {
    keys[0]!.in(batch.map((t) => t[0]) as never[]);
    return;
  }
  any(() => batch.forEach((t) => all(() => keys.forEach((k, i) => k.eq(t[i] as never)))));
}

/** 回调给出的表达式：运行时都是 ExprNode，用它的聚合方法。 */
function node(e: Expr<unknown>): ExprNode<unknown> {
  return e as ExprNode<unknown>;
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
    const wanted = new Map<string, unknown[]>();
    const rowKeys = rows.map((row) => {
      const values = row.slice(start, start + this.outer.length);
      const key = keyOf(values);
      if (key !== undefined && !wanted.has(key)) wanted.set(key, values);
      return key;
    });
    const found = new Map<string, C[]>();
    const pending = [...wanted.values()];
    for (let i = 0; i < pending.length; i += Children.BATCH) {
      for (const child of await run(this.#query(pending.slice(i, i + Children.BATCH)))) {
        const key = keyOf(this.outer.map((_, k) => child[`key${k}`]))!;
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

/** 比较父键与子键用的字符串；有 null 时为 undefined。`Decimal`、日期的文本形式是规整的，1 与 1.00 相等。 */
function keyOf(values: readonly unknown[]): string | undefined {
  const parts: string[] = [];
  for (const v of values) {
    if (v === null || v === undefined) return undefined;
    parts.push(String(v));
  }
  return JSON.stringify(parts);
}

/** 一对多 `first(...)` 的排名子查询：按表接口的属性名取列，不含行号列。 */
class FirstRowSource<T> implements Source<T> {
  readonly entity = undefined;
  readonly filtered = false;
  readonly columns: readonly { readonly name: string; readonly type: SqlType<unknown> }[];

  constructor(
    readonly ranked: Query<unknown>,
    readonly lateral: Query<unknown>,
    readonly preferredAlias: string,
  ) {
    this.columns = ranked[COLUMNS]("first()").filter((c) => c.name !== ROW_NUMBER);
  }

  /** 写出子查询：支持 LATERAL 的库写 `LATERAL (…)`，其余写排名的派生表（两种写法的列相同）。 */
  renderSource(w: SqlWriter): void {
    const each = w.dialect.supportsLateral;
    w.sql(each ? "LATERAL (" : "(");
    (each ? this.lateral : this.ranked)[NAMED](w);
    w.sql(")");
  }

  /** 列名就是属性名。 */
  columnName(column: { readonly name: string }): string {
    return column.name;
  }
}

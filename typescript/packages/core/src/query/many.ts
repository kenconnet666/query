import { COLUMNS, NAMED } from "../internal.js";
import { entityMeta, type Table } from "../schema/metadata.js";
import { integer } from "../schema/types.js";
import { all, Column, Comparison, compare, conjuncts, exists, expression, notExists, type Cond, type Expr, type OrderItem } from "../sql/expr.js";
import { avg, count, lit, max, min, rowNumber, scalar, sum } from "../sql/functions.js";
import type { Renderable, SqlWriter } from "../sql/writer.js";
import { from, type Query } from "./query.js";
import { createRef, ownerOf, type Ref, type RefOwner, type Related, type Source } from "./source.js";

/** 排名子查询里行号列的名字。 */
const ROW_NUMBER = "rn__";

/**
 * 一对多关系：目标表里满足连接条件的那些行，来自 `@many` 字段。它不连接进主查询（那会让主查询的行重复），
 * 只能通过集合运算使用：`any`、`none` 渲染为 EXISTS；`count`、`sum`、`max`、`min`、`avg` 渲染为相关标量子查询；
 * `first(order)` 每组取第一行，渲染为 ROW_NUMBER 派生表再 LEFT JOIN 回来。`where` 先筛选目标行。
 */
export class Many<T> {
  readonly #owner: RefOwner;
  readonly #target: () => Table<T>;
  readonly #condition: (target: Ref<T>) => Cond;

  constructor(owner: RefOwner, target: () => Table<T>, condition: (target: Ref<T>) => Cond) {
    this.#owner = owner;
    this.#target = target;
    this.#condition = condition;
  }

  /** 只看满足 condition 的目标行，返回新的 Many。 */
  where(condition: (target: Ref<T>) => Cond): Many<T> {
    const before = this.#condition;
    return new Many(this.#owner, this.#target, (t) => all(before(t), condition(t)));
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
  first(order: (target: Ref<T>) => OrderItem | readonly OrderItem[]): Related<T> {
    const target = this.#target();
    const keys: Column<unknown>[] = [];
    const outer: Renderable[] = [];
    const scope = from(target).where((t) => {
      const inner = ownerOf(t);
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
      return all(...filters);
    });
    if (keys.length === 0) {
      throw new Error(`first() needs an equality between a column of ${target.name} and a column of the table the relation belongs to`);
    }
    const names = entityMeta(target).properties.map((p) => p.name).sort();
    const ranked = scope.select((t) => {
      const projection: Record<string, Expr<unknown>> = {};
      for (const name of names) projection[name] = (t as Record<string, Expr<unknown>>)[name]!;
      const items = order(t);
      projection[ROW_NUMBER] = rowNumber().over((w) => w.partitionBy(...keys).orderBy(...(Array.isArray(items) ? items : [items as OrderItem])));
      return projection;
    });
    const first = createRef(new RankedSource(ranked, `${target.name.toLowerCase()}_first`), target, true);
    const firstOwner = ownerOf(first);
    const rowNo = expression<number>(integer, (w) => w.alias(firstOwner.alias(w)).sql(".").identifier(ROW_NUMBER));
    const join = keys.map((k, i) => compare((first as Record<string, Expr<unknown>>)[k.column.name]!, "=", outer[i]!));
    this.#owner.relations.push({ owner: firstOwner, ref: first, on: all(...join, compare(rowNo, "=", lit(1))) });
    return first as unknown as Related<T>;
  }

  /** 目标表的一个查询作用域，条件是连接条件加筛选条件。 */
  #scope() {
    return from(this.#target()).where((t) => this.#condition(t));
  }
}

/** 一对多 `first(...)` 的排名子查询：按表接口的属性名取列，不含行号列。 */
class RankedSource<T> implements Source<T> {
  readonly entity = undefined;
  readonly filtered = false;
  readonly columns: readonly { readonly name: string; readonly type: import("../schema/types.js").SqlType<unknown> }[];

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

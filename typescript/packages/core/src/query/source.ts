import { collect, type Cond } from "../sql/cond.js";
import { Column, type ColumnOwner, type SourceColumn } from "../sql/expr.js";
import type { ColOf } from "../sql/typed.js";
import { COLUMNS, NAMED } from "../internal.js";
import { columnName, entityMeta, type Ctor, type One, type PropertyMeta, type RelationMeta, type Table } from "../schema/metadata.js";
import { Many } from "./many.js";
import type { Query } from "./query.js";
import type { DialectClass } from "../sql/dialect.js";
import type { CteDefinition, SqlWriter } from "../sql/writer.js";
import { renderShards, type Sharding } from "./sharding.js";

/** 只在类型层面携带行类型的标记，运行时不存在。 */
export declare const rowType: unique symbol;

/**
 * 数据源在查询中的一次出现，传给各个回调：E 的每个字段都是按字段类型选的列接口（`ColText`、`ColDecimal` 等），
 * 所以 `l.material` 读起来就像实体本身，而作用域之外的表的字段无法通过编译。
 * 它对 `keyof E` 是同态映射，因此基于 `E extends Shared` 的泛型代码可以读取共有字段。
 * 展开它（`{ ...l }`）即可选取全部列。
 */
export type Ref<E> = { readonly [K in keyof E]-?: Member<E[K], false> };

/** LEFT JOIN 中可能缺失的一侧：每一列都可能为 null。 */
export type NullableRef<E> = { readonly [K in keyof E]-?: Member<E[K], true> };

/** 关系目标的引用带有的标记：对象投影略过带这个标记的值，所以展开引用（`{ ...l }`）只得到本表的列。 */
export const RELATED: unique symbol = Symbol("query.related");

/** 经由关系得到的目标表引用（多对一、`first(...)`）：列可能为 null。 */
export type Related<E> = NullableRef<E> & { readonly [RELATED]: true };

/**
 * 引用上一个字段的类型：多对一关系是目标表的引用（列可能为 null），一对多是 {@link Many}，其余是列接口。
 * 有意写成对 V 分配的条件类型：泛型代码里的 `E["client"]` 无法求值时，编译器按 V 的约束（如 string）求值，
 * 得到列接口；列接口里用未拆开的 Whole，可空列仍是一个 `ColText<string | null>` 而不是两个列的联合。
 */
type Member<V, Nullable extends boolean, Whole = V> = V extends One<infer T>
  ? Related<T>
  : V extends Many<infer T>
    ? Many<T>
    : ColOf<Nullable extends true ? Whole | null : Whole>;

/** 一个隐式连接：LEFT JOIN 目标的那次出现，连接条件是 on。 */
export interface Relation {
  readonly owner: RefOwner;
  readonly ref: object;
  readonly on: Cond;
}

/**
 * 查询读取行的来源：表、CTE、派生表。实现它即可增加一种来源，例如某个方言的参数化视图。
 */
export interface Source<E> {
  readonly [rowType]?: E;
  /** 表的实体类，供全局过滤器使用；CTE 等来源没有。 */
  readonly entity: Ctor<unknown> | undefined;
  readonly preferredAlias: string;
  /** 是否套用数据库级的全局过滤器；表为 true。 */
  readonly filtered: boolean;
  readonly columns: readonly SourceColumn[];
  /** 写出 FROM 或 JOIN 项，不含别名。 */
  renderSource(w: SqlWriter): void;
  /** 属性在这个来源里的列名。 */
  columnName(column: SourceColumn, w: SqlWriter): string;
}

/** 在引用对象上存放 {@link RefOwner} 的键。 */
const OWNER = Symbol("query.ref");

/** 数据源一次出现的身份；别名在渲染时按语句分配。 */
export class RefOwner implements ColumnOwner {
  /** 从这次出现出发的隐式连接，按登记顺序；渲染时只连接真正被用到的。 */
  readonly relations: Relation[] = [];

  constructor(readonly source: Source<unknown>) {}

  /** 本次出现在当前语句中的别名。 */
  alias(w: SqlWriter): string {
    return w.aliasFor(this, this.source.preferredAlias);
  }

  /** 带别名限定的列；属性有 `readAs` 模板时经由模板读取。 */
  renderColumn(w: SqlWriter, column: SourceColumn): void {
    const qualified = () => w.alias(this.alias(w)).sql(".").identifier(this.source.columnName(column, w));
    const read = (column as Partial<PropertyMeta>).read;
    if (read === undefined) {
      qualified();
      return;
    }
    read.split("{0}").forEach((part, i) => {
      if (i > 0) qualified();
      w.sql(part);
    });
  }

  /** 写出 `来源 别名`；分表时写出渲染器为这次出现选好的物理表（见 {@link Sharding}）。 */
  renderSource(w: SqlWriter): void {
    const shards = w.state(this);
    if (Array.isArray(shards)) renderShards(w, this, shards as string[]);
    else this.source.renderSource(w);
    w.dialect.sourceAlias(w, this.alias(w));
  }
}

/**
 * 为数据源创建一个引用：每个列一个属性，并暗中挂上 {@link RefOwner}。entity 的 `@one`、`@many` 字段成为
 * 惰性取值的属性：第一次读到时才登记连接，之后读到的是同一个结果，所以同一关系只连接一次。
 * 关系属性不可枚举，展开引用（`{ ...l }`）只得到列。related 为 true 时带上 {@link RELATED} 标记。
 */
export function createRef<E>(source: Source<E>, entity: Ctor<unknown> | undefined = source.entity, related = false): Ref<E> {
  const owner = new RefOwner(source as Source<unknown>);
  const ref: Record<string, unknown> = {};
  for (const c of source.columns) ref[c.name] = new Column(owner, c);
  Object.defineProperty(ref, OWNER, { value: owner });
  if (related) ref[RELATED as unknown as string] = true;
  if (entity !== undefined) {
    for (const relation of entityMeta(entity).relations) {
      let value: unknown;
      Object.defineProperty(ref, relation.name, { get: () => (value ??= relationValue(owner, ref, relation)) });
    }
  }
  return Object.freeze(ref) as unknown as Ref<E>;
}

/** 关系属性的值：一对多是 Many，多对多是中间表的 Many 再 through；多对一是目标表的新引用，并登记为本次出现的隐式连接。 */
function relationValue(owner: RefOwner, self: object, relation: RelationMeta): unknown {
  if (relation.through !== undefined) {
    const via = relation.through.via as (self: object) => Many<unknown>;
    return via(self).through(relation.through.to as never);
  }
  const on = relation.on as (self: object, target: object) => void;
  if (relation.many) return new Many(owner, relation.target, (t) => on(self, t));
  const target = createRef(tableSource(relation.target()), undefined, true);
  owner.relations.push({ owner: ownerOf(target), ref: target, on: collect(() => on(self, target)) });
  return target;
}

/** 一个数据源引用（查询回调的参数）在当前语句里的别名，供方言包写子句用，例如 `FOR UPDATE OF 别名`。 */
export function aliasOf(w: SqlWriter, ref: object): string {
  return ownerOf(ref).alias(w);
}

/** 取引用背后的 {@link RefOwner}；不是查询回调给出的引用就报错。 */
export function ownerOf(ref: object): RefOwner {
  const owner = (ref as Record<symbol, RefOwner | undefined>)[OWNER];
  if (owner === undefined) throw new TypeError("Not a source reference; use the parameters the query hands to its callbacks");
  return owner;
}

/** 由 `@table` 描述的表实体。 */
class TableSource<E> implements Source<E> {
  declare readonly [rowType]?: E;
  readonly filtered = true;
  readonly columns: readonly PropertyMeta[];
  readonly preferredAlias: string;
  readonly #table: { readonly name: string; readonly schema: string | undefined };

  constructor(readonly entity: Table<E>) {
    const meta = entityMeta(entity);
    if (meta.table === undefined) throw new Error(`${entity.name} has no @table; read it from a CTE or a derived query instead`);
    this.#table = meta.table;
    this.columns = meta.properties;
    this.preferredAlias = entity.name.toLowerCase();
  }

  /** 写出 `schema.表名`，schema 可省略。 */
  renderSource(w: SqlWriter): void {
    w.table(this.#table.schema, this.#table.name);
  }

  /** 显式列名，否则按数据库的命名策略推导。 */
  columnName(column: SourceColumn, w: SqlWriter): string {
    return columnName(column as PropertyMeta, w.naming);
  }
}

/** 每个实体类只创建一个 TableSource。 */
const tables = new WeakMap<Table<unknown>, TableSource<unknown>>();

/** 表实体对应的数据源，按类缓存。 */
export function tableSource<E>(entity: Table<E>): Source<E> {
  let source = tables.get(entity);
  if (source === undefined) {
    source = new TableSource(entity);
    tables.set(entity, source);
  }
  return source as Source<E>;
}

/**
 * 命名的公用表表达式，主体是扁平对象投影的查询，列就是投影的键。
 * 无论在多深的位置被引用，它都只在语句开头的 WITH 里渲染一次。
 */
export class Cte<E> implements Source<E>, CteDefinition {
  declare readonly [rowType]?: E;
  readonly entity = undefined;
  readonly filtered = false;
  readonly columns: readonly SourceColumn[];

  #body: Query<E>;

  constructor(
    readonly name: string,
    body: Query<E>,
    /** 递归 CTE 所属的方言，见 {@link Cte.recursive}。 */
    readonly recursiveIn?: DialectClass,
  ) {
    if (name.trim() === "") throw new TypeError("A CTE needs a name");
    this.columns = body[COLUMNS]("a CTE");
    this.#body = body;
  }

  /** CTE 的查询体。 */
  get body(): Query<E> {
    return this.#body;
  }

  /**
   * 递归 CTE，供方言包使用（PostgreSQL、MySQL 的 `recursiveCte`）：查询体是 `anchor UNION ALL step(自己)`，
   * step 收到这个 CTE 本身，在里面 `from(self)` 引用上一轮的行。只能在 dialect 上渲染。
   */
  static recursive<E>(name: string, anchor: Query<E>, step: (self: Cte<E>) => Query<E>, dialect: DialectClass): Cte<E> {
    const self = new Cte(name, anchor, dialect);
    self.#body = anchor.unionAll(step(self));
    return self;
  }

  /** CTE 的名字同时作为首选别名。 */
  get preferredAlias(): string {
    return this.name;
  }

  /** 渲染 CTE 主体，列名取自投影的键。 */
  render(w: SqlWriter): void {
    this.body[NAMED](w);
  }

  /** 登记到语句开头的 WITH，并在这里写出 CTE 名。 */
  renderSource(w: SqlWriter): void {
    w.cte(this);
    w.identifier(this.name);
  }

  /** 列名就是投影的键。 */
  columnName(column: SourceColumn): string {
    return column.name;
  }
}

/** FROM 或 JOIN 中的子查询。 */
export class Derived<E> implements Source<E> {
  declare readonly [rowType]?: E;
  readonly entity = undefined;
  readonly filtered = false;
  readonly preferredAlias = "t";
  readonly columns: readonly SourceColumn[];

  constructor(readonly query: Query<E>) {
    this.columns = query[COLUMNS]("a derived table");
  }

  /** 写出带括号的子查询，别名由调用方追加。 */
  renderSource(w: SqlWriter): void {
    w.sql("(");
    this.query[NAMED](w);
    w.sql(")");
  }

  /** 列名就是投影的键。 */
  columnName(column: SourceColumn): string {
    return column.name;
  }
}

/** 方言包自定义数据来源的各部分，见 {@link customSource}。 */
export interface CustomSource {
  readonly columns: readonly SourceColumn[];
  readonly preferredAlias: string;
  /** 是否套用数据库实例的全局过滤器；默认不套用。 */
  readonly filtered?: boolean;
  /** 表的实体类，供全局过滤器按类匹配。 */
  readonly entity?: Ctor<unknown>;
  renderSource(w: SqlWriter): void;
  columnName(column: SourceColumn, w: SqlWriter): string;
}

/** 由各部分组成的数据来源，供方言包实现带参数的视图、表函数、层级等；E 是行的形状。 */
export function customSource<E>(definition: CustomSource): Source<E> {
  return { entity: definition.entity, filtered: definition.filtered ?? false, columns: definition.columns, preferredAlias: definition.preferredAlias, renderSource: (w) => definition.renderSource(w), columnName: (c, w) => definition.columnName(c, w) };
}

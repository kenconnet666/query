import { Column, type ColumnOwner, type Expr, type SourceColumn } from "./expr.js";
import { COLUMNS, NAMED } from "./internal.js";
import { columnName, entityMeta, type Ctor, type PropertyMeta, type Table } from "./metadata.js";
import type { Query } from "./query.js";
import type { CteDefinition, SqlWriter } from "./writer.js";

/** 只在类型层面携带行类型的标记，运行时不存在。 */
declare const rowType: unique symbol;

/**
 * 数据源在查询中的一次出现，传给各个回调：E 的每个字段都是列表达式，
 * 所以 `l.material` 读起来就像实体本身，而作用域之外的表的字段无法通过编译。
 * 它对 `keyof E` 是同态映射，因此基于 `E extends Shared` 的泛型代码可以读取共有字段。
 * 展开它（`{ ...l }`）即可选取全部列。
 */
export type Ref<E> = { readonly [K in keyof E]-?: Expr<E[K]> };

/** LEFT JOIN 中可能缺失的一侧：每一列都可能为 null。 */
export type NullableRef<E> = { readonly [K in keyof E]-?: Expr<E[K] | null> };

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

  /** 写出 `来源 别名`。 */
  renderSource(w: SqlWriter): void {
    this.source.renderSource(w);
    w.sql(" ").alias(this.alias(w));
  }
}

/** 为数据源创建一个引用：每个列一个属性，并暗中挂上 {@link RefOwner}。 */
export function createRef<E>(source: Source<E>): Ref<E> {
  const owner = new RefOwner(source as Source<unknown>);
  const ref: Record<string, Expr<unknown>> = {};
  for (const c of source.columns) ref[c.name] = new Column(owner, c);
  Object.defineProperty(ref, OWNER, { value: owner });
  return Object.freeze(ref) as unknown as Ref<E>;
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
    if (this.#table.schema !== undefined) w.identifier(this.#table.schema).sql(".");
    w.identifier(this.#table.name);
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

  constructor(
    readonly name: string,
    readonly body: Query<E>,
  ) {
    if (name.trim() === "") throw new TypeError("A CTE needs a name");
    this.columns = body[COLUMNS]("a CTE");
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

import { Column, type ColumnOwner, type Expr, type SourceColumn } from "./expr.js";
import { COLUMNS, NAMED } from "./internal.js";
import { columnName, entityMeta, type Ctor, type PropertyMeta, type Table } from "./metadata.js";
import type { Query } from "./query.js";
import type { CteDefinition, SqlWriter } from "./writer.js";

declare const rowType: unique symbol;

/**
 * One occurrence of a source in a query, handed to callbacks: every field of E is a column
 * expression, so `l.material` reads like the entity and a field of a table outside the scope does
 * not compile. Homomorphic over `keyof E`, so generic code over `E extends Shared` can read the
 * shared fields. Spread it (`{ ...l }`) to select every column.
 */
export type Ref<E> = { readonly [K in keyof E]-?: Expr<E[K]> };

/** The side of a LEFT JOIN that may be missing: every column may be null. */
export type NullableRef<E> = { readonly [K in keyof E]-?: Expr<E[K] | null> };

/**
 * Something a query reads rows from: a table, a CTE, a derived table. Implement it to add a source
 * kind, e.g. a parameterized view of one dialect.
 */
export interface Source<E> {
  readonly [rowType]?: E;
  /** 表的实体类，供全局过滤器使用；CTE 等来源没有。 */
  readonly entity: Ctor<unknown> | undefined;
  readonly preferredAlias: string;
  /** Whether database-wide filters apply; true for tables. */
  readonly filtered: boolean;
  readonly columns: readonly SourceColumn[];
  /** Writes the FROM or JOIN item without its alias. */
  renderSource(w: SqlWriter): void;
  /** The column name a property has in this source. */
  columnName(column: SourceColumn, w: SqlWriter): string;
}

const OWNER = Symbol("query.ref");

/** The identity of one source occurrence; aliases are assigned per statement when rendering. */
export class RefOwner implements ColumnOwner {
  constructor(readonly source: Source<unknown>) {}

  alias(w: SqlWriter): string {
    return w.aliasFor(this, this.source.preferredAlias);
  }

  /** The qualified column, through the property's `readAs` template when it has one. */
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

  renderSource(w: SqlWriter): void {
    this.source.renderSource(w);
    w.sql(" ").alias(this.alias(w));
  }
}

export function createRef<E>(source: Source<E>): Ref<E> {
  const owner = new RefOwner(source as Source<unknown>);
  const ref: Record<string, Expr<unknown>> = {};
  for (const c of source.columns) ref[c.name] = new Column(owner, c);
  Object.defineProperty(ref, OWNER, { value: owner });
  return Object.freeze(ref) as unknown as Ref<E>;
}

export function ownerOf(ref: object): RefOwner {
  const owner = (ref as Record<symbol, RefOwner | undefined>)[OWNER];
  if (owner === undefined) throw new TypeError("Not a source reference; use the parameters the query hands to its callbacks");
  return owner;
}

/** A table entity, described by `@table`. */
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

  renderSource(w: SqlWriter): void {
    if (this.#table.schema !== undefined) w.identifier(this.#table.schema).sql(".");
    w.identifier(this.#table.name);
  }

  columnName(column: SourceColumn, w: SqlWriter): string {
    return columnName(column as PropertyMeta, w.naming);
  }
}

const tables = new WeakMap<Table<unknown>, TableSource<unknown>>();

export function tableSource<E>(entity: Table<E>): Source<E> {
  let source = tables.get(entity);
  if (source === undefined) {
    source = new TableSource(entity);
    tables.set(entity, source);
  }
  return source as Source<E>;
}

/**
 * A named common table expression over a query with a flat object projection; its columns are the
 * projection's keys. However deep it is referenced, it renders once in the statement's leading WITH.
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

  get preferredAlias(): string {
    return this.name;
  }

  render(w: SqlWriter): void {
    this.body[NAMED](w);
  }

  renderSource(w: SqlWriter): void {
    w.cte(this);
    w.identifier(this.name);
  }

  columnName(column: SourceColumn): string {
    return column.name;
  }
}

/** A subquery in FROM or JOIN. */
export class Derived<E> implements Source<E> {
  declare readonly [rowType]?: E;
  readonly entity = undefined;
  readonly filtered = false;
  readonly preferredAlias = "t";
  readonly columns: readonly SourceColumn[];

  constructor(readonly query: Query<E>) {
    this.columns = query[COLUMNS]("a derived table");
  }

  renderSource(w: SqlWriter): void {
    w.sql("(");
    this.query[NAMED](w);
    w.sql(")");
  }

  columnName(column: SourceColumn): string {
    return column.name;
  }
}

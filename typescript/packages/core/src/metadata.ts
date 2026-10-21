import { text, type SqlType } from "./types.js";

// Standard decorators record metadata on Symbol.metadata, which Node does not define yet. The
// compiled class reads Symbol.metadata when it is defined, and entity modules import this package
// first, so defining it here in time is enough. Symbol.for lets every copy of the package agree.
const METADATA: symbol = ((Symbol as { metadata?: symbol }).metadata ??= Symbol.for("Symbol.metadata"));

const FIELDS = Symbol("query.fields");
const TABLE = Symbol("query.table");

/** A class whose instances describe rows: a table entity, a DTO or a CTE row. */
export type Ctor<E> = abstract new (...args: never[]) => E;
/** A table entity: a concrete class with `@table`. */
export type Table<E> = new (...args: never[]) => E;

/** Derives a column name for a property without an explicit name. */
export type NamingStrategy = (property: string) => string;

const snake = (property: string): string => property.replace(/([a-z0-9])([A-Z])/g, "$1_$2");

export const NamingStrategy = Object.freeze({
  AS_IS: ((property) => property) as NamingStrategy,
  UPPER_SNAKE: ((property) => snake(property).toUpperCase()) as NamingStrategy,
  LOWER_SNAKE: ((property) => snake(property).toLowerCase()) as NamingStrategy,
});

interface FieldMeta {
  readonly name: string;
  readonly column?: string;
  readonly type?: SqlType<unknown>;
  readonly mapFrom?: { readonly entity: Ctor<unknown>; readonly property: string };
  readonly ignore?: true;
  readonly read?: string;
}

/** One column property of an entity. */
export interface PropertyMeta {
  readonly name: string;
  readonly type: SqlType<unknown>;
  /** The explicit column name; otherwise the database's naming strategy derives it. */
  readonly column: string | undefined;
  /** The `readAs` template the column is read through, `{0}` standing for the qualified column. */
  readonly read: string | undefined;
}

/** The fields of a class, as its decorators and its instances describe them. */
export interface EntityMeta {
  readonly type: Ctor<unknown>;
  readonly table: { readonly name: string; readonly schema: string | undefined } | undefined;
  /** Decorated columns, base class first. */
  readonly properties: readonly PropertyMeta[];
  /** Every decorated field that is not ignored, columns or `@mapFrom` alike. */
  readonly fields: readonly string[];
  property(name: string): PropertyMeta | undefined;
  /** Where a DTO field takes its value from, if it says so. */
  mapFrom(name: string): { readonly entity: Ctor<unknown>; readonly property: string } | undefined;
  ignored(name: string): boolean;
}

type Bag = Record<symbol, unknown>;

function bag(context: { metadata: DecoratorMetadataObject | undefined; name: string | symbol | undefined }): Bag {
  if (!context.metadata) {
    throw new Error(`No decorator metadata for ${String(context.name)}; import @query/core (or a dialect package) before declaring entities`);
  }
  return context.metadata as Bag;
}

/** Each class gets its own copy of the field list, so a subclass extends but never changes its base. */
function record(context: ClassFieldDecoratorContext, field: Omit<FieldMeta, "name">): void {
  if (context.static || context.private) throw new Error(`@column and friends apply to public instance fields, not ${String(context.name)}`);
  const meta = bag(context);
  const own = Object.hasOwn(meta, FIELDS) ? (meta[FIELDS] as FieldMeta[]) : (meta[FIELDS] = [...((meta[FIELDS] as FieldMeta[] | undefined) ?? [])]) as FieldMeta[];
  const name = String(context.name);
  const index = own.findIndex((f) => f.name === name);
  if (field.read !== undefined && index >= 0 && own[index]!.read !== undefined) throw new Error(`${name} declares two read templates`);
  const merged: FieldMeta = { ...(index >= 0 ? own[index] : undefined), ...field, name };
  if (index >= 0) own[index] = merged;
  else own.push(merged);
}

/** Marks a table entity. Fields of base classes count as its columns too. */
export function table(name: string, options: { schema?: string } = {}) {
  return (_target: Ctor<unknown>, context: ClassDecoratorContext): void => {
    bag(context)[TABLE] = { name, schema: options.schema };
  };
}

type FieldDecorator<V> = <F extends V>(value: undefined, context: ClassFieldDecoratorContext<unknown, F>) => void;

/**
 * A column. The type defaults to `text`; the field's declared type must match the column type,
 * optionally with `| null`.
 */
export function column(name?: string): FieldDecorator<string | null>;
export function column<T>(type: SqlType<T>): FieldDecorator<T | null>;
export function column<T>(name: string, type: SqlType<T>): FieldDecorator<T | null>;
export function column(nameOrType?: string | SqlType<unknown>, type?: SqlType<unknown>): FieldDecorator<unknown> {
  const name = typeof nameOrType === "string" ? nameOrType : undefined;
  const sqlType = typeof nameOrType === "object" ? nameOrType : (type ?? (text as SqlType<unknown>));
  return (_value, context) => record(context, name === undefined ? { type: sqlType } : { column: name, type: sqlType });
}

/** On a DTO field: the value comes from `entity[property]`. The key and the field type are checked. */
export function mapFrom<E, K extends keyof E & string>(entity: Ctor<E>, property: K) {
  return <F>(_value: undefined, context: ClassFieldDecoratorContext<unknown, F> & ([E[K]] extends [F] ? unknown : { "field type must accept": E[K] })): void =>
    record(context, { mapFrom: { entity: entity as Ctor<unknown>, property } });
}

/**
 * How a column is read in every statement: an SQL template where `{0}` stands for the qualified
 * column, like Hibernate's `@ColumnTransformer(read = ...)`. The column then reads transformed in
 * SELECT, WHERE, JOIN, GROUP BY and ORDER BY alike; declare a second field on the same column to work
 * with the stored value. Combine it with `@column`.
 */
export function readAs(template: string) {
  if (!template.includes("{0}")) throw new TypeError("A read template needs {0} for the column");
  return (_value: undefined, context: ClassFieldDecoratorContext): void => record(context, { read: template });
}

/** The column reads an empty string as NULL, as SAP tables store "no value": `NULLIF(column, '')`. */
export const blankAsNull = readAs("NULLIF({0}, '')");

/** The column reads without surrounding spaces: `TRIM(column)`. */
export const trimmed = readAs("TRIM({0})");

/** A DTO field that no query fills. */
export function ignore(_value: undefined, context: ClassFieldDecoratorContext): void {
  record(context, { ignore: true });
}

const cache = new WeakMap<Ctor<unknown>, EntityMeta>();

export function entityMeta(type: Ctor<unknown>): EntityMeta {
  let meta = cache.get(type);
  if (!meta) {
    meta = describe(type);
    cache.set(type, meta);
  }
  return meta;
}

function describe(type: Ctor<unknown>): EntityMeta {
  const meta = (type as unknown as Record<symbol, Bag | undefined>)[METADATA];
  const fields = (meta?.[FIELDS] as FieldMeta[] | undefined) ?? [];
  // A class without decorators of its own inherits its base's metadata object, but not its table.
  const own = Object.hasOwn(type, METADATA) && meta !== undefined && Object.hasOwn(meta, TABLE);
  const table = own ? (meta[TABLE] as { name: string; schema: string | undefined }) : undefined;
  const properties: PropertyMeta[] = fields
    .filter((f) => f.type !== undefined && !f.ignore)
    .map((f) => Object.freeze({ name: f.name, type: f.type!, column: f.column, read: f.read }));
  const byName = new Map(properties.map((p) => [p.name, p]));
  const fieldsByName = new Map(fields.map((f) => [f.name, f]));
  return Object.freeze({
    type,
    table,
    properties,
    fields: fields.filter((f) => !f.ignore).map((f) => f.name),
    property: (name: string) => byName.get(name),
    mapFrom: (name: string) => fieldsByName.get(name)?.mapFrom,
    ignored: (name: string) => fieldsByName.get(name)?.ignore === true,
  });
}

/**
 * The fields a DTO class receives: its own instance fields (class fields are defined by the
 * constructor) plus decorated ones, without ignored fields. The class needs a no-argument constructor.
 */
export function shapeFields(type: Ctor<unknown>): readonly string[] {
  const meta = entityMeta(type);
  let instance: object;
  try {
    instance = new (type as unknown as new () => object)();
  } catch (e) {
    throw new Error(`${type.name} needs a constructor without arguments to receive rows`, { cause: e });
  }
  const names = new Set([...Object.keys(instance), ...meta.fields]);
  return [...names].filter((n) => !meta.ignored(n));
}

/** The column name of a property: its explicit name, or the naming strategy's. */
export function columnName(property: PropertyMeta, naming: NamingStrategy): string {
  return property.column ?? naming(property.name);
}

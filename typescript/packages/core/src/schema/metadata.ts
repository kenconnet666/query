import type { Many } from "../query/many.js";
import type { Ref } from "../query/source.js";
import type { Cond } from "../sql/expr.js";
import { text, type SqlType } from "./types.js";

// 标准装饰器把元数据记录在 Symbol.metadata 上，而 Node 还没有定义它。编译后的类在
// Symbol.metadata 已定义时才会读取它；实体模块总是先导入本包，所以在这里及时定义就够了。
// 用 Symbol.for 让本包的每个副本使用同一个符号。
const METADATA: symbol = ((Symbol as { metadata?: symbol }).metadata ??= Symbol.for("Symbol.metadata"));

/** 类的元数据对象上，存放字段列表和表信息的键。 */
const FIELDS = Symbol("query.fields");
const TABLE = Symbol("query.table");

/** 实例描述行的类：表实体、DTO 或 CTE 行。 */
export type Ctor<E> = abstract new (...args: never[]) => E;
/** 表实体：带 `@table` 的具体类。 */
export type Table<E> = new (...args: never[]) => E;

/** 为没有显式列名的属性推导列名。 */
export type NamingStrategy = (property: string) => string;

/** 驼峰转下划线，大小写另行处理。 */
const snake = (property: string): string => property.replace(/([a-z0-9])([A-Z])/g, "$1_$2");

/** 内置命名策略：原样、大写下划线、小写下划线。 */
export const NamingStrategy = Object.freeze({
  AS_IS: ((property) => property) as NamingStrategy,
  UPPER_SNAKE: ((property) => snake(property).toUpperCase()) as NamingStrategy,
  LOWER_SNAKE: ((property) => snake(property).toLowerCase()) as NamingStrategy,
});

/** 装饰器记录的字段信息；只有 `@column` 声明过类型的字段才是列。 */
interface FieldMeta {
  readonly name: string;
  readonly column?: string;
  readonly type?: SqlType<unknown>;
  readonly ignore?: true;
  readonly read?: string;
  readonly relation?: Omit<RelationMeta, "name">;
}

/** 关系字段的声明：目标表（延迟取得，表之间可以互相引用）和连接条件。 */
export interface RelationMeta {
  readonly name: string;
  /** 是否一对多。 */
  readonly many: boolean;
  /** 目标表实体。 */
  readonly target: () => Table<unknown>;
  /** 连接条件：本表的引用和目标表的引用。 */
  readonly on: (self: never, target: never) => Cond;
}

declare const ONE: unique symbol;

/**
 * 多对一关系字段的类型：`@one(() => Mara, (l, m) => m.material.eq(l.material)) mara!: One<Mara>;`。
 * 查询回调里 `l.mara` 是目标表的引用（列可能为 null），可以继续取列或继续导航。
 */
export interface One<T> {
  readonly [ONE]: T;
}

/** 实体的一个列属性。 */
export interface PropertyMeta {
  readonly name: string;
  readonly type: SqlType<unknown>;
  /** 显式指定的列名；缺省时由数据库的命名策略推导。 */
  readonly column: string | undefined;
  /** 读取该列所用的 `readAs` 模板，`{0}` 代表带限定的列。 */
  readonly read: string | undefined;
}

/** 一个类的字段，由它的装饰器和实例共同描述。 */
export interface EntityMeta {
  readonly type: Ctor<unknown>;
  readonly table: { readonly name: string; readonly schema: string | undefined } | undefined;
  /** 装饰过的列，基类的在前。 */
  readonly properties: readonly PropertyMeta[];
  /** 没有被 ignore 的全部字段。 */
  readonly fields: readonly string[];
  /** 按属性名取列属性，不是列时为 undefined。 */
  property(name: string): PropertyMeta | undefined;
  /** 该字段是否被 `ignore` 标记，或是关系字段（两者都不接收列值）。 */
  ignored(name: string): boolean;
  /** 用 `@one`、`@many` 声明的关系。 */
  readonly relations: readonly RelationMeta[];
}

/** 类的元数据对象。 */
type Bag = Record<symbol, unknown>;

/** 取装饰器上下文的元数据对象；运行时没有定义 Symbol.metadata 时给出明确的报错。 */
function bag(context: { metadata: DecoratorMetadataObject | undefined; name: string | symbol | undefined }): Bag {
  if (!context.metadata) {
    throw new Error(`No decorator metadata for ${String(context.name)}; import @zerodep-dev/query-core (or a dialect package) before declaring entities`);
  }
  return context.metadata as Bag;
}

/** 每个类各有一份字段列表的副本，所以子类只会扩展而不会改动基类。 */
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

/** 标记表实体。基类的字段也算它的列。 */
export function table(name: string, options: { schema?: string } = {}) {
  return (_target: Ctor<unknown>, context: ClassDecoratorContext): void => {
    bag(context)[TABLE] = { name, schema: options.schema };
  };
}

/** 字段装饰器；`V` 限定字段声明的类型。 */
type FieldDecorator<V> = <F extends V>(value: undefined, context: ClassFieldDecoratorContext<unknown, F>) => void;

/**
 * 声明一列。类型缺省为 `text`；字段声明的类型必须与列类型一致，可以另加 `| null`。
 */
export function column(name?: string): FieldDecorator<string | null>;
export function column<T>(type: SqlType<T>): FieldDecorator<T | null>;
export function column<T>(name: string, type: SqlType<T>): FieldDecorator<T | null>;
export function column(nameOrType?: string | SqlType<unknown>, type?: SqlType<unknown>): FieldDecorator<unknown> {
  const name = typeof nameOrType === "string" ? nameOrType : undefined;
  const sqlType = typeof nameOrType === "object" ? nameOrType : (type ?? (text as SqlType<unknown>));
  return (_value, context) => record(context, name === undefined ? { type: sqlType } : { column: name, type: sqlType });
}

/**
 * 声明列在每条语句里的读取方式：SQL 模板，`{0}` 代表带限定的列，
 * 类似 Hibernate 的 `@ColumnTransformer(read = ...)`。之后该列在 SELECT、WHERE、JOIN、
 * GROUP BY 和 ORDER BY 里一律以转换后的值参与；要使用存储的原值，就在同一列上另声明一个字段。
 * 与 `@column` 搭配使用。
 */
export function readAs(template: string) {
  if (!template.includes("{0}")) throw new TypeError("A read template needs {0} for the column");
  return (_value: undefined, context: ClassFieldDecoratorContext): void => record(context, { read: template });
}

/** 该列把空字符串读作 NULL（SAP 的表用空串表示“无值”）：`NULLIF(column, '')`。 */
export const blankAsNull = readAs("NULLIF({0}, '')");

/** 该列读取时去掉两端空格：`TRIM(column)`。 */
export const trimmed = readAs("TRIM({0})");

/**
 * 多对一或一对一关系：用 on 把目标表 LEFT JOIN 进来。同一个关系在一条查询里只连接一次，没有被用到时不连接。
 * 字段写成 `@one(() => Mara, (l, m) => m.material.eq(l.material)) mara!: One<Mara>;`，回调的 l 就是本表。
 */
export function one<S, T>(target: () => Table<T>, on: (self: Ref<S>, target: Ref<T>) => Cond) {
  return (_value: undefined, context: ClassFieldDecoratorContext<S, One<T>>): void =>
    record(context, { relation: { many: false, target, on: on as RelationMeta["on"] } });
}

/**
 * 一对多关系：目标表里满足 on 的那些行，通过 `any`、`none`、`count`、`sum`、`first` 等使用。
 * 字段写成 `@many(() => Lips, (m, l) => l.material.eq(m.material)) deliveries!: Many<Lips>;`。
 */
export function many<S, T>(target: () => Table<T>, on: (self: Ref<S>, target: Ref<T>) => Cond) {
  return (_value: undefined, context: ClassFieldDecoratorContext<S, Many<T>>): void =>
    record(context, { relation: { many: true, target, on: on as RelationMeta["on"] } });
}

/** 不由任何查询填充的 DTO 字段。 */
export function ignore(_value: undefined, context: ClassFieldDecoratorContext): void {
  record(context, { ignore: true });
}

/** 每个类只解析一次元数据。 */
const cache = new WeakMap<Ctor<unknown>, EntityMeta>();

/** 类的元数据，首次访问时解析并缓存。 */
export function entityMeta(type: Ctor<unknown>): EntityMeta {
  let meta = cache.get(type);
  if (!meta) {
    meta = describe(type);
    cache.set(type, meta);
  }
  return meta;
}

/** 从装饰器记录的元数据构建不可变的 {@link EntityMeta}。 */
function describe(type: Ctor<unknown>): EntityMeta {
  const meta = (type as unknown as Record<symbol, Bag | undefined>)[METADATA];
  const fields = (meta?.[FIELDS] as FieldMeta[] | undefined) ?? [];
  // 自己没有装饰器的类会继承基类的元数据对象，但不继承基类的表。
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
    fields: fields.filter((f) => !f.ignore && f.relation === undefined).map((f) => f.name),
    property: (name: string) => byName.get(name),
    ignored: (name: string) => fieldsByName.get(name)?.ignore === true || fieldsByName.get(name)?.relation !== undefined,
    relations: fields.filter((f) => f.relation !== undefined).map((f) => Object.freeze({ ...f.relation!, name: f.name })),
  });
}

/**
 * DTO 类接收的字段：它自己的实例字段（类字段由构造函数定义）加上被装饰的字段，不含被忽略的。
 * 该类需要无参构造函数。
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

/** 属性的列名：显式指定的名称，否则取命名策略推导的。 */
export function columnName(property: PropertyMeta, naming: NamingStrategy): string {
  return property.column ?? naming(property.name);
}

import type { Many } from "../query/many.js";
import type { Ref } from "../query/source.js";
import type { Expr } from "../sql/expr.js";
import type { Family, FamilyOf } from "../sql/typed.js";
import { text, type SqlType, type TypeKind } from "./types.js";
import type { DialectClass } from "../sql/dialect.js";

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
  readonly where?: WhereMeta;
  readonly id?: true;
  readonly version?: true;
  readonly generated?: true;
  readonly logicDelete?: { readonly active: unknown; readonly deleted: unknown; readonly epochMillis: boolean };
  readonly readOnly?: true;
  readonly writeOnce?: true;
  readonly ddl?: ColumnOptions;
}

/** 只用于结构变更（`db.schema()`）的列声明，查询和写入不读它们；对应 Java 的 `@Column` 属性。 */
export interface ColumnOptions {
  /** 文本或字节列的长度；缺省时文本 255。 */
  readonly length?: number;
  /** 小数的总位数；缺省时 DECIMAL(18,2)。 */
  readonly precision?: number;
  /** 小数的小数位，与 precision 一起写。 */
  readonly scale?: number;
  /** 是否可以为 NULL，缺省 true；主键列总是 NOT NULL。 */
  readonly nullable?: boolean;
  /** 默认值的 SQL 文本，例如 `"0"`、`"'open'"`、`"CURRENT_TIMESTAMP"`。 */
  readonly default?: string;
  /** 不限长度的文本或字节（NCLOB / TEXT / LONGTEXT 等）。 */
  readonly lob?: boolean;
  readonly comment?: string;
  /** 改名前的列名：库里没有新列名、但有这个旧名时，计划写成 RENAME COLUMN。 */
  readonly previousName?: string;
}

/** 表上的索引，写在 `@table` 的选项里；columns 是字段名。 */
export interface IndexOptions {
  readonly name: string;
  readonly columns: readonly string[];
  readonly unique?: boolean;
}

/** 表上的外键，写在 `@table` 的选项里；columns 是本表的字段名，referencedColumns 缺省为被引用表的 `@id`。 */
export interface ForeignKeyOptions {
  readonly name: string;
  readonly columns: readonly string[];
  readonly references: () => Table<unknown>;
  readonly referencedColumns?: readonly string[];
}

/** `@table` 的选项。 */
export interface TableOptions {
  readonly schema?: string;
  /** 不归本库管的表（例如 SAP 表）：`db.schema().check(...)` 只核对已声明的列，从不生成 DDL。 */
  readonly external?: boolean;
  readonly comment?: string;
  readonly indexes?: readonly IndexOptions[];
  readonly foreignKeys?: readonly ForeignKeyOptions[];
  /**
   * 只在某个方言上加在 CREATE TABLE 末尾的选项，最常见的是原生分区：
   * `options: [{ dialect: PostgresDialect, sql: 'PARTITION BY RANGE ("DAY")' }]`。比较实库时不核对它。
   */
  readonly options?: readonly { readonly dialect: DialectClass; readonly sql: string }[];
}

/**
 * 表上与写入有关的列，取自 `@id`、`@version`、`@generated`、`@logicDelete`、`@readOnly`、`@writeOnce`；没有的为空。
 * ids 按字段名排序，与 Java 的列顺序一致。
 */
export interface WriteMeta {
  readonly ids: readonly string[];
  readonly version: string | undefined;
  readonly generated: string | undefined;
  readonly logicDelete: LogicDeleteMeta | undefined;
  /** 只读列：插入和更新都不写。 */
  readonly readOnly: readonly string[];
  /** 只在插入时写的列。 */
  readonly writeOnce: readonly string[];
}

/** 插入时是否写这个字段：生成列和只读列不写。 */
export function inserts(writes: WriteMeta, field: string): boolean {
  return field !== writes.generated && !writes.readOnly.includes(field);
}

/** `byId` 更新和插入或更新的更新部分是否写这个字段：主键、版本、生成列、只读列和只在插入时写的列都不写。 */
export function updates(writes: WriteMeta, field: string): boolean {
  return !writes.ids.includes(field) && field !== writes.version && inserts(writes, field) && !writes.writeOnce.includes(field);
}

/**
 * 逻辑删除列：timestamp 为 true 时记录删除时间（未删除为 NULL，删除写当前时间）；epochMillis 为 true 时删除写当前的毫秒数；
 * 否则按 active、deleted 两个值。
 */
export interface LogicDeleteMeta {
  readonly property: string;
  readonly active: unknown;
  readonly deleted: unknown;
  readonly timestamp: boolean;
  readonly epochMillis: boolean;
}

/** 删除时写入的值：epochMillis 时是此刻的毫秒数，否则是 deleted；timestamp 时不用。 */
export function deletedValue(logic: LogicDeleteMeta): unknown {
  return logic.epochMillis ? BigInt(Date.now()) : logic.deleted;
}

/** 条件对象字段的比较方式。 */
export type WhereOp = "eq" | "ne" | "gt" | "ge" | "lt" | "le" | "contains" | "startsWith" | "endsWith" | WhereOperator;

/** 自定义的比较：在列上写条件（条件块），不写就是不加条件；字段值为空时不调用，直接省略。 */
export type WhereOperator = (column: Expr<any>, value: any) => void;

/** 条件对象里一个字段的比较方式和要比较的列（关系路径）。 */
export interface WhereMeta {
  readonly op: WhereOp;
  readonly columns: readonly string[];
}

/** 关系字段的声明：目标表（延迟取得，表之间可以互相引用）和连接条件。 */
export interface RelationMeta {
  readonly name: string;
  /** 是否一对多。 */
  readonly many: boolean;
  /** 目标表实体。 */
  readonly target: () => Table<unknown>;
  /** 连接条件块：本表的引用和目标表的引用。 */
  readonly on: (self: never, target: never) => void;
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
  /** 结构变更用的列声明，没有时为 undefined。 */
  readonly ddl?: ColumnOptions | undefined;
}

/** 一个类的字段，由它的装饰器和实例共同描述。 */
export interface EntityMeta {
  readonly type: Ctor<unknown>;
  readonly table: (TableOptions & { readonly name: string; readonly schema: string | undefined }) | undefined;
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
  /** 条件对象字段的 `@where` 声明，没有时为 undefined。 */
  where(name: string): WhereMeta | undefined;
  /** 主键、版本、生成列和逻辑删除列。 */
  readonly writes: WriteMeta;
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
export function table(name: string, options: TableOptions = {}) {
  return (_target: Ctor<unknown>, context: ClassDecoratorContext): void => {
    bag(context)[TABLE] = { ...options, name, schema: options.schema };
  };
}

/** 字段装饰器；`V` 限定字段声明的类型，K 是存储的种类，字段类型与它不是同一类时报错（见 {@link StorageCheck}）。 */
type FieldDecorator<V, K extends TypeKind> = <F extends V>(value: undefined, context: ClassFieldDecoratorContext<unknown, F> & StorageCheck<F, K>) => void;

/** 存储种类所属的逻辑类型；`unknown`（无类型）配任何字段。 */
type KindFamily<K extends TypeKind> = K extends "integer" | "double" ? "number" : K extends "unknown" ? Family : K;

/**
 * 字段类型与存储种类是否相配：同一类，或字段类型不属于任何一类（自定义的值类型，只有通用的比较）。不相配时（以文本存的日期
 * 声明成 `CalendarDate`）列会点出存储类型没有的函数，所以要求写成 `Stored<字段类型, 存储类型>`，报错就在装饰器上。
 */
type StorageCheck<F, K extends TypeKind> = FamilyOf<NonNullable<F>> extends "other" | KindFamily<K>
  ? unknown
  : { readonly "the column is stored as another type; declare the field as Stored<FieldType, StorageType>": K };

/**
 * 声明一列。类型缺省为 `text`；字段声明的类型必须与列类型一致，可以另加 `| null`。转换器列的字段类型与存储类型不是同一类时
 * （SAP 以文本存的日期），字段写成 `Stored<CalendarDate, string>`。
 */
export function column(name?: string): FieldDecorator<string | null, "text">;
export function column<T, K extends TypeKind>(type: SqlType<T, K>): FieldDecorator<T | null, K>;
export function column<T, K extends TypeKind>(name: string, type: SqlType<T, K>): FieldDecorator<T | null, K>;
/** 声明一列，另带结构变更用的选项：`@column("TITLE", text, { length: 40, nullable: false })`。 */
export function column<T, K extends TypeKind>(name: string, type: SqlType<T, K>, options: ColumnOptions): FieldDecorator<T | null, K>;
export function column(nameOrType?: string | SqlType<unknown>, type?: SqlType<unknown>, options?: ColumnOptions): FieldDecorator<unknown, TypeKind> {
  const name = typeof nameOrType === "string" ? nameOrType : undefined;
  const sqlType = typeof nameOrType === "object" ? nameOrType : (type ?? (text as SqlType<unknown>));
  const ddl = options === undefined ? {} : { ddl: Object.freeze({ ...options }) };
  return (_value, context) => record(context, name === undefined ? { type: sqlType, ...ddl } : { column: name, type: sqlType, ...ddl });
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
 * 字段写成 `@one(() => Mara, (l, m) => m.material.eq(l.material)) mara!: One<Mara>;`，回调是条件块，l 就是本表。
 */
export function one<S, T>(target: () => Table<T>, on: (self: Ref<S>, target: Ref<T>) => void) {
  return (_value: undefined, context: ClassFieldDecoratorContext<S, One<T>>): void =>
    record(context, { relation: { many: false, target, on: on as RelationMeta["on"] } });
}

/**
 * 一对多关系：目标表里满足 on 的那些行，通过 `any`、`none`、`count`、`sum`、`first` 等使用。
 * 字段写成 `@many(() => Lips, (m, l) => l.material.eq(m.material)) deliveries!: Many<Lips>;`。
 */
export function many<S, T>(target: () => Table<T>, on: (self: Ref<S>, target: Ref<T>) => void) {
  return (_value: undefined, context: ClassFieldDecoratorContext<S, Many<T>>): void =>
    record(context, { relation: { many: true, target, on: on as RelationMeta["on"] } });
}

/**
 * 条件对象（`from(...).where(filter)`）里一个字段怎样变成条件：op 是比较方式，columns 是比较的列，默认是字段名；
 * 列可以经过多对一关系（`"mara.partNumber"`），写多个时任一满足即可（OR）。没有这个装饰器的字段与同名列比较相等
 * （数组用 IN）。例如 `@where("ge", "createdOn") from?: CalendarDate;`；op 也可以是自定义函数 `(column, value) => { 条件 }`。
 */
export function where(op: WhereOp, ...columns: string[]) {
  return (_value: undefined, context: ClassFieldDecoratorContext): void => record(context, { where: Object.freeze({ op, columns }) });
}

/**
 * 主键列，多列主键在每一列上都标。`update(...).byId(row)`、`delete(...).byId(row)` 按它们定位行，
 * 插入或更新的 `onConflict()` 不写列时也用它们。
 */
export function id(_value: undefined, context: ClassFieldDecoratorContext): void {
  record(context, { id: true });
}

/**
 * 乐观锁的版本列（integer 或 bigint），一张表至多一列。`update(...).byId(row)` 写成 `SET version = version + 1 ... WHERE id = ? AND version = ?`，
 * 没有改到行时抛出 UnexpectedRowCountError；`delete(...).byId(row)` 同样检查版本。
 */
export function version(_value: undefined, context: ClassFieldDecoratorContext): void {
  record(context, { version: true });
}

/** 由数据库生成值的列（IDENTITY、AUTO_INCREMENT），一张表至多一列：插入时不写，`db.insertKeys(insert)` 取回生成的值。 */
export function generated(_value: undefined, context: ClassFieldDecoratorContext): void {
  record(context, { generated: true });
}

/**
 * 逻辑删除列，一张表至多一列：这张表的每次出现都自动加上"未删除"的条件（`unfiltered()`、`withDeleted()` 关闭），
 * `delete(...)` 改写成把这一列设为 deleted 的 UPDATE，`.physically()` 才真正删除。
 * 列是时间戳时记录删除时间：未删除是 NULL，删除写当前时间，两个参数不用写。
 */
export function logicDelete(active?: unknown, deleted?: unknown): (value: undefined, context: ClassFieldDecoratorContext) => void;
/**
 * 删除时写当前的毫秒数（bigint 列，未删除为 active，默认 0）。这样 `UNIQUE (业务键, 删除列)` 能拦住两条未删除的同键行，
 * 又允许同一个键被删除多次（记录删除时间的 NULL 互不相等，做不到这一点）：`@logicDelete({ epochMillis: true })`。
 */
export function logicDelete(options: { readonly epochMillis: true; readonly active?: unknown }): (value: undefined, context: ClassFieldDecoratorContext) => void;
export function logicDelete(first: unknown = 0, deleted: unknown = 1) {
  const options = typeof first === "object" && first !== null && "epochMillis" in first ? (first as { epochMillis: true; active?: unknown }) : undefined;
  const meta =
    options === undefined
      ? { active: first, deleted, epochMillis: false }
      : { active: options.active ?? 0, deleted: undefined, epochMillis: true };
  return (_value: undefined, context: ClassFieldDecoratorContext): void => record(context, { logicDelete: Object.freeze(meta) });
}

/**
 * 只读列，例如由数据库默认值、触发器或计算列维护的列：插入、`byId` 更新和插入或更新都不写它，即使行里有这个字段；
 * 在 `update(...).set(...)` 里显式写它会报错。查询照常读取。
 */
export function readOnly(_value: undefined, context: ClassFieldDecoratorContext): void {
  record(context, { readOnly: true });
}

/**
 * 只在插入时写的列，例如创建时间、创建人：`byId` 更新和插入或更新的更新部分不写它，即使行里有这个字段。
 * 确实要改时用 `update(...).set(...)` 显式写。
 */
export function writeOnce(_value: undefined, context: ClassFieldDecoratorContext): void {
  record(context, { writeOnce: true });
}

/** 不由任何查询填充的 DTO 字段；在条件对象里表示不作为条件（如分页、排序参数）。 */
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
  const table = own ? (meta[TABLE] as TableOptions & { name: string; schema: string | undefined }) : undefined;
  const properties: PropertyMeta[] = fields
    .filter((f) => f.type !== undefined && !f.ignore)
    .map((f) => Object.freeze({ name: f.name, type: f.type!, column: f.column, read: f.read, ddl: f.ddl }));
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
    where: (name: string) => fieldsByName.get(name)?.where,
    writes: writes(type, fields, byName),
  });
}

/** 由字段上的写入装饰器得到 {@link WriteMeta}；版本列、生成列、逻辑删除列各至多一列，版本列必须是整数。 */
function writes(type: Ctor<unknown>, fields: readonly FieldMeta[], columns: ReadonlyMap<string, PropertyMeta>): WriteMeta {
  const single = (flag: "version" | "generated" | "logicDelete"): FieldMeta | undefined => {
    const marked = fields.filter((f) => f[flag] !== undefined);
    if (marked.length > 1) throw new Error(`${type.name} has more than one @${flag} column: ${marked.map((f) => f.name).join(", ")}`);
    return marked[0];
  };
  const version = single("version");
  if (version !== undefined && !["integer", "bigint"].includes(columns.get(version.name)?.type.kind ?? "")) {
    throw new Error(`${type.name}.${version.name}: @version needs an integer or bigint column`);
  }
  const logic = single("logicDelete");
  const timestamp = logic !== undefined && columns.get(logic.name)?.type.kind === "timestamp";
  if (logic?.logicDelete!.epochMillis && columns.get(logic.name)?.type.kind !== "bigint") {
    throw new Error(`${type.name}.${logic.name}: @logicDelete({ epochMillis: true }) needs a bigint column`);
  }
  const readOnly = fields.filter((f) => f.readOnly).map((f) => f.name);
  const writeOnce = fields.filter((f) => f.writeOnce).map((f) => f.name);
  for (const f of fields.filter((x) => x.readOnly)) {
    if (f.writeOnce || f.id || f.version) throw new Error(`${type.name}.${f.name}: @readOnly cannot be combined with @writeOnce, @id or @version`);
  }
  return Object.freeze({
    ids: fields.filter((f) => f.id).map((f) => f.name).sort(),
    version: version?.name,
    generated: single("generated")?.name,
    logicDelete:
      logic === undefined
        ? undefined
        : Object.freeze({
            property: logic.name,
            active: timestamp ? null : logic.logicDelete!.active,
            deleted: timestamp ? null : logic.logicDelete!.deleted,
            timestamp,
            epochMillis: logic.logicDelete!.epochMillis,
          }),
    readOnly: Object.freeze(readOnly),
    writeOnce: Object.freeze(writeOnce),
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

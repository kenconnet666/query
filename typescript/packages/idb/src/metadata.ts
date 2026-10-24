// 标准装饰器把元数据记在 Symbol.metadata 上，而浏览器和 Node 都还没有定义它。编译后的类在
// Symbol.metadata 已定义时才会读取它；声明仓库的模块总是先导入本包，所以在这里及时定义就够了。
const METADATA: symbol = ((Symbol as { metadata?: symbol }).metadata ??= Symbol.for("Symbol.metadata"));

const STORE = Symbol("idb.store");
const FIELDS = Symbol("idb.fields");

/** 记录里存放编码后的主键和索引值的属性；读回时去掉。 */
export const SHADOW = "$idx";

/** 仓库类：读回的行按它的原型还原。 */
export type StoreClass<T = any> = abstract new (...args: any[]) => T;

/**
 * 主键和索引的编码器：把不能当 IndexedDB 键的值（布尔、bigint）换成能当键且保持顺序的值，或者建影子索引（{@link lowercase}）。
 * 只作用于键和索引，字段本身的值照常按结构化克隆保存。没有 decode 的编码器不能用于主键。
 */
export interface Codec<T> {
  readonly name: string;
  encode(value: T): IDBValidKey;
  decode?(key: IDBValidKey): T;
}

/** 布尔写成 1 / 0。 */
export const boolean: Codec<boolean> = Object.freeze({ name: "boolean", encode: (v: boolean) => (v ? 1 : 0), decode: (k: IDBValidKey) => k === 1 });

/** bigint 写成保持大小顺序的文本：符号位、四位的位数（负数取补）、各位数字（负数取补）。 */
export const bigint: Codec<bigint> = Object.freeze({
  name: "bigint",
  encode(v: bigint): string {
    const digits = (v < 0n ? -v : v).toString();
    if (v >= 0n) return `1${String(digits.length).padStart(4, "0")}${digits}`;
    return `0${String(9999 - digits.length).padStart(4, "0")}${complement(digits)}`;
  },
  decode(k: IDBValidKey): bigint {
    const s = String(k);
    return s[0] === "1" ? BigInt(s.slice(5)) : -BigInt(complement(s.slice(5)));
  },
});

/** 大小写不敏感的影子索引：按小写的值进索引，查询的值也转成小写；字段本身保留原样。 */
export const lowercase: Codec<string> = Object.freeze({ name: "lowercase", encode: (v: string) => v.toLowerCase() });

/** 每一位数字换成 9 减它。 */
function complement(digits: string): string {
  return digits.replace(/\d/g, (d) => String(9 - Number(d)));
}

/** `@index` 的选项。 */
export interface IndexOptions {
  /** 唯一索引：两行的值相同时写入报错。 */
  readonly unique?: boolean;
  /** 字段是数组，每个元素各进一次索引（标签之类）。 */
  readonly multiEntry?: boolean;
  /** 进索引前的编码，见 {@link Codec}。 */
  readonly codec?: Codec<any>;
  /** 允许字段为空；为空的行不在这个索引里（按这个索引查不到它们）。缺省时为空的行写入报错。 */
  readonly optional?: boolean;
}

/** `@store` 的选项。 */
export interface StoreOptions {
  /** 复合索引：字段名的列表，或带 unique 的对象。按 `where(["a", "b"])` 查，只能按前缀查范围。 */
  readonly indexes?: readonly (readonly string[] | { readonly fields: readonly string[]; readonly unique?: boolean })[];
}

/** 一个字段上的声明。 */
interface FieldMeta {
  readonly name: string;
  readonly key?: { readonly codec?: Codec<any> };
  readonly index?: IndexOptions;
  readonly relation?: RelationMeta;
}

/** 一个索引：名字是字段名，复合索引是用 + 连起来的字段名。 */
export interface IndexMeta {
  readonly name: string;
  readonly fields: readonly string[];
  /** 建索引用的 keyPath：有编码器的字段指向影子属性。 */
  readonly keyPath: string | string[];
  readonly unique: boolean;
  readonly multiEntry: boolean;
  readonly optional: boolean;
  /** 各字段的编码器，没有时为 undefined。 */
  readonly codecs: readonly (Codec<any> | undefined)[];
}

/** `@one` / `@many` 声明的关系，由 include 按键批量装载。 */
export interface RelationMeta {
  readonly kind: "one" | "many";
  /** 关系的字段名。 */
  readonly name: string;
  readonly target: () => StoreClass;
  /** one：本行上指向目标主键的字段；many：目标上指回本行主键的字段（必须有索引）。 */
  readonly field: string;
}

/** 一个仓库的元数据，由 {@link storeMeta} 从装饰器算出并缓存。 */
export interface StoreMeta {
  readonly name: string;
  readonly type: StoreClass;
  /** 主键：字段、keyPath 与编码器；单字段时 keyPath 是字符串。 */
  readonly key: IndexMeta;
  /** 按名字的索引，不含主键。 */
  readonly indexes: ReadonlyMap<string, IndexMeta>;
  /** 有编码器的字段：写入时把编码后的值存到影子属性。 */
  readonly encoded: readonly { readonly field: string; readonly codec: Codec<any>; readonly multiEntry: boolean }[];
  readonly relations: ReadonlyMap<string, RelationMeta>;
}

type Bag = Record<symbol, unknown>;

/** 取装饰器上下文的元数据对象；运行时没有 Symbol.metadata 时给出明确的报错。 */
function bag(context: { metadata: DecoratorMetadataObject | undefined; name: string | symbol | undefined }): Bag {
  if (!context.metadata) throw new Error(`No decorator metadata for ${String(context.name)}; import @zerodep-dev/idb before declaring stores`);
  return context.metadata as Bag;
}

/** 记下字段上的声明；每个类各有一份列表的副本，子类只扩展而不改动基类。 */
function record(context: ClassFieldDecoratorContext, field: Omit<FieldMeta, "name">): void {
  if (context.static || context.private) throw new Error(`@key, @index, @one and @many apply to public instance fields, not ${String(context.name)}`);
  const meta = bag(context);
  const own = Object.hasOwn(meta, FIELDS) ? (meta[FIELDS] as FieldMeta[]) : ((meta[FIELDS] = [...((meta[FIELDS] as FieldMeta[] | undefined) ?? [])]) as FieldMeta[]);
  const name = String(context.name);
  const i = own.findIndex((f) => f.name === name);
  const merged: FieldMeta = { ...(i >= 0 ? own[i] : undefined), ...field, name };
  if (i >= 0) own[i] = merged;
  else own.push(merged);
}

/** 是不是标准装饰器的上下文（区分 `@index` 与 `@index({...})`）。 */
function isContext(value: unknown): value is ClassFieldDecoratorContext {
  return typeof value === "object" && value !== null && "kind" in value && "metadata" in value;
}

type FieldDecorator = (value: undefined, context: ClassFieldDecoratorContext) => void;

/** 标记对象仓库，name 是 IndexedDB 里的仓库名。读回的行是这个类的实例。 */
export function store(name: string, options: StoreOptions = {}) {
  return (_target: StoreClass, context: ClassDecoratorContext): void => {
    bag(context)[STORE] = { name, options };
  };
}

/** 主键字段；多个 `@key` 时按声明顺序组成复合主键。`@key({ codec: bigint })` 先编码再当键。 */
export function key(value: undefined, context: ClassFieldDecoratorContext): void;
export function key(options: { readonly codec: Codec<any> }): FieldDecorator;
export function key(a: undefined | { readonly codec: Codec<any> }, b?: ClassFieldDecoratorContext): void | FieldDecorator {
  if (isContext(b)) return record(b, { key: {} });
  const options = a!;
  if (options.codec.decode === undefined) throw new Error(`The ${options.codec.name} codec cannot encode a primary key: it has no decode`);
  return (_value, context) => record(context, { key: { codec: options.codec } });
}

/** 单字段索引：`@index`、`@index({ unique: true })`、`@index({ multiEntry: true })`、`@index({ codec: boolean })`。 */
export function index(value: undefined, context: ClassFieldDecoratorContext): void;
export function index(options: IndexOptions): FieldDecorator;
export function index(a: undefined | IndexOptions, b?: ClassFieldDecoratorContext): void | FieldDecorator {
  if (isContext(b)) return record(b, { index: {} });
  const options = Object.freeze({ ...a });
  return (_value, context) => record(context, { index: options });
}

/** 多对一：本行的 field 是目标仓库的主键，`include("名字")` 时按主键批量装回。这个字段不存进记录。 */
export function one(target: () => StoreClass, field: string): FieldDecorator {
  return (_value, context) => record(context, { relation: { kind: "one", name: String(context.name), target, field } });
}

/** 一对多：目标仓库的 field（必须有索引）指回本行的主键，`include("名字")` 时按索引批量装回。这个字段不存进记录。 */
export function many(target: () => StoreClass, field: string): FieldDecorator {
  return (_value, context) => record(context, { relation: { kind: "many", name: String(context.name), target, field } });
}

const CACHE = new WeakMap<StoreClass, StoreMeta>();

/** 仓库类的元数据；没有 `@store` 或没有 `@key` 时报错。 */
export function storeMeta(type: StoreClass): StoreMeta {
  let meta = CACHE.get(type);
  if (meta === undefined) CACHE.set(type, (meta = build(type)));
  return meta;
}

/** 从装饰器记下的声明算出仓库的元数据。 */
function build(type: StoreClass): StoreMeta {
  const bagOf = (type as unknown as Record<symbol, Bag | undefined>)[METADATA];
  const declared = bagOf?.[STORE] as { name: string; options: StoreOptions } | undefined;
  if (declared === undefined) throw new Error(`${type.name} is not a store; add @store("name")`);
  const fields = (bagOf![FIELDS] as FieldMeta[] | undefined) ?? [];
  const where = `${type.name} (store ${declared.name})`;
  const byName = new Map(fields.map((f) => [f.name, f]));

  const keys = fields.filter((f) => f.key !== undefined);
  if (keys.length === 0) throw new Error(`${where} has no @key field`);
  const path = (f: FieldMeta | undefined, name: string) => ((f?.key?.codec ?? f?.index?.codec) === undefined ? name : `${SHADOW}.${name}`);
  const indexOf = (name: string, names: readonly string[], options: IndexOptions, codecOf: (f: string) => Codec<any> | undefined): IndexMeta => {
    for (const n of names) if (byName.get(n)?.relation !== undefined) throw new Error(`${where}: ${n} is a relation and cannot be indexed`);
    const paths = names.map((n) => path(byName.get(n), n));
    return Object.freeze({
      name,
      fields: Object.freeze([...names]),
      keyPath: paths.length === 1 ? paths[0]! : paths,
      unique: options.unique ?? false,
      multiEntry: options.multiEntry ?? false,
      optional: options.optional ?? false,
      codecs: Object.freeze(names.map(codecOf)),
    });
  };
  const key = indexOf(
    "",
    keys.map((k) => k.name),
    { unique: true },
    (n) => byName.get(n)!.key!.codec,
  );

  const indexes = new Map<string, IndexMeta>();
  for (const f of fields) {
    if (f.index === undefined) continue;
    if (f.key !== undefined) throw new Error(`${where}: ${f.name} is the primary key; it needs no @index`);
    indexes.set(f.name, indexOf(f.name, [f.name], f.index, () => f.index!.codec));
  }
  for (const c of declared.options.indexes ?? []) {
    const names = "fields" in c ? c.fields : (c as readonly string[]);
    if (names.length < 2) throw new Error(`${where}: a compound index needs two or more fields; use @index for one`);
    const name = names.join("+");
    if (indexes.has(name)) throw new Error(`${where} declares the index ${name} twice`);
    indexes.set(name, indexOf(name, names, { unique: "fields" in c ? (c.unique ?? false) : false }, (n) => byName.get(n)?.index?.codec));
  }

  const encoded = fields.flatMap((f) => {
    const codec = f.key?.codec ?? f.index?.codec;
    return codec === undefined ? [] : [{ field: f.name, codec, multiEntry: f.index?.multiEntry ?? false }];
  });
  const relations = new Map(fields.filter((f) => f.relation !== undefined).map((f) => [f.name, f.relation!]));
  return Object.freeze({ name: declared.name, type, key, indexes, encoded: Object.freeze(encoded), relations });
}

/** 值能不能当 IndexedDB 的键：数字（非 NaN）、字符串、有效的 Date、二进制，或由它们组成的数组。 */
export function isKey(value: unknown): value is IDBValidKey {
  if (typeof value === "number") return !Number.isNaN(value);
  if (typeof value === "string") return true;
  if (value instanceof Date) return !Number.isNaN(value.getTime());
  if (value instanceof ArrayBuffer || ArrayBuffer.isView(value)) return true;
  return Array.isArray(value) && value.every(isKey);
}

/** 值的类型说明，用于报错。 */
export function describe(value: unknown): string {
  if (value === null || value === undefined) return String(value);
  if (Array.isArray(value)) return "an array";
  if (value instanceof Date) return "an invalid Date";
  return typeof value === "object" ? (value.constructor?.name ?? "an object") : `${typeof value} ${String(value)}`;
}

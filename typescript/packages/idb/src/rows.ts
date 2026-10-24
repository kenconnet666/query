import { describe, isKey, SHADOW, type IndexMeta, type StoreMeta } from "./metadata.js";

/** 行不能写入：主键或索引字段为空、不是合法的键。problems 逐条说明是哪个字段。 */
export class InvalidRowError extends TypeError {
  constructor(
    readonly store: string,
    readonly problems: readonly string[],
  ) {
    super(`Cannot write the row to ${store}: ${problems.join("; ")}`);
    this.name = "InvalidRowError";
  }
}

/**
 * 把行变成要存的记录：复制自有的字段（关系字段不存），有编码器的主键和索引字段另把编码后的值放进影子属性。
 * 主键和索引字段为空或不是合法的键时报 {@link InvalidRowError}——否则 IndexedDB 会让这行悄悄地不进索引。
 */
export function toRecord(meta: StoreMeta, row: object): Record<string, unknown> {
  const source = row as Record<string, unknown>;
  const record: Record<string, unknown> = {};
  for (const name of Object.keys(source)) if (name !== SHADOW && !meta.relations.has(name)) record[name] = source[name];
  const shadow: Record<string, unknown> = {};
  for (const e of meta.encoded) {
    const v = source[e.field];
    if (v == null) continue;
    try {
      shadow[e.field] = e.multiEntry && Array.isArray(v) ? v.map((x) => e.codec.encode(x)) : e.codec.encode(v);
    } catch (error) {
      throw new InvalidRowError(meta.name, [`${e.field} cannot be encoded by ${e.codec.name}: ${(error as Error).message}`]);
    }
  }
  const problems = [...keyProblems(meta, meta.key, source, shadow), ...[...meta.indexes.values()].flatMap((i) => keyProblems(meta, i, source, shadow))];
  if (problems.length > 0) throw new InvalidRowError(meta.name, problems);
  if (meta.encoded.length > 0) record[SHADOW] = shadow;
  return record;
}

/** 一个主键或索引上的问题：字段为空（索引声明了 optional 时允许）、不是合法的键。 */
function keyProblems(meta: StoreMeta, index: IndexMeta, row: Record<string, unknown>, shadow: Record<string, unknown>): string[] {
  const what = index === meta.key ? "the primary key" : `the index ${index.name}`;
  const out: string[] = [];
  for (const f of index.fields) {
    const raw = row[f];
    if (raw == null) {
      if (index !== meta.key && (index.optional || meta.indexes.get(f)?.optional)) return []; // 这行不进这个索引，是声明允许的
      out.push(`${f} is ${String(raw)}, but ${what} needs it${index === meta.key ? "" : " (declare @index({ optional: true }) if rows may leave it empty)"}`);
      continue;
    }
    const v = f in shadow ? shadow[f] : raw;
    if (index.multiEntry) {
      if (!Array.isArray(v) || !v.every(isKey)) out.push(`${f} must be an array of valid IndexedDB keys for the multiEntry index ${index.name}`);
    } else if (!isKey(v)) {
      out.push(`${f} is ${describe(raw)}, which is not a valid IndexedDB key (${what}); keys are numbers, strings, Dates, binary or arrays of those — give the field a codec`);
    }
  }
  return out;
}

/** 读回的记录还原成仓库类的实例：去掉影子属性，换上类的原型。 */
export function fromRecord<T>(meta: StoreMeta, record: unknown): T {
  const r = record as Record<string, unknown>;
  delete r[SHADOW];
  return Object.setPrototypeOf(r, meta.type.prototype) as T;
}

/** 查询或主键的值编码成键：复合的给数组（可以只给前缀），有编码器的字段先编码。不是合法的键时报错。 */
export function encodeKey(meta: StoreMeta, index: IndexMeta, value: unknown): IDBValidKey {
  const label = index === meta.key ? `the primary key of ${meta.name}` : `the index ${meta.name}.${index.name}`;
  let key: unknown;
  if (index.fields.length === 1) {
    const codec = index.codecs[0];
    key = codec === undefined || value == null ? value : codec.encode(value);
  } else {
    if (!Array.isArray(value) || value.length === 0 || value.length > index.fields.length) throw new TypeError(`${label} takes an array of 1 to ${index.fields.length} values (${index.fields.join(", ")})`);
    key = value.map((v, i) => {
      const codec = index.codecs[i];
      return codec === undefined || v == null ? v : codec.encode(v);
    });
  }
  if (!isKey(key)) throw new TypeError(`${describe(value)} is not a valid value for ${label}`);
  return key;
}

/** 行的主键（编码后的键）；复合主键是数组。 */
export function primaryKeyOf(meta: StoreMeta, row: object): IDBValidKey {
  const r = row as Record<string, unknown>;
  const { fields } = meta.key;
  return encodeKey(meta, meta.key, fields.length === 1 ? r[fields[0]!] : fields.map((f) => r[f]));
}

/** 存的主键还原成字段的值：有 decode 的编码器先解码；复合主键是数组。 */
export function decodeKey(meta: StoreMeta, key: IDBValidKey): unknown {
  const { codecs } = meta.key;
  if (codecs.length === 1) return codecs[0]?.decode === undefined ? key : codecs[0].decode(key);
  return (key as IDBValidKey[]).map((k, i) => (codecs[i]?.decode === undefined ? k : codecs[i].decode(k)));
}

/** 记录上 keyPath 指向的值（主键或索引的键）。 */
export function keyAt(record: unknown, keyPath: string | readonly string[]): IDBValidKey {
  const at = (path: string) => path.split(".").reduce<unknown>((o, p) => (o as Record<string, unknown> | undefined)?.[p], record);
  return (typeof keyPath === "string" ? at(keyPath) : keyPath.map(at)) as IDBValidKey;
}

/** 键的文本形式，用于去重：同一个键总是同一段文本，不同的键不同。 */
export function keyText(key: IDBValidKey): string {
  const tag = (k: IDBValidKey): unknown => {
    if (typeof k === "number") return ["n", k];
    if (typeof k === "string") return ["s", k];
    if (k instanceof Date) return ["d", k.getTime()];
    if (Array.isArray(k)) return k.map(tag);
    const bytes = k instanceof ArrayBuffer ? new Uint8Array(k) : new Uint8Array(k.buffer, k.byteOffset, k.byteLength);
    return ["b", Array.from(bytes)];
  };
  return JSON.stringify(tag(key));
}

/**
 * 值的逻辑种类。方言据此渲染字面量和 CAST 目标，SUM 等函数据此做检查，
 * 因为 TypeScript 区分不出 decimal 字符串和文本列。
 */
export type TypeKind =
  | "text"
  | "integer"
  | "bigint"
  | "double"
  | "decimal"
  | "boolean"
  | "date"
  | "timestamp"
  | "bytes"
  | "unknown";

/**
 * TypeScript 类型 T 的值如何传入和读出数据库。驱动把 decimal、大整数、日期和时间戳都当作
 * 字符串交接，所以解码从不经过 `Date` 或进程时区。
 */
export interface SqlType<T> {
  readonly name: string;
  readonly kind: TypeKind;
  /** 作为参数绑定的值；不会以 null 调用。 */
  encode(value: T): unknown;
  /** 从驱动读到的值；不会以 null 调用。 */
  decode(raw: unknown): T;
}

/**
 * 在字段类型 J 与存储类型 D 之间转换，类似 Java 的 `Converter<J, D>`。
 * 读取时，表示“无值”的存储值（例如 SAP 的空日期）可以得到 null；这样的字段要声明为 `| null`。
 */
export interface Converter<J, D> {
  toDatabase(value: J): D;
  fromDatabase(value: D): J | null;
}

/** 创建不可变的 {@link SqlType}。 */
function type<T>(name: string, kind: TypeKind, encode: (value: T) => unknown, decode: (raw: unknown) => T): SqlType<T> {
  return Object.freeze({ name, kind, encode, decode });
}

/** 报告无法把 `raw` 读成指定类型。 */
function fail(type: string, raw: unknown): never {
  const shown = raw instanceof Uint8Array ? "bytes" : typeof raw === "string" ? JSON.stringify(raw) : String(raw);
  throw new TypeError(`Cannot read ${shown} (${typeof raw}) as ${type}`);
}

/** decimal、日期、时间戳文本的格式。 */
const DECIMAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const TIMESTAMP = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/;

/** 校验是真实存在的日历日期（含闰年），格式为 'YYYY-MM-DD'。 */
function checkDate(value: string, type: string): string {
  const m = DATE.exec(value);
  if (!m) throw new TypeError(`${type} needs 'YYYY-MM-DD', got ${JSON.stringify(value)}`);
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  if (days === undefined || day < 1 || day > days) throw new RangeError(`${JSON.stringify(value)} is not a calendar date`);
  return value;
}

/** 无论驱动返回什么形式，都规整成 'YYYY-MM-DD HH:MM:SS' 加去掉末尾零的小数部分。 */
function normalizeTimestamp(value: string, type: string): string {
  const m = TIMESTAMP.exec(value.trim());
  if (!m) throw new TypeError(`${type} needs 'YYYY-MM-DD HH:MM:SS[.fraction]', got ${JSON.stringify(value)}`);
  checkDate(m[1]!, type);
  const [hour, minute, second] = [Number(m[2]), Number(m[3]), Number(m[4] ?? "0")];
  if (hour > 23 || minute > 59 || second > 59) throw new RangeError(`${JSON.stringify(value)} has an invalid time of day`);
  const fraction = (m[5] ?? "").replace(/0+$/, "");
  return `${m[1]} ${m[2]}:${m[3]}:${m[4] ?? "00"}${fraction ? "." + fraction : ""}`;
}

/** 把驱动返回的 number、bigint 或数字字符串读成 number。 */
function toNumber(raw: unknown, type: string): number {
  if (typeof raw === "number") return raw;
  if (typeof raw === "bigint") return Number(raw);
  if (typeof raw === "string" && raw.trim() !== "" && !Number.isNaN(Number(raw))) return Number(raw);
  return fail(type, raw);
}

/** 解析 PostgreSQL 的 `\x...` 十六进制字节串。 */
function hexBytes(raw: string): Uint8Array {
  const hex = raw.slice(2);
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** 文本。 */
export const text: SqlType<string> = type(
  "text",
  "text",
  (v) => v,
  (raw) => (typeof raw === "string" ? raw : typeof raw === "number" || typeof raw === "bigint" ? String(raw) : fail("text", raw)),
);

/** 安全范围内的整数；超出时请改用 {@link bigint}。 */
export const integer: SqlType<number> = type(
  "integer",
  "integer",
  (v) => {
    if (!Number.isSafeInteger(v)) throw new RangeError(`${v} is not a safe integer`);
    return v;
  },
  (raw) => {
    const n = toNumber(raw, "integer");
    if (!Number.isSafeInteger(n)) throw new RangeError(`${String(raw)} does not fit a safe integer; use bigint`);
    return n;
  },
);

/** 小整数：查询和写入与 {@link integer} 相同，只在结构变更里写成 SMALLINT。 */
export const smallint: SqlType<number> = Object.freeze({ ...integer, name: "smallint" });

/** 任意大小的整数，对应 JavaScript 的 bigint。 */
export const bigint: SqlType<bigint> = type(
  "bigint",
  "bigint",
  (v) => v,
  (raw) => {
    if (typeof raw === "bigint") return raw;
    if (typeof raw === "number" && Number.isInteger(raw)) return BigInt(raw);
    if (typeof raw === "string" && /^\s*[+-]?\d+\s*$/.test(raw)) return BigInt(raw.trim());
    return fail("bigint", raw);
  },
);

/** 双精度浮点数。 */
export const double: SqlType<number> = type("double", "double", (v) => v, (raw) => toNumber(raw, "double"));

/** 精确的 decimal，以十进制文本表示，例如 '3.000'；只要普通数字就在该列上用 `double`。 */
export const decimal: SqlType<string> = type(
  "decimal",
  "decimal",
  (v) => {
    if (!DECIMAL.test(v)) throw new TypeError(`${JSON.stringify(v)} is not a decimal number`);
    return v;
  },
  (raw) => {
    const s = typeof raw === "string" ? raw.trim() : typeof raw === "number" || typeof raw === "bigint" ? String(raw) : fail("decimal", raw);
    return DECIMAL.test(s) ? s : fail("decimal", raw);
  },
);

/** 布尔值；也接受 0 / 1（含 bigint，SQLite 按整数存布尔）和 't' / 'f' 等文本。 */
export const boolean: SqlType<boolean> = type(
  "boolean",
  "boolean",
  (v) => v,
  (raw) => {
    if (typeof raw === "boolean") return raw;
    if (raw === 1 || raw === 0 || raw === 1n || raw === 0n) return raw === 1 || raw === 1n;
    if (typeof raw === "string") {
      const s = raw.trim().toLowerCase();
      if (s === "t" || s === "true" || s === "1") return true;
      if (s === "f" || s === "false" || s === "0") return false;
    }
    return fail("boolean", raw);
  },
);

/** 日历日期，形如 'YYYY-MM-DD'。 */
export const date: SqlType<string> = type(
  "date",
  "date",
  (v) => checkDate(v, "date"),
  (raw) => (typeof raw === "string" ? checkDate(raw.trim().slice(0, 10), "date") : fail("date", raw)),
);

/** 不带时区的本地日期时间，形如 'YYYY-MM-DD HH:MM:SS[.fraction]'。 */
export const timestamp: SqlType<string> = type(
  "timestamp",
  "timestamp",
  (v) => normalizeTimestamp(v, "timestamp"),
  (raw) => (typeof raw === "string" ? normalizeTimestamp(raw, "timestamp") : fail("timestamp", raw)),
);

/** 二进制数据。 */
export const bytes: SqlType<Uint8Array> = type(
  "bytes",
  "bytes",
  (v) => v,
  (raw) => {
    if (raw instanceof Uint8Array) return raw;
    if (typeof raw === "string" && raw.startsWith("\\x")) return hexBytes(raw);
    return fail("bytes", raw);
  },
);

/** 值原样取自驱动的返回；无类型的 SQL 片段使用它。 */
export const untyped: SqlType<unknown> = type("untyped", "unknown", (v) => v, (raw) => raw);

/** 以另一种类型存储的字段类型，例如 SAP 把日期存成 'yyyyMMdd' 文本。 */
export function converted<J, D>(storage: SqlType<D>, converter: Converter<J, D>, name = `converted ${storage.name}`): SqlType<J> {
  return type(
    name,
    storage.kind,
    (v) => storage.encode(converter.toDatabase(v)),
    (raw) => converter.fromDatabase(storage.decode(raw)) as J,
  );
}

/** JavaScript 值的类型，用于没有声明类型的参数和字面量。 */
export function typeOf(value: unknown): SqlType<unknown> {
  switch (typeof value) {
    case "string":
      return text as SqlType<unknown>;
    case "number":
      return (Number.isInteger(value) ? integer : double) as SqlType<unknown>;
    case "bigint":
      return bigint as SqlType<unknown>;
    case "boolean":
      return boolean as SqlType<unknown>;
    default:
      if (value instanceof Uint8Array) return bytes as SqlType<unknown>;
      throw new TypeError(`No SQL type for ${value === null ? "null" : typeof value}; pass a type explicitly`);
  }
}

/** 逻辑上的 CAST 目标；各方言渲染自己的类型名。 */
export class CastType<T> {
  private constructor(
    readonly kind: Exclude<TypeKind, "bytes" | "unknown">,
    readonly type: SqlType<T>,
    readonly length = 0,
    readonly scale = 0,
  ) {}

  /** VARCHAR(length)。 */
  static text(length: number): CastType<string> {
    if (!Number.isInteger(length) || length < 1) throw new RangeError("Text length must be a positive integer");
    return new CastType("text", text, length);
  }

  /** DECIMAL(precision, scale)。 */
  static decimal(precision: number, scale: number): CastType<string> {
    if (!Number.isInteger(precision) || !Number.isInteger(scale) || precision < 1 || scale < 0 || scale > precision) {
      throw new RangeError(`Invalid DECIMAL(${precision},${scale})`);
    }
    return new CastType("decimal", decimal, precision, scale);
  }

  /** 整数。 */
  static integer(): CastType<number> {
    return new CastType("integer", integer);
  }

  /** 大整数。 */
  static bigint(): CastType<bigint> {
    return new CastType("bigint", bigint);
  }

  /** 双精度浮点数。 */
  static double(): CastType<number> {
    return new CastType("double", double);
  }

  /** 日期。 */
  static date(): CastType<string> {
    return new CastType("date", date);
  }

  /** 时间戳。 */
  static timestamp(): CastType<string> {
    return new CastType("timestamp", timestamp);
  }

  /** 布尔值。 */
  static boolean(): CastType<boolean> {
    return new CastType("boolean", boolean);
  }
}

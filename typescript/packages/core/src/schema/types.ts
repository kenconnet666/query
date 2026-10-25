import { CalendarDate, CalendarDateTime } from "@internationalized/date";
import { parseDate, parseTimestamp } from "@zerodep-dev/wire";
import { Decimal } from "decimal.js";

/**
 * 值的逻辑种类，也就是库里怎样存：方言据此渲染字面量和 CAST 目标，列据此选择可用的函数
 * （以文本存的日期是 `text`，只有文本函数）。与 Java 的逻辑 SQL 类型是同一套词。
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
 * TypeScript 类型 T 的值如何传入和读出数据库，K 是存储的种类。小数是 decimal.js 的 `Decimal`，日期和时间戳是
 * @internationalized/date 的 `CalendarDate`、`CalendarDateTime`（与前端组件一致）；驱动把它们都当作文本交接，
 * 所以解码从不经过 `Date` 或进程时区。
 */
export interface SqlType<T, K extends TypeKind = TypeKind> {
  readonly name: string;
  readonly kind: K;
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
function type<T, K extends TypeKind>(name: string, kind: K, encode: (value: T) => unknown, decode: (raw: unknown) => T): SqlType<T, K> {
  return Object.freeze({ name, kind, encode, decode });
}

/** 报告无法把 `raw` 读成指定类型。 */
function fail(type: string, raw: unknown): never {
  const shown = raw instanceof Uint8Array ? "bytes" : typeof raw === "string" ? JSON.stringify(raw) : String(raw);
  throw new TypeError(`Cannot read ${shown} (${typeof raw}) as ${type}`);
}

/** decimal 文本的格式；日期与时间戳的文本由 wire 包的 parseDate、parseTimestamp 严格解析（含日历校验）。 */
const DECIMAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/** 'YYYY-MM-DD HH:MM:SS' 加去掉末尾零的毫秒；`toString` 已经换算成公历，只把 T 换成空格，各库都认这种写法。 */
function timestampText(value: CalendarDateTime): string {
  return value.toString().replace("T", " ");
}

/** 小数文本是否合法，合法时原样返回。 */
function decimalText(value: string, type: string): string {
  if (!DECIMAL.test(value)) throw new TypeError(`${JSON.stringify(value)} is not a ${type} number`);
  return value;
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
export const text: SqlType<string, "text"> = type(
  "text",
  "text",
  (v) => v,
  (raw) => (typeof raw === "string" ? raw : typeof raw === "number" || typeof raw === "bigint" ? String(raw) : fail("text", raw)),
);

/** 安全范围内的整数；超出时请改用 {@link bigint}。 */
export const integer: SqlType<number, "integer"> = type(
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
export const smallint: SqlType<number, "integer"> = Object.freeze({ ...integer, name: "smallint" });

/** 任意大小的整数，对应 JavaScript 的 bigint。 */
export const bigint: SqlType<bigint, "bigint"> = type(
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
export const double: SqlType<number, "double"> = type("double", "double", (v) => v, (raw) => toNumber(raw, "double"));

// 小数、日期、时间戳的值是对象，绑定时也接受它们的文本形式（按格式校验）：条件对象、原生 SQL 的参数常常是请求里的文本。

/**
 * 精确的小数，值是 decimal.js 的 `Decimal`，以十进制文本绑定和读取。`Decimal` 不保留末尾的零，`new Decimal("12.50")` 写出 12.5。
 * 只要普通数字时在该列上用 `double`。
 */
export const decimal: SqlType<Decimal, "decimal"> = type(
  "decimal",
  "decimal",
  (v) => (typeof v === "string" ? decimalText(v, "decimal") : v.toFixed()),
  (raw) => {
    if (Decimal.isDecimal(raw)) return raw;
    const s = typeof raw === "string" ? raw.trim() : typeof raw === "number" || typeof raw === "bigint" ? String(raw) : fail("decimal", raw);
    return DECIMAL.test(s) ? new Decimal(s) : fail("decimal", raw);
  },
);

/** 布尔值；也接受 0 / 1（含 bigint，SQLite 按整数存布尔）和 't' / 'f' 等文本。 */
export const boolean: SqlType<boolean, "boolean"> = type(
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

/** 日历日期，值是 `CalendarDate`，以 'YYYY-MM-DD' 绑定和读取。 */
export const date: SqlType<CalendarDate, "date"> = type(
  "date",
  "date",
  (v) => (typeof v === "string" ? parseDate(v) : v).toString(),
  (raw) => {
    if (raw instanceof CalendarDate) return raw;
    return typeof raw === "string" ? parseDate(raw.trim().slice(0, 10)) : fail("date", raw);
  },
);

/**
 * 不带时区的本地日期时间，值是 `CalendarDateTime`，以 'YYYY-MM-DD HH:MM:SS[.fraction]' 绑定和读取。
 * `CalendarDateTime` 精确到毫秒：HANA 等更细的时间戳读出来时舍去毫秒以下的部分。
 */
export const timestamp: SqlType<CalendarDateTime, "timestamp"> = type(
  "timestamp",
  "timestamp",
  (v) => timestampText(typeof v === "string" ? parseTimestamp(String(v).trim()) : v),
  (raw) => {
    if (raw instanceof CalendarDateTime) return raw;
    return typeof raw === "string" ? parseTimestamp(raw.trim()) : fail("timestamp", raw);
  },
);

/** 二进制数据。 */
export const bytes: SqlType<Uint8Array, "bytes"> = type(
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
export const untyped: SqlType<unknown, "unknown"> = type("untyped", "unknown", (v) => v, (raw) => raw);

/**
 * 以另一种类型存储的字段类型，例如 SAP 把日期存成 'yyyyMMdd' 文本：存储的种类 K 不变，所以这样的列只有存储类型的函数。
 * 字段类型与存储类型不是同一类时（日期存成文本），字段要声明成 `Stored<字段类型, 存储类型>`，`@column` 会检查。
 */
export function converted<J, D, K extends TypeKind>(storage: SqlType<D, K>, converter: Converter<J, D>, name = `converted ${storage.name}`): SqlType<J, K> {
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
      if (Decimal.isDecimal(value)) return decimal as SqlType<unknown>;
      if (value instanceof CalendarDateTime) return timestamp as SqlType<unknown>;
      if (value instanceof CalendarDate) return date as SqlType<unknown>;
      throw new TypeError(`No SQL type for ${value === null ? "null" : typeof value}; pass a type explicitly`);
  }
}

/** 逻辑上的 CAST 目标；各方言渲染自己的类型名。结果的类型接口由 T 决定（`CastType.decimal(15, 3)` 得到 `ExprDecimal`）。 */
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
  static decimal(precision: number, scale: number): CastType<Decimal> {
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
  static date(): CastType<CalendarDate> {
    return new CastType("date", date);
  }

  /** 时间戳。 */
  static timestamp(): CastType<CalendarDateTime> {
    return new CastType("timestamp", timestamp);
  }

  /** 布尔值。 */
  static boolean(): CastType<boolean> {
    return new CastType("boolean", boolean);
  }
}

/**
 * The logical kind of a value. Dialects render literals and CAST targets from it, and functions
 * such as SUM check it, because TypeScript cannot tell a decimal string from a text column.
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
 * How values of TypeScript type T travel to and from the database. Drivers hand decimals, big
 * integers, dates and timestamps over as strings, so decoding never goes through `Date` or the
 * process time zone.
 */
export interface SqlType<T> {
  readonly name: string;
  readonly kind: TypeKind;
  /** The value bound as a parameter; never called with null. */
  encode(value: T): unknown;
  /** The value read from a driver; never called with null. */
  decode(raw: unknown): T;
}

/**
 * Converts between a field's type J and the stored type D, like a Java `Converter<J, D>`. Reading
 * may give null for a stored value that means "none", e.g. an empty SAP date; declare such a field
 * with `| null`.
 */
export interface Converter<J, D> {
  toDatabase(value: J): D;
  fromDatabase(value: D): J | null;
}

function type<T>(name: string, kind: TypeKind, encode: (value: T) => unknown, decode: (raw: unknown) => T): SqlType<T> {
  return Object.freeze({ name, kind, encode, decode });
}

function fail(type: string, raw: unknown): never {
  const shown = raw instanceof Uint8Array ? "bytes" : typeof raw === "string" ? JSON.stringify(raw) : String(raw);
  throw new TypeError(`Cannot read ${shown} (${typeof raw}) as ${type}`);
}

const DECIMAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const TIMESTAMP = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/;

function checkDate(value: string, type: string): string {
  const m = DATE.exec(value);
  if (!m) throw new TypeError(`${type} needs 'YYYY-MM-DD', got ${JSON.stringify(value)}`);
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  if (days === undefined || day < 1 || day > days) throw new RangeError(`${JSON.stringify(value)} is not a calendar date`);
  return value;
}

/** 'YYYY-MM-DD HH:MM:SS' plus the fraction without trailing zeros, whatever the driver sent. */
function normalizeTimestamp(value: string, type: string): string {
  const m = TIMESTAMP.exec(value.trim());
  if (!m) throw new TypeError(`${type} needs 'YYYY-MM-DD HH:MM:SS[.fraction]', got ${JSON.stringify(value)}`);
  checkDate(m[1]!, type);
  const [hour, minute, second] = [Number(m[2]), Number(m[3]), Number(m[4] ?? "0")];
  if (hour > 23 || minute > 59 || second > 59) throw new RangeError(`${JSON.stringify(value)} has an invalid time of day`);
  const fraction = (m[5] ?? "").replace(/0+$/, "");
  return `${m[1]} ${m[2]}:${m[3]}:${m[4] ?? "00"}${fraction ? "." + fraction : ""}`;
}

function toNumber(raw: unknown, type: string): number {
  if (typeof raw === "number") return raw;
  if (typeof raw === "bigint") return Number(raw);
  if (typeof raw === "string" && raw.trim() !== "" && !Number.isNaN(Number(raw))) return Number(raw);
  return fail(type, raw);
}

function hexBytes(raw: string): Uint8Array {
  const hex = raw.slice(2);
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

export const text: SqlType<string> = type(
  "text",
  "text",
  (v) => v,
  (raw) => (typeof raw === "string" ? raw : typeof raw === "number" || typeof raw === "bigint" ? String(raw) : fail("text", raw)),
);

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

export const double: SqlType<number> = type("double", "double", (v) => v, (raw) => toNumber(raw, "double"));

/** Exact decimals as their decimal text, e.g. '3.000'; use `double` on the column for plain numbers. */
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

export const boolean: SqlType<boolean> = type(
  "boolean",
  "boolean",
  (v) => v,
  (raw) => {
    if (typeof raw === "boolean") return raw;
    if (raw === 1 || raw === 0) return raw === 1;
    if (typeof raw === "string") {
      const s = raw.trim().toLowerCase();
      if (s === "t" || s === "true" || s === "1") return true;
      if (s === "f" || s === "false" || s === "0") return false;
    }
    return fail("boolean", raw);
  },
);

/** A calendar date as 'YYYY-MM-DD'. */
export const date: SqlType<string> = type(
  "date",
  "date",
  (v) => checkDate(v, "date"),
  (raw) => (typeof raw === "string" ? checkDate(raw.trim().slice(0, 10), "date") : fail("date", raw)),
);

/** A local date-time without time zone as 'YYYY-MM-DD HH:MM:SS[.fraction]'. */
export const timestamp: SqlType<string> = type(
  "timestamp",
  "timestamp",
  (v) => normalizeTimestamp(v, "timestamp"),
  (raw) => (typeof raw === "string" ? normalizeTimestamp(raw, "timestamp") : fail("timestamp", raw)),
);

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

/** Values exactly as the driver returns them; used by untyped SQL fragments. */
export const untyped: SqlType<unknown> = type("untyped", "unknown", (v) => v, (raw) => raw);

/** A field type stored as another type, e.g. SAP dates kept as 'yyyyMMdd' text. */
export function converted<J, D>(storage: SqlType<D>, converter: Converter<J, D>, name = `converted ${storage.name}`): SqlType<J> {
  return type(
    name,
    storage.kind,
    (v) => storage.encode(converter.toDatabase(v)),
    (raw) => converter.fromDatabase(storage.decode(raw)) as J,
  );
}

/** The type of a JavaScript value, for parameters and literals written without a declared type. */
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

/** A logical CAST target; each dialect renders its own type name. */
export class CastType<T> {
  private constructor(
    readonly kind: Exclude<TypeKind, "bytes" | "unknown">,
    readonly type: SqlType<T>,
    readonly length = 0,
    readonly scale = 0,
  ) {}

  static text(length: number): CastType<string> {
    if (!Number.isInteger(length) || length < 1) throw new RangeError("Text length must be a positive integer");
    return new CastType("text", text, length);
  }

  static decimal(precision: number, scale: number): CastType<string> {
    if (!Number.isInteger(precision) || !Number.isInteger(scale) || precision < 1 || scale < 0 || scale > precision) {
      throw new RangeError(`Invalid DECIMAL(${precision},${scale})`);
    }
    return new CastType("decimal", decimal, precision, scale);
  }

  static integer(): CastType<number> {
    return new CastType("integer", integer);
  }

  static bigint(): CastType<bigint> {
    return new CastType("bigint", bigint);
  }

  static double(): CastType<number> {
    return new CastType("double", double);
  }

  static date(): CastType<string> {
    return new CastType("date", date);
  }

  static timestamp(): CastType<string> {
    return new CastType("timestamp", timestamp);
  }

  static boolean(): CastType<boolean> {
    return new CastType("boolean", boolean);
  }
}

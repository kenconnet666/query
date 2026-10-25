import { CalendarDate, CalendarDateTime, getLocalTimeZone, parseAbsolute, Time, ZonedDateTime } from "@internationalized/date";
import { Decimal } from "decimal.js";

/**
 * 线上要还原的值的种类：JSON 不能原样承载的值写成文字，`$types` 记下它们在哪里、是哪一种。
 * 与 Java 的 `org.dpdns.zerodep.wire` 是同一套词。
 *
 * | 种类 | TypeScript | Java | 文字 |
 * |---|---|---|---|
 * | bigint | `bigint` | Long、BigInteger | `"9007199254740993"` |
 * | decimal | decimal.js 的 `Decimal` | BigDecimal | `"12.50"`，不用科学计数法 |
 * | double | NaN、±Infinity 的 `number` | Double、Float | `"NaN"`、`"Infinity"`、`"-Infinity"`（有限的值是 JSON 数字） |
 * | date | `CalendarDate` | LocalDate | `"2024-01-31"` |
 * | time | `Time` | LocalTime | `"13:45:00"` |
 * | timestamp | `CalendarDateTime` | LocalDateTime | `"2024-01-31T13:45:00"`，秒以下有值时带小数 |
 * | timestamptz | `ZonedDateTime`、`Date` | OffsetDateTime、Instant、ZonedDateTime | `"2024-01-31T05:45:00Z"`，换算成 UTC |
 * | bytes | `Uint8Array` | byte[] | 标准 base64 |
 *
 * 其余的值照 JSON 原样：文字、布尔、安全范围内的整数与有限的小数（Java 的 Integer、Short、Double）。
 */
export type WireType = "bigint" | "decimal" | "double" | "date" | "time" | "timestamp" | "timestamptz" | "bytes";

/** {@link parseWire} 能读的种类：要还原的种类，加上 JSON 原样承载的文字、整数和布尔（地址栏里它们也是文字）。 */
export type WireKind = WireType | "text" | "integer" | "boolean";

/** 还原 timestamptz 时的选项。 */
export interface WireOptions {
  /** timestamptz 还原成哪个时区的 `ZonedDateTime`（IANA 名字），默认是运行环境的本地时区。 */
  readonly timeZone?: string;
}

const INTEGER = /^[+-]?\d+$/;
const DECIMAL = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;
const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const TIME = /^(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/;
const TIMESTAMP = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?)$/;
const TIMESTAMPTZ = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:\d{2})$/i;

/** 值要还原成哪一种；JSON 能原样承载的值（文字、布尔、有限的数字、null）为 undefined。 */
export function wireTypeOf(value: unknown): WireType | undefined {
  switch (typeof value) {
    case "bigint":
      return "bigint";
    case "number":
      return Number.isFinite(value) ? undefined : "double";
    case "object":
      if (value === null) return undefined;
      if (Decimal.isDecimal(value)) return "decimal";
      if (value instanceof CalendarDateTime) return "timestamp";
      if (value instanceof CalendarDate) return "date";
      if (value instanceof Time) return "time";
      if (value instanceof ZonedDateTime || value instanceof Date) return "timestamptz";
      if (value instanceof Uint8Array) return "bytes";
      return undefined;
    default:
      return undefined;
  }
}

/**
 * 一个值写成线上的文字：地址栏参数、表头筛选、表单字段都用它。Decimal 不用科学计数法，日期与时间是 ISO 写法，
 * timestamptz 换算成 UTC（`Z`），字节是 base64；null 与 undefined 是空字符串。对象、数组等不是单个值的报错。
 */
export function wireText(value: unknown): string {
  if (value === null || value === undefined) return "";
  switch (typeof value) {
    case "string":
      return value;
    case "boolean":
    case "bigint":
      return String(value);
    case "number":
      return Object.is(value, -0) ? "0" : String(value);
  }
  const type = wireTypeOf(value);
  if (type === undefined) throw new TypeError(`wireText: ${describe(value)} is not a single value`);
  return specialText(type, value);
}

/** 要还原的值写成的文字；type 是 {@link wireTypeOf} 的结果。 */
export function specialText(type: WireType, value: unknown): string {
  switch (type) {
    case "bigint":
    case "double":
      return String(value);
    case "decimal": {
      const d = value as Decimal;
      return d.isFinite() ? d.toFixed() : d.toString();
    }
    case "timestamptz":
      return utcText(value instanceof Date ? value.getTime() : (value as ZonedDateTime).toDate().getTime());
    case "bytes":
      return encodeBase64(value as Uint8Array);
    default:
      return String(value);
  }
}

/** 某一时刻的 UTC 写法 'YYYY-MM-DDTHH:MM:SS[.fraction]Z'：总有秒，毫秒去掉末尾的零（同 timestamp 的写法与 Java 一侧）。 */
function utcText(epochMillis: number): string {
  return new Date(epochMillis).toISOString().replace(/\.(\d{3})Z$/, (_, ms: string) => (ms === "000" ? "Z" : `.${ms.replace(/0+$/, "")}Z`));
}

/**
 * 按种类读线上的文字，格式与 {@link wireText} 写出的一致：整数要在安全范围内，日期要是真实存在的日历日期，
 * 时间到毫秒（更细的部分舍去），timestamptz 要带 `Z` 或时差。读不出时报错，消息里带上原文。
 */
export function parseWire(kind: WireKind, text: string, options: WireOptions = {}): unknown {
  switch (kind) {
    case "text":
      return text;
    case "integer": {
      const n = INTEGER.test(text) ? Number(text) : Number.NaN;
      if (!Number.isSafeInteger(n)) throw new RangeError(`${JSON.stringify(text)} is not a safe integer`);
      return n;
    }
    case "bigint":
      if (!INTEGER.test(text)) throw new TypeError(`${JSON.stringify(text)} is not an integer`);
      return BigInt(text);
    case "double": {
      const n = text.trim() === "" ? Number.NaN : Number(text);
      if (Number.isNaN(n) && text !== "NaN") throw new TypeError(`${JSON.stringify(text)} is not a number`);
      return n;
    }
    case "decimal":
      if (!DECIMAL.test(text) && text !== "NaN" && text !== "Infinity" && text !== "-Infinity") {
        throw new TypeError(`${JSON.stringify(text)} is not a decimal number`);
      }
      return new Decimal(text);
    case "boolean":
      if (text === "true") return true;
      if (text === "false") return false;
      throw new TypeError(`${JSON.stringify(text)} is not true or false`);
    case "date":
      return parseDate(text);
    case "time":
      return parseTime(text);
    case "timestamp":
      return parseTimestamp(text);
    case "timestamptz":
      if (!TIMESTAMPTZ.test(text)) throw new TypeError(`${JSON.stringify(text)} is not a timestamp with an offset ('YYYY-MM-DDTHH:MM:SSZ')`);
      return parseAbsolute(text.toUpperCase(), options.timeZone ?? getLocalTimeZone());
    case "bytes":
      return decodeBase64(text);
  }
}

/**
 * 读 'YYYY-MM-DD'，并校验是真实存在的日历日期（含闰年）：`CalendarDate` 的构造函数会把 2 月 30 日悄悄改成月末，所以先查。
 */
export function parseDate(text: string): CalendarDate {
  const m = DATE.exec(text);
  if (!m) throw new TypeError(`${JSON.stringify(text)} is not a date ('YYYY-MM-DD')`);
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  if (days === undefined || day < 1 || day > days) throw new RangeError(`${JSON.stringify(text)} is not a calendar date`);
  return new CalendarDate(year, month, day);
}

/** 读 'HH:MM[:SS[.fraction]]'；`Time` 精确到毫秒，更细的部分舍去。 */
export function parseTime(text: string): Time {
  const m = TIME.exec(text);
  if (!m) throw new TypeError(`${JSON.stringify(text)} is not a time ('HH:MM:SS')`);
  const [hour, minute, second] = [Number(m[1]), Number(m[2]), Number(m[3] ?? "0")];
  if (hour > 23 || minute > 59 || second > 59) throw new RangeError(`${JSON.stringify(text)} has an invalid time of day`);
  return new Time(hour, minute, second, Number((m[4] ?? "").padEnd(3, "0").slice(0, 3)));
}

/** 读 'YYYY-MM-DDTHH:MM[:SS[.fraction]]'（也接受空格分隔）；`CalendarDateTime` 精确到毫秒，更细的部分舍去。 */
export function parseTimestamp(text: string): CalendarDateTime {
  const m = TIMESTAMP.exec(text);
  if (!m) throw new TypeError(`${JSON.stringify(text)} is not a timestamp ('YYYY-MM-DDTHH:MM:SS')`);
  const day = parseDate(m[1]!);
  const time = parseTime(m[2]!);
  return new CalendarDateTime(day.year, day.month, day.day, time.hour, time.minute, time.second, time.millisecond);
}

const BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/** 标准 base64（带补齐的 =），与 Java 的 `Base64.getEncoder()` 一致。 */
export function encodeBase64(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const [a, b, c] = [bytes[i]!, bytes[i + 1], bytes[i + 2]];
    out += BASE64[a >> 2]! + BASE64[((a & 3) << 4) | ((b ?? 0) >> 4)]!;
    out += b === undefined ? "==" : BASE64[((b & 15) << 2) | ((c ?? 0) >> 6)]! + (c === undefined ? "=" : BASE64[c & 63]!);
  }
  return out;
}

/** 读标准 base64；长度不是 4 的倍数或含有别的字符时报错。 */
export function decodeBase64(text: string): Uint8Array {
  if (text.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(text)) throw new TypeError(`${JSON.stringify(text.slice(0, 40))} is not base64`);
  const pad = text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0;
  const out = new Uint8Array((text.length / 4) * 3 - pad);
  let at = 0;
  for (let i = 0; i < text.length; i += 4) {
    const n = [0, 1, 2, 3].reduce((acc, k) => (acc << 6) | Math.max(0, BASE64.indexOf(text[i + k]!)), 0);
    for (const byte of [n >> 16, (n >> 8) & 255, n & 255]) if (at < out.length) out[at++] = byte;
  }
  return out;
}

/** 报错时对值的简短描述。 */
export function describe(value: unknown): string {
  if (value === null) return "null";
  if (typeof value !== "object") return typeof value;
  return value.constructor?.name ?? "object";
}

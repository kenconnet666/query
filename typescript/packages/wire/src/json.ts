import { parseWire, specialText, wireTypeOf, type WireOptions, type WireType } from "./scalar.js";

/** JSON 能承载的值。 */
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

/**
 * `$types`：路径 → 种类。路径从根开始，用点连接属性名，数组不占一段（`rows.amount` 指每一行的 amount）；
 * 属性名里的 `~` 与 `.` 写成 `~0`、`~1`（同 JSON Pointer）。
 */
export type WireTypes = Record<string, WireType>;

/** 根对象上的类型表。 */
export const TYPES = "$types";

/** 根不是对象（数组、单个值）又有要还原的值时，包成 `{ "$value": 根, "$types": … }`。 */
export const VALUE = "$value";

/**
 * 把值写成线上的 JSON：bigint、Decimal、日期、时间、字节等 JSON 不能原样承载的值写成文字（见 {@link WireType}），
 * 根对象上加一张 `$types` 记下它们的位置，对方用 {@link fromWire} 还原。没有这样的值时结果就是普通 JSON。
 *
 *   toWire({ id: 9007199254740993n, amount: new Decimal("12.50"), rows: [{ on: new CalendarDate(2024, 1, 31) }] })
 *   // { id: "9007199254740993", amount: "12.5", rows: [{ on: "2024-01-31" }], $types: { id: "bigint", amount: "decimal", "rows.on": "date" } }
 *
 * 对象取自身可枚举的属性（值为 undefined 与函数的跳过），有 `toJSON` 的对象先调用它，Map 写成对象、Set 写成数组。
 * 同一路径上的值必须是同一种：一个路径上既有 Decimal 又有文字时报错，因为对方无从还原。循环引用报错。
 */
export function toWire(value: unknown): Json {
  if (isRecord(value) && (Object.hasOwn(value, TYPES) || Object.hasOwn(value, VALUE))) {
    throw new TypeError(`toWire: ${TYPES} and ${VALUE} are reserved on the root object`);
  }
  const found = new Map<string, WireType | "text">();
  const json = encode(value, "", found, new Set());
  const types: WireTypes = {};
  for (const [path, type] of found) if (type !== "text") types[path] = type;
  if (Object.keys(types).length === 0) return json;
  if (isRecord(json)) return { ...json, [TYPES]: types };
  const wrapped: WireTypes = {};
  for (const [path, type] of Object.entries(types)) wrapped[path === "" ? VALUE : `${VALUE}.${path}`] = type;
  return { [VALUE]: json, [TYPES]: wrapped };
}

/**
 * 读线上的 JSON：按根上的 `$types` 把文字还原成 bigint、Decimal、`CalendarDate` 等，去掉 `$types`
 * （`{ $value, $types }` 拆成里面的值）；没有 `$types` 时原样返回。就地改写传入的对象，通常它刚由 JSON.parse 得到。
 * 路径上的值是 null 或已经不是文字时跳过；文字读不出时报错并指出路径。
 */
export function fromWire<T = unknown>(json: unknown, options: WireOptions = {}): T {
  if (!isRecord(json) || !Object.hasOwn(json, TYPES)) return json as T;
  const types = json[TYPES];
  if (!isRecord(types)) throw new TypeError(`fromWire: ${TYPES} must be an object of path → type`);
  delete json[TYPES];
  for (const [path, type] of Object.entries(types)) {
    const steps = path.split(".").map(unescape);
    try {
      revive(json, steps[0]!, steps.slice(1), type as WireType, options);
    } catch (error) {
      throw new TypeError(`fromWire: ${path}: ${error instanceof Error ? error.message : String(error)}`, { cause: error });
    }
  }
  const keys = Object.keys(json);
  return (keys.length === 1 && keys[0] === VALUE ? json[VALUE] : json) as T;
}

/** 写下 value，path 是它的路径，found 收集各路径上值的种类。 */
function encode(value: unknown, path: string, found: Map<string, WireType | "text">, seen: Set<object>): Json {
  if (value === null || value === undefined) return null;
  switch (typeof value) {
    case "string":
      note(found, path, "text");
      return value;
    case "boolean":
      return value;
    case "number":
      if (Number.isFinite(value)) return Object.is(value, -0) ? 0 : value;
      break;
    case "function":
    case "symbol":
      return null;
  }
  const type = wireTypeOf(value);
  if (type !== undefined) {
    note(found, path, type);
    return specialText(type, value);
  }
  const object = value as object;
  if (seen.has(object)) throw new TypeError(`toWire: ${path || "the root"} refers back to itself`);
  seen.add(object);
  try {
    if (Array.isArray(object) || object instanceof Set) return [...object].map((item) => encode(item, path, found, seen));
    if (typeof (object as { toJSON?: unknown }).toJSON === "function") return encode((object as { toJSON(): unknown }).toJSON(), path, found, seen);
    const entries: [string, unknown][] = object instanceof Map ? [...object].map(([k, v]) => [String(k), v]) : Object.entries(object);
    const out: { [key: string]: Json } = {};
    for (const [key, item] of entries) {
      if (item === undefined || typeof item === "function") continue;
      out[key] = encode(item, path === "" ? escape(key) : `${path}.${escape(key)}`, found, seen);
    }
    return out;
  } finally {
    seen.delete(object);
  }
}

/** 记下 path 上的值的种类；同一路径出现两种时报错。 */
function note(found: Map<string, WireType | "text">, path: string, type: WireType | "text"): void {
  const known = found.get(path);
  if (known !== undefined && known !== type) {
    throw new TypeError(`toWire: values at ${path || "the root"} are both ${known} and ${type}; one path carries one kind of value`);
  }
  found.set(path, type);
}

/** 沿路径往下，把末端的文字换成值；数组不占一段，逐个元素往下。 */
function revive(holder: Record<string | number, unknown>, key: string | number, rest: readonly string[], type: WireType, options: WireOptions): void {
  const value = holder[key];
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) revive(value as unknown as Record<number, unknown>, i, rest, type, options);
    return;
  }
  if (rest.length === 0) {
    if (typeof value === "string") holder[key] = parseWire(type, value, options);
    return;
  }
  if (isRecord(value)) revive(value, rest[0]!, rest.slice(1), type, options);
}

/** 属性名写进路径：`~` → `~0`，`.` → `~1`。 */
function escape(key: string): string {
  return key.replaceAll("~", "~0").replaceAll(".", "~1");
}

/** {@link escape} 的反向。 */
function unescape(step: string): string {
  return step.replaceAll("~1", ".").replaceAll("~0", "~");
}

/** 是普通的对象（不是数组、不是 null）。 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

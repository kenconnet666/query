import { parseConditionTree, type ConditionTree } from "../query/condition-tree.js";
import { InvalidRequestError } from "./errors.js";

/**
 * 一次表格查询的请求：页码、每页条数、排序、已知的总数和表头筛选，与 vue-tsx-pro 的 `tableParams` 写出的地址栏参数一一对应（0016）：
 *
 *   page=2 & pageSize=20 & sort=-amount,name & total=368 & filter.status=draft & filter.status=paid & search.supplier=启航
 *   & condition={"any":[{"field":"amount","op":"gte","value":10000},{"field":"urgent","op":"eq","value":true}]}
 *
 * 其余参数是查询栏的条件，交给条件对象（`where(filter)`，字段的文字按列的类型读）。交给 `db.table` 执行，结果经 `toWire` 发回：
 *
 *   export default defineEventHandler(async (event) => {
 *     const params = getQuery(event);
 *     return toWire(await db.table(orders().where(Object.assign(new OrderFilter(), params)), parseTableRequest(params)));
 *   });
 */
export interface TableRequest {
  /** 页码，从 1 开始 */
  readonly page: number;
  /** 每页条数 */
  readonly pageSize: number;
  /** 排序串（`Query.sortBy`）；没有时是查询自己的顺序 */
  readonly sort: string | undefined;
  /** 前端已知的总数（只翻页、换排序时沿用上一次的），有它时不再执行 COUNT */
  readonly total: number | undefined;
  /** 表头的多选筛选：结果列名 → 选中的值（`Query.filterBy`） */
  readonly filter: Readonly<Record<string, readonly string[]>>;
  /** 表头的关键字筛选：结果列名 → 关键字（`Query.searchBy`） */
  readonly search: Readonly<Record<string, string>>;
  /** 高级筛选的条件树（`Query.conditionBy`，0017）；没有时 undefined */
  readonly condition: ConditionTree | undefined;
}

/** 没有 pageSize 参数时的每页条数，同 vue-tsx-pro 的默认值。 */
export const DEFAULT_PAGE_SIZE = 10;

/** 每页条数的上限，同 Spring Data 的 `max-page-size`；超过时报错，而不是悄悄少给。 */
export const MAX_PAGE_SIZE = 2000;

/** 地址栏参数：`URLSearchParams` 这样的 [键, 值] 序列（重复的键各占一项），或 h3 的 `getQuery`、Express 的 `req.query` 那样的对象（重复的参数是数组）。 */
export type QueryParams = Iterable<readonly [string, string]> | Readonly<Record<string, string | readonly string[] | null | undefined>>;

/**
 * 读地址栏参数：page、pageSize、sort、total 取第一个值，`filter.列名` 取全部值，`search.列名` 取第一个值，
 * `condition` 是条件树的 JSON 文字；其余参数不管。
 * 写错的数字、超过 maxPageSize（默认 {@link MAX_PAGE_SIZE}）的条数、写错的条件树报 {@link InvalidRequestError}（`query.request-invalid`）。
 */
export function parseTableRequest(params: QueryParams, options: { readonly maxPageSize?: number } = {}): TableRequest {
  const entries = Symbol.iterator in params ? [...groupParams(params as Iterable<readonly [string, string]>)] : Object.entries(params);
  const all = new Map<string, readonly string[]>();
  for (const [key, value] of entries) {
    if (value == null) continue;
    all.set(key, typeof value === "string" ? [value] : (value as readonly string[]));
  }
  const first = (name: string): string | undefined => all.get(name)?.[0];
  const filter: Record<string, readonly string[]> = {};
  const search: Record<string, string> = {};
  for (const [key, values] of all) {
    if (key.startsWith("filter.")) {
      const kept = values.filter((v) => v.trim() !== "");
      if (kept.length > 0) filter[key.slice("filter.".length)] = kept;
    } else if (key.startsWith("search.") && values[0] !== undefined && values[0].trim() !== "") {
      search[key.slice("search.".length)] = values[0].trim();
    }
  }
  const max = options.maxPageSize ?? MAX_PAGE_SIZE;
  const page = integer(first("page"), "page", 1);
  const pageSize = integer(first("pageSize"), "pageSize", DEFAULT_PAGE_SIZE);
  if (page < 1) throw new InvalidRequestError(`page must be at least 1, got ${page}`);
  if (pageSize < 1) throw new InvalidRequestError(`pageSize must be at least 1, got ${pageSize}`);
  if (pageSize > max) throw new InvalidRequestError(`pageSize must be at most ${max}, got ${pageSize}`);
  const sort = first("sort")?.trim();
  const totalText = first("total");
  const total = totalText === undefined || totalText.trim() === "" ? undefined : integer(totalText, "total", 0);
  if (total !== undefined && total < 0) throw new InvalidRequestError(`total must not be negative, got ${total}`);
  const conditionText = first("condition")?.trim();
  const condition = conditionText ? conditionOf(conditionText) : undefined;
  return Object.freeze({ page, pageSize, sort: sort === "" ? undefined : sort, total, filter: Object.freeze(filter), search: Object.freeze(search), condition });
}

/** condition 参数读成条件树；写错时报请求读不懂。 */
function conditionOf(text: string): ConditionTree {
  try {
    return parseConditionTree(text);
  } catch (e) {
    throw new InvalidRequestError(e instanceof Error ? e.message : String(e), { cause: e });
  }
}

/** 整数参数；没有或空白时是 fallback，写错时报错并指出参数名。 */
function integer(text: string | undefined, name: string, fallback: number): number {
  if (text === undefined || text.trim() === "") return fallback;
  const n = /^[+-]?\d+$/.test(text.trim()) ? Number(text.trim()) : Number.NaN;
  if (!Number.isSafeInteger(n)) throw new InvalidRequestError(`${name} must be an integer, got '${text}'`);
  return n;
}

/** [键, 值] 序列里同名的参数合成数组。 */
function groupParams(params: Iterable<readonly [string, string]>): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const [key, value] of params) out.set(key, [...(out.get(key) ?? []), value]);
  return out;
}

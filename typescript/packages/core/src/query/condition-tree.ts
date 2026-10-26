import { and, collect, Cond, negated, or, withSkipEmpty } from "../sql/cond.js";
import type { ExprNode } from "../sql/expr.js";
import { range, textAs } from "./filter.js";

/**
 * 条件树：前端条件构造器（vue-tsx-pro 的 `ConditionBuilder`、FilterBar 的高级筛选）编辑的条件，JSON 原样传来（0017）：
 *
 *   { "all": [ { "field": "amount", "op": "gte", "value": 10000 },
 *              { "any": [ { "field": "category", "op": "in", "value": ["steel", "aluminum"] },
 *                         { "not": { "field": "urgent", "op": "eq", "value": false } } ] } ] }
 *
 * 交给 `Query.conditionBy`：字段是结果列名（白名单），值按列的类型读。与前端的 `matchesCondition` 求值一致：
 * 不等于、不在其中、不包含把空值算作满足；没填完整的比较（值为空、区间两端都空、空数组）和空的组不加条件。
 */
export type ConditionTree =
  | ConditionRule
  | { readonly all: readonly ConditionTree[] }
  | { readonly any: readonly ConditionTree[] }
  | { readonly not: ConditionTree };

/** 比较的方式，同 vue-tsx-pro 的 `ConditionOp` */
export type ConditionOp =
  | "eq"
  | "ne"
  | "gt"
  | "gte"
  | "lt"
  | "lte"
  | "between"
  | "in"
  | "notIn"
  | "contains"
  | "notContains"
  | "startsWith"
  | "exists"
  | "empty";

/** 比较一个字段 */
export interface ConditionRule {
  /** 结果列名 */
  readonly field: string;
  /** 比较的方式 */
  readonly op: ConditionOp;
  /** 比较的值：between 是 [下限, 上限]，in / notIn 是数组，exists / empty 没有 */
  readonly value?: unknown;
}

const OPS: ReadonlySet<string> = new Set<ConditionOp>([
  "eq",
  "ne",
  "gt",
  "gte",
  "lt",
  "lte",
  "between",
  "in",
  "notIn",
  "contains",
  "notContains",
  "startsWith",
  "exists",
  "empty",
]);

/** 只能用在文字列上的比较 */
const TEXT_OPS: ReadonlySet<string> = new Set(["contains", "notContains", "startsWith"]);

/** 嵌套的上限：防止恶意的深层 JSON 把栈压爆 */
const MAX_DEPTH = 32;

/**
 * 读条件树：JSON 文字（地址栏参数）或已经解析好的对象（请求体）。形状不对、比较方式不认识时报 TypeError，
 * 消息里带出错的位置（`condition.all[1].op`）；读表格请求时 `parseTableRequest` 换成 `query.request-invalid`。
 */
export function parseConditionTree(input: unknown): ConditionTree {
  const value = typeof input === "string" ? (JSON.parse(input) as unknown) : input;
  return readNode(value, "condition", 0);
}

function readNode(node: unknown, at: string, depth: number): ConditionTree {
  if (depth > MAX_DEPTH) throw new TypeError(`${at}: the condition is nested more than ${MAX_DEPTH} levels`);
  if (typeof node !== "object" || node === null || Array.isArray(node)) throw new TypeError(`${at}: expected an object`);
  const o = node as Record<string, unknown>;
  for (const key of ["all", "any"] as const) {
    if (key in o) {
      if (!Array.isArray(o[key])) throw new TypeError(`${at}.${key}: expected an array`);
      const list = (o[key] as unknown[]).map((child, i) => readNode(child, `${at}.${key}[${i}]`, depth + 1));
      return key === "all" ? { all: list } : { any: list };
    }
  }
  if ("not" in o) return { not: readNode(o.not, `${at}.not`, depth + 1) };
  if (typeof o.field !== "string" || o.field.trim() === "") throw new TypeError(`${at}.field: expected a column name`);
  if (typeof o.op !== "string" || !OPS.has(o.op)) throw new TypeError(`${at}.op: unknown operator ${JSON.stringify(o.op)}; use one of ${[...OPS].join(", ")}`);
  return "value" in o ? { field: o.field, op: o.op as ConditionOp, value: o.value } : { field: o.field, op: o.op as ConditionOp };
}

/** 条件树里用到的字段名（去重，按出现的先后） */
export function conditionFields(tree: ConditionTree): string[] {
  const names = new Set<string>();
  const visit = (node: ConditionTree): void => {
    if ("all" in node) node.all.forEach(visit);
    else if ("any" in node) node.any.forEach(visit);
    else if ("not" in node) visit(node.not);
    else names.add(node.field);
  };
  visit(tree);
  return [...names];
}

const blank = (value: unknown) => value == null || (typeof value === "string" && value.trim() === "");

/** 值写成文字再按列的类型读：数字、布尔经文字转成列的类型（bigint、小数列），文字原样按列读 */
function valueOf(column: ExprNode<never>, value: unknown): never {
  const text = typeof value === "number" || typeof value === "boolean" ? String(value) : value;
  return textAs(column, text) as never;
}

/** 列（有值）或空值：`NOT` 一类的比较把空值也算作满足，同前端 */
const orNull = (column: ExprNode<never>, c: Cond) => (c.isEmpty ? c : or(c, collect(() => column.isNull())));

/**
 * 条件树写成条件。column 按字段名给出结果列的表达式（`Query.conditionBy` 传的是白名单里的列）。
 * 文字比较的大小写按数据库的排序规则（MySQL 默认不分；PostgreSQL 区分）
 */
export function conditionTreeCond(tree: ConditionTree, column: (field: string) => ExprNode<never>): Cond {
  if ("all" in tree) return and(...tree.all.map((child) => conditionTreeCond(child, column)));
  if ("any" in tree) return or(...tree.any.map((child) => conditionTreeCond(child, column)));
  if ("not" in tree) return negated(conditionTreeCond(tree.not, column));
  const c = column(tree.field);
  const { op, value } = tree;
  const at = `condition '${tree.field}'`;
  if (TEXT_OPS.has(op) && c.type.kind !== "text") throw new TypeError(`${at}: ${op} needs a text column`);
  const isText = c.type.kind === "text";
  switch (op) {
    case "exists":
      return collect(() => {
        c.isNotNull();
        if (isText) c.ne("" as never);
      });
    case "empty":
      return isText ? or(collect(() => c.isNull()), collect(() => c.eq("" as never))) : collect(() => c.isNull());
    case "between": {
      if (!Array.isArray(value) || value.length !== 2) {
        if (value == null) return Cond.EMPTY;
        throw new TypeError(`${at}: between needs two values [low, high]`);
      }
      const ends = value.map((end) => (blank(end) ? null : typeof end === "number" ? String(end) : end));
      // 空的一端交给 skipEmpty 省略（同条件对象的区间）
      return withSkipEmpty(true, () => collect(() => range(c, ends, at)));
    }
    case "in":
    case "notIn": {
      if (value == null) return Cond.EMPTY;
      if (!Array.isArray(value)) throw new TypeError(`${at}: ${op} needs an array`);
      const values = value.filter((item) => !blank(item)).map((item) => valueOf(c, item));
      if (values.length === 0) return Cond.EMPTY;
      return op === "in" ? collect(() => c.in(values)) : orNull(c, collect(() => c.notIn(values)));
    }
  }
  if (blank(value)) return Cond.EMPTY;
  if (Array.isArray(value) || typeof value === "object") throw new TypeError(`${at}: ${op} needs a single value`);
  const text = String(value as string | number | boolean);
  const v = valueOf(c, value);
  switch (op) {
    case "eq":
      return collect(() => c.eq(v));
    case "ne":
      return orNull(c, collect(() => c.ne(v)));
    case "gt":
      return collect(() => c.gt(v));
    case "gte":
      return collect(() => c.ge(v));
    case "lt":
      return collect(() => c.lt(v));
    case "lte":
      return collect(() => c.le(v));
    case "contains":
      return collect(() => c.contains(text as never));
    case "notContains":
      return orNull(c, negated(collect(() => c.contains(text as never))));
    default:
      return collect(() => c.startsWith(text as never));
  }
}

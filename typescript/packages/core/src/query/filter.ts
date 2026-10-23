import { entityMeta, type Ctor, type WhereOp } from "../schema/metadata.js";
import { all, any, Cond, Expr, withSkipEmpty } from "../sql/expr.js";
import { contains, endsWith, startsWith } from "../sql/functions.js";

/**
 * 条件对象：普通对象或类的实例，不能是函数（这样 `where` 回调写错时仍然报类型错误，而不是被当成条件对象）。
 */
export type FilterObject = object & { readonly call?: never; readonly apply?: never };

/**
 * `where(filter)` 的实现：条件对象的每个字段变成一个条件，默认与同名列比较相等（数组用 IN），`@where` 指定比较方式和列，
 * `@ignore` 的字段跳过。列在构造时就按名字找好，所以字段名写错时无论有没有值都会报错；
 * 值为 null、undefined、空白字符串或空数组的字段不产生条件。refs 是作用域里各数据源的引用。
 */
export function filterCondition(refs: readonly object[], filter: FilterObject): Cond {
  if (filter instanceof Cond) throw new TypeError("where(...) takes a callback or a filter object; write where((t) => condition)");
  const type = Object.getPrototypeOf(filter)?.constructor as Ctor<unknown> | undefined;
  const meta = type !== undefined && type !== Object ? entityMeta(type) : undefined;
  const owner = meta === undefined ? "filter" : type!.name;
  const conds: Cond[] = [];
  for (const name of new Set([...Object.keys(filter), ...(meta?.fields ?? [])])) {
    if (meta?.ignored(name)) continue;
    const declared = meta?.where(name);
    const op = declared?.op ?? "eq";
    const paths = declared !== undefined && declared.columns.length > 0 ? declared.columns : [name];
    const value = (filter as Record<string, unknown>)[name];
    const field = `${owner}.${name}`;
    conds.push(any(...paths.map((path) => withSkipEmpty(true, () => compare(resolve(refs, path, field), op, value, field)))));
  }
  return all(...conds);
}

/**
 * 沿 path 找列，例如 "mara.partNumber"：第一段在作用域的数据源里找（恰好一个有），之后每段在上一段的结果（关系的目标）上找。
 */
function resolve(refs: readonly object[], path: string, field: string): Expr<unknown> {
  const [first, ...rest] = path.split(".");
  const hits = refs.filter((r) => first! in r);
  if (hits.length > 1) throw new Error(`${field} matches ${first} in ${hits.length} tables; write the table's relation path`);
  let value: unknown = hits.length === 1 ? (hits[0] as Record<string, unknown>)[first!] : undefined;
  for (const step of rest) value = value !== null && typeof value === "object" && step in value ? (value as Record<string, unknown>)[step] : undefined;
  if (value === undefined) throw new Error(`${field}: the query's tables have no ${path}`);
  if (!(value instanceof Expr)) throw new Error(`${field}: ${path} is a relation, not a column`);
  return value as Expr<unknown>;
}

/** 一个字段的条件；值为空时由 skipEmpty 规则省略。 */
function compare(column: Expr<unknown>, op: WhereOp, value: unknown, field: string): Cond {
  const c = column as Expr<never>;
  if (Array.isArray(value)) {
    if (op === "eq") return c.in(value as never[]);
    if (op === "ne") return c.notIn(value as never[]);
    throw new TypeError(`${field}: an array can only be compared with eq (IN) or ne (NOT IN)`);
  }
  const v = value as never;
  const text = (value == null ? value : String(value)) as string | null | undefined;
  switch (op) {
    case "eq":
      return c.eq(v);
    case "ne":
      return c.ne(v);
    case "gt":
      return c.gt(v);
    case "ge":
      return c.ge(v);
    case "lt":
      return c.lt(v);
    case "le":
      return c.le(v);
    case "contains":
      return contains(c, text);
    case "startsWith":
      return startsWith(c, text);
    case "endsWith":
      return endsWith(c, text);
  }
}

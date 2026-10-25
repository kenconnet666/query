import { CalendarDate, CalendarDateTime } from "@internationalized/date";
import { parseDate } from "@zerodep-dev/wire";
import { entityMeta, type Ctor, type WhereOp } from "../schema/metadata.js";
import { parseColumnText, type SqlType } from "../schema/types.js";
import { addCondition, and, collect, Cond, or, skips, withSkipEmpty } from "../sql/cond.js";
import { ExprNode } from "../sql/expr.js";
import { Many } from "./many.js";

/**
 * 条件对象：普通对象或类的实例，不能是函数（这样 `where` 回调写错时仍然报类型错误，而不是被当成条件对象）。
 */
export type FilterObject = object & { readonly call?: never; readonly apply?: never };

/**
 * `where(filter)` 的实现：条件对象的每个字段变成一个条件，默认与同名列比较相等（数组用 IN），`@where` 指定比较方式和列，
 * `@ignore` 的字段跳过。列在构造时就按名字找好，所以字段名写错时无论有没有值都会报错；
 * 值为 null、undefined、空白字符串或空数组的字段不产生条件。refs 是作用域里各数据源的引用。
 * 路径经过一对多关系时写成 EXISTS；只写一条路径的字段按第一个一对多关系合并进同一个 EXISTS，
 * 即要求同一个子行满足这些字段，例如 `@where("eq", "deliveries.unit")` 与 `@where("ge", "deliveries.quantity")`。
 */
export function filterCondition(refs: readonly object[], filter: FilterObject): Cond {
  const type = Object.getPrototypeOf(filter)?.constructor as Ctor<unknown> | undefined;
  const meta = type !== undefined && type !== Object ? entityMeta(type) : undefined;
  const owner = meta === undefined ? "filter" : type!.name;
  const conds: Cond[] = [];
  const groups = new Map<string, Group>();
  for (const name of new Set([...Object.keys(filter), ...(meta?.fields ?? [])])) {
    if (meta?.ignored(name)) continue;
    const declared = meta?.where(name);
    const op = declared?.op ?? "eq";
    const paths = declared !== undefined && declared.columns.length > 0 ? declared.columns : [name];
    const value = (filter as Record<string, unknown>)[name];
    const field = `${owner}.${name}`;
    const alternatives: Cond[] = [];
    for (const path of paths) {
      const step: Step = { steps: path.split("."), path, op, value, field };
      const start = find(refs, step);
      const group = paths.length === 1 ? groupOf(start, step, groups) : undefined;
      if (group === undefined) alternatives.push(along(start, 1, step));
      else group.steps.push(step);
    }
    conds.push(or(...alternatives));
  }
  for (const g of groups.values()) conds.push(exists(g.many, (t) => and(...g.steps.map((s) => along(t, g.rest, s)))));
  return and(...conds);
}

/** 一个字段沿一条路径的比较：steps 是拆开的路径，其余是字段的比较方式、值和报错用的名字。 */
interface Step {
  readonly steps: readonly string[];
  readonly path: string;
  readonly op: WhereOp;
  readonly value: unknown;
  readonly field: string;
}

/** 走过同一个一对多关系的字段，合并进一个 EXISTS；rest 是关系之后的第一段。 */
interface Group {
  readonly many: Many<unknown>;
  readonly rest: number;
  readonly steps: Step[];
}

/** 路径的第一段：在作用域的数据源里找，恰好一个有。 */
function find(refs: readonly object[], step: Step): unknown {
  const first = step.steps[0]!;
  const hits = refs.filter((r) => first in r);
  if (hits.length > 1) throw new Error(`${step.field} matches ${first} in ${hits.length} tables; write the table's relation path`);
  if (hits.length === 0) throw new Error(`${step.field}: the query's tables have no ${step.path}`);
  return (hits[0] as Record<string, unknown>)[first];
}

/** at 上名为 name 的列或关系；没有时为 undefined。 */
function member(at: unknown, name: string): unknown {
  return at !== null && typeof at === "object" && name in at ? (at as Record<string, unknown>)[name] : undefined;
}

/** 路径在到达列之前经过一对多关系时，返回（必要时新建）这个关系的分组；不经过时为 undefined。 */
function groupOf(start: unknown, step: Step, groups: Map<string, Group>): Group | undefined {
  let at = start;
  for (let i = 1; i < step.steps.length; i++) {
    if (at instanceof Many) {
      const key = step.steps.slice(0, i).join(".");
      let group = groups.get(key);
      if (group === undefined) groups.set(key, (group = { many: at as Many<unknown>, rest: i, steps: [] }));
      return group;
    }
    at = member(at, step.steps[i]!);
    if (at === undefined) return undefined;
  }
  return undefined;
}

/** 从 at 出发沿 step 的第 i 段往下找列并比较；遇到一对多关系写成 EXISTS，找不到或停在关系上时报错。 */
function along(at: unknown, i: number, step: Step): Cond {
  for (; i < step.steps.length; i++) {
    if (at instanceof Many) {
      const rest = i;
      return exists(at as Many<unknown>, (t) => along(t, rest, step));
    }
    at = member(at, step.steps[i]!);
    if (at === undefined) throw new Error(`${step.field}: the query's tables have no ${step.path}`);
  }
  if (!(at instanceof ExprNode)) throw new Error(`${step.field}: ${step.path} is a relation, not a column`);
  const column = at as ExprNode<never>;
  return withSkipEmpty(true, () => collect(() => compare(column, step.op, step.value, step.field)));
}

/** `EXISTS (目标行满足 inner)`；inner 为空条件（值都为空）时整个条件省略，而不是变成"至少有一行"。 */
function exists(many: Many<unknown>, inner: (target: unknown) => Cond): Cond {
  let built = Cond.EMPTY;
  const c = collect(() =>
    many.any((t) => {
      built = inner(t);
      addCondition(built);
    }),
  );
  return built.isEmpty ? Cond.EMPTY : c;
}

/**
 * 区间字段的值：`{ start, end }`（vue-tsx-pro 的 DateRangePicker 给的就是它）或两项的数组 `[start, end]`；
 * 两端都含，空的一端不加条件。
 */
export interface Range<T> {
  readonly start?: T | null;
  readonly end?: T | null;
}

/** 一个字段的条件，写进当前的条件块；值为空时由 skipEmpty 规则省略，文字按列的类型读。 */
function compare(c: ExprNode<never>, op: WhereOp, value: unknown, field: string): void {
  if (typeof op === "function") {
    if (!skips(value)) op(c, value);
    return;
  }
  if (op === "range") return range(c, value, field);
  if (Array.isArray(value)) {
    const values = value.map((item) => textAs(c, item)).filter((item) => item != null) as never[];
    if (op === "eq") c.in(values);
    else if (op === "ne") c.notIn(values);
    else throw new TypeError(`${field}: an array can only be compared with eq (IN), ne (NOT IN) or range`);
    return;
  }
  const text = (value == null ? value : String(value)) as string | null | undefined;
  const v = textAs(c, value) as never;
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
      return c.contains(text);
    case "startsWith":
      return c.startsWith(text);
    case "endsWith":
      return c.endsWith(text);
  }
}

/** 文字按列的类型读（地址栏里的值都是文字）；别的值原样返回。 */
function textAs(c: ExprNode<never>, value: unknown): unknown {
  return typeof value === "string" ? parseColumnText(c.type as SqlType<unknown>, value) : value;
}

/**
 * 区间：起端写 `>=`，止端写 `<=`。列是时间戳而某一端是日期（`CalendarDate` 或 'YYYY-MM-DD'）时，起端是那天的零点，
 * 止端写成 `<` 后一天的零点，这一整天都算在内。两端都空时不加条件。
 */
function range(c: ExprNode<never>, value: unknown, field: string): void {
  if (value == null) return;
  const [start, end] = ends(value, field).map((bound) => (typeof bound === "string" && bound.trim() === "" ? null : bound));
  const timestamp = c.type.kind === "timestamp";
  const day = (bound: unknown) => (timestamp ? asDay(bound) : undefined);
  const from = day(start);
  c.ge((from === undefined ? textAs(c, start) : new CalendarDateTime(from.year, from.month, from.day)) as never);
  const until = day(end);
  if (until === undefined) c.le(textAs(c, end) as never);
  else c.lt(new CalendarDateTime(until.year, until.month, until.day).add({ days: 1 }) as never);
}

/** 区间值的两端。 */
function ends(value: unknown, field: string): [unknown, unknown] {
  if (Array.isArray(value)) {
    if (value.length === 0) return [null, null];
    if (value.length === 2) return [value[0], value[1]];
  } else if (typeof value === "object" && value !== null && ("start" in value || "end" in value)) {
    return [(value as Range<unknown>).start, (value as Range<unknown>).end];
  }
  throw new TypeError(`${field}: a range needs { start, end } or two values [start, end]`);
}

/** 区间的一端是一天（日期值或 'YYYY-MM-DD'）时返回那天，否则 undefined。 */
function asDay(bound: unknown): CalendarDate | undefined {
  if (bound instanceof CalendarDate && !(bound instanceof CalendarDateTime)) return bound;
  return typeof bound === "string" && /^\d{4}-\d{2}-\d{2}$/.test(bound.trim()) ? parseDate(bound.trim()) : undefined;
}

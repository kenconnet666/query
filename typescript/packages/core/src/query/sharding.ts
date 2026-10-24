import { all, Between, Column, Comparison, Conjunction, Disjunction, InList, Literal, Param, type Cond } from "../sql/expr.js";
import type { SqlWriter } from "../sql/writer.js";
import { columnName, entityMeta, type Ctor, type PropertyMeta, type Table } from "../schema/metadata.js";
import type { RefOwner } from "./source.js";

/**
 * 同库物理分表的规则，与 Java 的 `ShardRule` 对应：分表键（字段名）、全部物理表，以及一个键值或一段范围落在哪些表。
 * 用数据库选项 `shards: [shard(Event, monthly("day", "2025-01", "2026-12"))]` 注册。
 */
export interface ShardRule {
  /** 分表键：表类的字段名。 */
  readonly key: string;
  /** 逻辑表 table 的全部物理表，按顺序。 */
  tables(table: string): readonly string[];
  /** 键值 value 所在的物理表。 */
  route(table: string, value: unknown): string;
  /** 可能含有 low 到 high 之间键值的物理表；bound 为 undefined 表示不限。没有时用全部表。结果可以多，不能少。 */
  range?(table: string, low: unknown, lowInclusive: boolean, high: unknown, highInclusive: boolean): readonly string[];
}

/** 一张分表的注册：表类和它的规则。 */
export interface ShardConfig {
  readonly table: Table<unknown>;
  readonly rule: ShardRule;
}

/** 注册一张分表，写在数据库选项的 `shards` 里。 */
export function shard<T>(table: Table<T>, rule: ShardRule): ShardConfig {
  return Object.freeze({ table: table as Table<unknown>, rule });
}

const MONTH = /^(\d{4})-(\d{2})/;

/** 'YYYY-MM' 或日期、时间戳文本所在的月份，写成可以比较的 YYYYMM 数。 */
function monthOf(value: unknown, key: string): number {
  const m = typeof value === "string" ? MONTH.exec(value) : null;
  if (m === null) throw new TypeError(`monthly shards need a 'YYYY-MM-DD' date or timestamp for ${key}, got ${String(value)}`);
  return Number(m[1]) * 100 + Number(m[2]);
}

/** 下一个月。 */
function nextMonth(m: number): number {
  return m % 100 === 12 ? (Math.floor(m / 100) + 1) * 100 + 1 : m + 1;
}

/** 上一个月。 */
function previousMonth(m: number): number {
  return m % 100 === 1 ? (Math.floor(m / 100) - 1) * 100 + 12 : m - 1;
}

/** 是不是某月一日的零点：`< '2026-02-01'` 不含二月。 */
function startOfMonth(value: unknown): boolean {
  return typeof value === "string" && /^\d{4}-\d{2}-01(?:[ T]00:00(?::00(?:\.0+)?)?)?$/.test(value);
}

/**
 * 按月分表：物理表是 `表名_yyyyMM`，从 first 到 last（含，写成 'YYYY-MM'）每月一张；键是日期或时间戳文本，
 * 等值、IN 和范围条件都能选出对应的月份。
 */
export function monthly(key: string, first: string, last: string): ShardRule {
  const from = monthOf(first, key);
  const to = monthOf(last, key);
  if (to < from) throw new RangeError(`monthly shards end before they start: ${first} .. ${last}`);
  const between = (table: string, a: number, b: number): string[] => {
    const out: string[] = [];
    for (let m = a; m <= b; m = nextMonth(m)) out.push(`${table}_${m}`);
    return out;
  };
  return Object.freeze({
    key,
    tables: (table: string) => between(table, from, to),
    route(table: string, value: unknown): string {
      const m = monthOf(value, key);
      if (m < from || m > to) throw new RangeError(`${key} = ${String(value)} is outside the shards ${first} .. ${last}`);
      return `${table}_${m}`;
    },
    range(table: string, low: unknown, _lowInclusive: boolean, high: unknown, highInclusive: boolean): readonly string[] {
      const a = low === undefined ? from : Math.max(from, monthOf(low, key));
      const b = high === undefined ? to : Math.min(to, highInclusive || !startOfMonth(high) ? monthOf(high, key) : previousMonth(monthOf(high, key)));
      return b < a ? [] : between(table, a, b);
    },
  });
}

/** 按取模分表：物理表是 `表名_0` 到 `表名_(count-1)`，整数键 k 在 `表名_(k mod count)`；只有等值和 IN 能选表。 */
export function modulo(key: string, count: number): ShardRule {
  if (!Number.isInteger(count) || count < 1) throw new RangeError(`modulo shards need a positive count, got ${count}`);
  return Object.freeze({
    key,
    tables: (table: string) => Array.from({ length: count }, (_, i) => `${table}_${i}`),
    route(table: string, value: unknown): string {
      if (typeof value === "bigint") return `${table}_${((value % BigInt(count)) + BigInt(count)) % BigInt(count)}`;
      if (typeof value !== "number" || !Number.isInteger(value)) throw new TypeError(`${key} must be an integer to pick a modulo shard, got ${String(value)}`);
      return `${table}_${((value % count) + count) % count}`;
    },
  });
}

/** 自定义：物理表是 tables（写全名），router 把键值映射到其中一张；只有等值和 IN 能选表。 */
export function customShards(key: string, tables: readonly string[], router: (value: unknown) => string): ShardRule {
  const all = Object.freeze([...tables]);
  if (all.length === 0) throw new RangeError("custom shards need at least one table");
  return Object.freeze({
    key,
    tables: () => all,
    route(_table: string, value: unknown): string {
      const target = router(value);
      if (!all.includes(target)) throw new Error(`${key} = ${String(value)} routes to ${target}, which is not one of ${all.join(", ")}`);
      return target;
    },
  });
}

/** 一条语句看得到哪些分表：`allShards()` 读写全部，`onShards(...)` 只读写列出的；都没有时按条件选表。 */
export interface ShardChoice {
  readonly allShards: boolean;
  readonly onShards: readonly string[];
}

/** 是否显式选了分表。 */
export function choosesShards(c: ShardChoice): boolean {
  return c.allShards || c.onShards.length > 0;
}

/** 写入器状态的键：外层 SELECT 显式的分表选择，子查询没有自己的选择时沿用。 */
const INHERITED = Symbol("query.shards.inherited");

/**
 * 数据库实例上注册的同库分表，以及按条件选表：分表键与常量的 `=`、`IN`、`BETWEEN` 和范围比较，AND 取交、OR 取并；
 * 推不出来时报错，除非语句写了 `allShards()` 或 `onShards(...)`。与 Java 的 `Sharding` 对应。
 */
export class Sharding {
  /** 没有注册分表。 */
  static readonly NONE = new Sharding([]);
  readonly #rules = new Map<Ctor<unknown>, ShardRule>();

  /** 分表键必须是表的列。 */
  constructor(configs: readonly ShardConfig[]) {
    for (const c of configs) {
      const meta = entityMeta(c.table);
      if (meta.table === undefined) throw new Error(`${c.table.name} has no @table, so it cannot be sharded`);
      if (meta.property(c.rule.key) === undefined) throw new Error(`Shard key '${c.rule.key}' is not a column of ${c.table.name}`);
      this.#rules.set(c.table, c.rule);
    }
  }

  /** 写入器所属数据库实例的分表设置。 */
  static of(w: SqlWriter): Sharding {
    return (w.settings.sharding as Sharding | undefined) ?? Sharding.NONE;
  }

  /** table 的规则；不分表时为 undefined。 */
  rule(table: Ctor<unknown> | undefined): ShardRule | undefined {
    return table === undefined ? undefined : this.#rules.get(table);
  }

  /** table 的全部物理表；不分表时为空。 */
  tables(table: Ctor<unknown>): readonly string[] {
    const rule = this.#rules.get(table);
    return rule === undefined ? [] : rule.tables(entityMeta(table).table!.name);
  }

  /** 一段 SELECT 开始时调用：记下它显式的分表选择供子查询沿用，返回之前的选择，结束时交给 {@link leave}。 */
  static enter(w: SqlWriter, choice: ShardChoice): unknown {
    const previous = w.state(INHERITED);
    if (choosesShards(choice)) w.setState(INHERITED, choice);
    return previous;
  }

  /** 恢复 {@link enter} 之前的选择。 */
  static leave(w: SqlWriter, previous: unknown): void {
    w.setState(INHERITED, previous);
  }

  /** 为一段 SELECT 里的来源选表并登记到写入器上；一段 SELECT 里最多一张分表。 */
  routeSelect(w: SqlWriter, owners: readonly RefOwner[], where: readonly Cond[], choice: ShardChoice): void {
    if (this.#rules.size === 0) return;
    let sharded: RefOwner | undefined;
    for (const o of owners) {
      if (this.rule(o.source.entity) === undefined) continue;
      if (sharded !== undefined) {
        throw new Error(`Only one sharded table may take part in a SELECT; ${sharded.source.entity!.name} and ${o.source.entity!.name} both are. Read one of them in a CTE or a separate query`);
      }
      sharded = o;
    }
    if (sharded === undefined) return;
    const inherited = w.state(INHERITED) as ShardChoice | undefined;
    w.setState(sharded, this.route(sharded, where, choosesShards(choice) ? choice : (inherited ?? choice)));
  }

  /** owner 这次出现要读写的物理表，按规则里的顺序；不分表时为 undefined，推不出来时报错。 */
  route(owner: RefOwner, where: readonly Cond[], choice: ShardChoice): readonly string[] | undefined {
    const entity = owner.source.entity;
    const rule = this.rule(entity);
    if (rule === undefined) return undefined;
    const table = entityMeta(entity!).table!.name;
    const tables = rule.tables(table);
    if (choice.onShards.length > 0) {
      for (const t of choice.onShards) if (!tables.includes(t)) throw new Error(`onShards(...) names ${t}, which is not a shard of ${table}: ${tables.join(", ")}`);
      return tables.filter((t) => choice.onShards.includes(t));
    }
    if (choice.allShards) return tables;
    const routed = analyze(all(...where), owner, rule, table);
    if (routed === undefined) {
      throw new Error(`Cannot tell which shards of ${table} the statement needs: filter on ${entity!.name}.${rule.key} with =, IN, BETWEEN or a range (combined with AND or OR), or add allShards() or onShards(...)`);
    }
    return tables.filter((t) => routed.has(t));
  }

  /** 一行写到的物理表：行里分表键的值所在的表。 */
  routeValue(table: Ctor<unknown>, value: unknown): string {
    const rule = this.#rules.get(table)!;
    if (value == null) throw new Error(`A row of ${table.name} has no ${rule.key}; it is the shard key`);
    return rule.route(entityMeta(table).table!.name, value);
  }
}

/** 条件能选出的物理表；undefined 表示推不出来。AND 取交（推不出来的项不参与），OR 取并（有一项推不出来就推不出来）。 */
function analyze(c: Cond, owner: RefOwner, rule: ShardRule, table: string): Set<string> | undefined {
  if (c instanceof Conjunction) {
    let acc: Set<string> | undefined;
    for (const part of c.parts) {
      const s = analyze(part, owner, rule, table);
      if (s === undefined) continue;
      acc = acc === undefined ? new Set(s) : new Set([...acc].filter((t) => s.has(t)));
    }
    return acc;
  }
  if (c instanceof Disjunction) {
    const acc = new Set<string>();
    for (const part of c.parts) {
      const s = analyze(part, owner, rule, table);
      if (s === undefined) return undefined;
      for (const t of s) acc.add(t);
    }
    return acc;
  }
  if (c instanceof Comparison) {
    if (isKey(c.left, owner, rule) && constant(c.right)) return compare(c.operator, value(c.right), rule, table);
    if (isKey(c.right, owner, rule) && constant(c.left)) return compare(flip(c.operator), value(c.left), rule, table);
    return undefined;
  }
  if (c instanceof InList && !c.negate && isKey(c.e, owner, rule)) {
    const acc = new Set<string>();
    for (const v of c.values) {
      if (!constant(v) || value(v) == null) return undefined;
      acc.add(rule.route(table, value(v)));
    }
    return acc;
  }
  if (c instanceof Between && isKey(c.e, owner, rule) && constant(c.low) && constant(c.high) && value(c.low) != null && value(c.high) != null) {
    return new Set(range(rule, table, value(c.low), true, value(c.high), true));
  }
  return undefined;
}

/** 规则的范围选表；没有 range 时是全部表。 */
function range(rule: ShardRule, table: string, low: unknown, lowInclusive: boolean, high: unknown, highInclusive: boolean): readonly string[] {
  return rule.range === undefined ? rule.tables(table) : rule.range(table, low, lowInclusive, high, highInclusive);
}

/** `key op value` 能选出的表。 */
function compare(operator: string, v: unknown, rule: ShardRule, table: string): Set<string> | undefined {
  if (v == null) return undefined;
  switch (operator) {
    case "=":
      return new Set([rule.route(table, v)]);
    case ">":
      return new Set(range(rule, table, v, false, undefined, false));
    case ">=":
      return new Set(range(rule, table, v, true, undefined, false));
    case "<":
      return new Set(range(rule, table, undefined, false, v, false));
    case "<=":
      return new Set(range(rule, table, undefined, false, v, true));
    default:
      return undefined;
  }
}

/** 两侧对调后的运算符：`5 < k` 即 `k > 5`。 */
function flip(operator: string): string {
  return ({ ">": "<", ">=": "<=", "<": ">", "<=": ">=" } as Record<string, string>)[operator] ?? operator;
}

/** e 是不是 owner 这次出现的分表键列。 */
function isKey(e: unknown, owner: RefOwner, rule: ShardRule): boolean {
  return e instanceof Column && e.owner === owner && e.column.name === rule.key;
}

/** e 是不是参数或字面量。 */
function constant(e: unknown): e is Param<unknown> | Literal<unknown> {
  return e instanceof Param || e instanceof Literal;
}

/** 参数或字面量的值。 */
function value(e: unknown): unknown {
  return (e as Param<unknown> | Literal<unknown>).value;
}

/**
 * 写出 owner 的物理表：只有一张时直接写它，多张时写成 `(SELECT 列 FROM 表1 UNION ALL SELECT 列 FROM 表2 ...)`，
 * 一张都没有时写成取不到行的 `(SELECT 列 FROM 第一张表 WHERE 1 = 0)`。列按字段名排序，与 Java 相同。
 */
export function renderShards(w: SqlWriter, owner: RefOwner, tables: readonly string[]): void {
  const meta = entityMeta(owner.source.entity!);
  const table = meta.table!;
  if (tables.length === 1) {
    w.table(table.schema, tables[0]!);
    return;
  }
  const columns = [...meta.properties]
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
    .map((p: PropertyMeta) => w.dialect.quote(columnName(p, w.naming)))
    .join(", ");
  const from = tables.length === 0 ? [Sharding.of(w).tables(owner.source.entity!)[0]!] : tables;
  w.sql("(");
  from.forEach((t, i) => {
    if (i > 0) w.sql(" UNION ALL ");
    w.sql(`SELECT ${columns} FROM `).table(table.schema, t);
  });
  if (tables.length === 0) w.sql(" WHERE 1 = 0");
  w.sql(")");
}

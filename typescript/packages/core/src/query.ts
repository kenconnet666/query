import type { Clause, Dialect } from "./dialect.js";
import { all, Cond, Expr, Literal, toOrder, type Order, type OrderItem, type SourceColumn } from "./expr.js";
import { COLUMN_TYPES, COLUMNS, NAMED, SUBQUERY } from "./internal.js";
import { entityMeta, shapeFields, type Ctor, type Table } from "./metadata.js";
import { createRef, Cte, Derived, ownerOf, tableSource, type NullableRef, type Ref, type Source } from "./source.js";
import { double, integer, typeOf, type SqlType } from "./types.js";
import { SqlWriter, type Rendered } from "./writer.js";

declare const rowType: unique symbol;

// ---------- global filters ----------

/** A database-wide condition for every table whose entity is `type` or a subclass of it. */
export interface SourceFilter {
  readonly type: Ctor<unknown>;
  readonly condition: (ref: Ref<never>) => Cond;
}

/** Added to WHERE for the FROM table and to ON for joined tables; `.unfiltered()` skips them. */
export function filter<E>(type: Ctor<E>, condition: (ref: Ref<E>) => Cond): SourceFilter {
  return Object.freeze({ type, condition: condition as (ref: Ref<never>) => Cond });
}

function conditionsFor(filters: readonly SourceFilter[], ref: object): Cond[] {
  const entity = ownerOf(ref).source.entity;
  if (filters.length === 0 || entity === undefined || !ownerOf(ref).source.filtered) return [];
  return filters.filter((f) => entity === f.type || entity.prototype instanceof f.type).map((f) => f.condition(ref as Ref<never>));
}

// ---------- one SELECT without its projection ----------

type JoinKind = "FROM" | "JOIN" | "LEFT JOIN" | "CROSS JOIN";

interface Join {
  readonly kind: JoinKind;
  readonly ref: object;
  readonly on: Cond;
}

interface SelectSpec {
  readonly joins: readonly Join[];
  readonly where: readonly Cond[];
  readonly groupBy: readonly Expr<unknown>[];
  /** GROUP BY ROLLUP over all keys: subtotals per prefix of the keys and a grand total. */
  readonly rollup: boolean;
  readonly having: readonly Cond[];
  readonly orderBy: readonly Order[];
  readonly distinct: boolean;
  readonly limit: number | undefined;
  readonly offset: number | undefined;
  readonly filtered: boolean;
  readonly clauses: readonly Clause[];
}

function rows(n: number, what: string): number {
  if (!Number.isSafeInteger(n) || n < 0) throw new RangeError(`${what} must be a non-negative integer`);
  return n;
}

function nonNull(c: Cond): Cond {
  if (c == null) throw new TypeError("A condition is null or undefined; use Cond.EMPTY for no condition");
  return c;
}

function list<T>(value: T | readonly T[]): readonly T[] {
  return Array.isArray(value) ? (value as readonly T[]) : [value as T];
}

// ---------- projections ----------

/** An object projection: every leaf is an expression, nested objects give nested results. */
export type Projection = { readonly [key: string]: Expr<any> | Projection };

/** The result type of a projection. */
export type Shape<P> = { -readonly [K in keyof P]: P[K] extends Expr<infer V> ? V : Shape<P[K]> };

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

function flatten(p: object, path: readonly string[], items: Expr<unknown>[], paths: string[][]): void {
  for (const [key, value] of Object.entries(p)) {
    if (value instanceof Expr) {
      items.push(value as Expr<unknown>);
      paths.push([...path, key]);
    } else if (isPlainObject(value)) {
      flatten(value, [...path, key], items, paths);
    } else {
      throw new TypeError(`Projection field '${[...path, key].join(".")}' must be an expression or a nested object; use val(...) for constants`);
    }
  }
}

function assembler(paths: readonly (readonly string[])[]): (values: readonly unknown[]) => unknown {
  if (paths.every((p) => p.length === 1)) {
    const keys = paths.map((p) => p[0]!);
    return (values) => {
      const row: Record<string, unknown> = {};
      keys.forEach((k, i) => (row[k] = values[i]));
      return row;
    };
  }
  return (values) => {
    const root: Record<string, unknown> = {};
    paths.forEach((path, i) => {
      let node = root;
      for (const key of path.slice(0, -1)) node = (node[key] ??= {}) as Record<string, unknown>;
      node[path.at(-1)!] = values[i];
    });
    return root;
  };
}

// ---------- queries ----------

interface Arm {
  readonly spec: SelectSpec;
  readonly items: readonly Expr<unknown>[];
}

interface State {
  readonly arms: readonly Arm[];
  readonly operators: readonly string[];
  readonly labels: readonly string[];
  /** Labels are the keys of an object projection; such arms combine by name. */
  readonly keyed: boolean;
  /** A flat object projection: usable as a CTE or derived table. */
  readonly flat: boolean;
  readonly assemble: (values: readonly unknown[]) => unknown;
  readonly limit: number | undefined;
  readonly offset: number | undefined;
  readonly ttl: number | undefined;
}

let makeQuery: <R>(s: State) => Query<R>;
let stateOf: (q: Query<unknown>) => State;
let memoOf: (q: Query<unknown>) => WeakMap<object, Rendered>;

/**
 * A complete, immutable query producing rows of type R. Built by the `select...` methods of a
 * scope, combined with {@link unionAll}, and executed by a dialect's database instance.
 * Rendering is memoized per database instance: the same query object renders once.
 */
export class Query<R> {
  declare readonly [rowType]: R;
  readonly #s: State;
  readonly #memo = new WeakMap<object, Rendered>();

  private constructor(s: State) {
    this.#s = s;
  }

  static {
    makeQuery = <R>(s: State) => new Query<R>(s);
    stateOf = (q) => q.#s;
    memoOf = (q) => q.#memo;
  }

  /** Column labels in SELECT order; nested projections join their keys with dots. */
  get labels(): readonly string[] {
    return this.#s.labels;
  }

  /** Arms must produce the same row type; object projections are matched by key, not position. */
  unionAll(other: Query<R>): Query<R> {
    return this.#combine("UNION ALL", other);
  }

  union(other: Query<R>): Query<R> {
    return this.#combine("UNION", other);
  }

  #combine(operator: string, other: Query<R>): Query<R> {
    const a = this.#s;
    const b = other.#s;
    if (a.labels.length !== b.labels.length) throw new Error(`UNION arms select ${a.labels.length} and ${b.labels.length} columns`);
    if ([a, b].some((s) => s.limit !== undefined || s.offset !== undefined)) throw new Error("Apply limit/offset after combining queries, not before");
    if ([...a.arms, ...b.arms].some((arm) => hasOrderOrLimit(arm.spec))) {
      throw new Error("A UNION arm cannot have ORDER BY or LIMIT; wrap it in a CTE");
    }
    let arms = b.arms;
    if (a.keyed && b.keyed && a.labels.some((l, i) => l !== b.labels[i])) {
      const position = new Map(b.labels.map((l, i) => [l, i]));
      if (a.labels.some((l) => !position.has(l))) throw new Error(`UNION arms select different columns: ${a.labels} and ${b.labels}`);
      arms = b.arms.map((arm) => ({ spec: arm.spec, items: a.labels.map((l) => arm.items[position.get(l)!]!) }));
    }
    return makeQuery({ ...a, arms: [...a.arms, ...arms], operators: [...a.operators, operator, ...b.operators], ttl: a.ttl });
  }

  /** Same SQL, with a function applied to every row. */
  map<V>(fn: (row: R) => V): Query<V> {
    const inner = this.#s.assemble;
    return makeQuery({ ...this.#s, flat: false, keyed: false, assemble: (values) => fn(inner(values) as R) });
  }

  /**
   * Same SQL, every row copied into a new instance of the class by field name. The row type must
   * provide the class's fields; extra columns are ignored.
   */
  mapTo<C extends object>(type: new () => C, ..._check: [R] extends [Fields<C>] ? [] : [error: { "the row type does not provide the fields of": C }]): Query<C> {
    const inner = this.#s.assemble;
    let fields: readonly string[] | undefined;
    return makeQuery({
      ...this.#s,
      flat: false,
      keyed: false,
      assemble: (values) => {
        const row = inner(values) as Record<string, unknown>;
        const target = new type() as Record<string, unknown>;
        for (const f of (fields ??= shapeFields(type))) target[f] = row[f];
        return target;
      },
    });
  }

  /** Results are kept in the database instance's cache for ttl milliseconds, keyed by SQL and parameters. */
  cached(ttlMs: number): Query<R> {
    if (!(ttlMs > 0)) throw new RangeError("ttl must be positive");
    return makeQuery({ ...this.#s, ttl: ttlMs });
  }

  limit(n: number): Query<R> {
    if (this.#s.arms.length === 1) return this.#withSpec({ ...this.#s.arms[0]!.spec, limit: rows(n, "limit") });
    return makeQuery({ ...this.#s, limit: rows(n, "limit") });
  }

  offset(n: number): Query<R> {
    if (this.#s.arms.length === 1) return this.#withSpec({ ...this.#s.arms[0]!.spec, offset: rows(n, "offset") });
    return makeQuery({ ...this.#s, offset: rows(n, "offset") });
  }

  #withSpec(spec: SelectSpec): Query<R> {
    return makeQuery({ ...this.#s, arms: [{ spec, items: this.#s.arms[0]!.items }] });
  }

  /** As a derived table: `from(query.asTable())`. Needs a flat object projection. */
  asTable(): Source<R> {
    return new Derived(this);
  }

  /** Renders for one dialect, without any database instance's filters or overrides. Handy in tests. */
  toSql(dialect: Dialect): Rendered {
    return renderStatement(new SqlWriter(dialect), this);
  }

  toString(): string {
    return `Query(${this.#s.labels.join(", ")})`;
  }

  [SUBQUERY](w: SqlWriter): void {
    renderQuery(w, this.#s, true, false);
  }

  [NAMED](w: SqlWriter): void {
    renderQuery(w, this.#s, true, true);
  }

  get [COLUMN_TYPES](): readonly SqlType<unknown>[] {
    return this.#s.arms[0]!.items.map((e) => e.type as SqlType<unknown>);
  }

  [COLUMNS](use: string): readonly SourceColumn[] {
    if (!this.#s.flat) throw new Error(`Only a query with a flat object projection can be used as ${use}; its columns need names`);
    const types = this[COLUMN_TYPES];
    return this.#s.labels.map((name, i) => Object.freeze({ name, type: types[i]! }));
  }
}

type IfEquals<X, Y, A, B> = (<T>() => T extends X ? 1 : 2) extends <T>() => T extends Y ? 1 : 2 ? A : B;
type WritableKeys<C> = { [K in keyof C]-?: IfEquals<{ [Q in K]: C[K] }, { -readonly [Q in K]: C[K] }, K, never> }[keyof C];

/** The fields of a class that `mapTo` fills: not methods, and not getters (they look readonly). */
export type Fields<C> = { [K in WritableKeys<C> as C[K] extends (...args: never[]) => unknown ? never : K]: C[K] };

function hasOrderOrLimit(s: SelectSpec): boolean {
  return s.orderBy.length > 0 || s.limit !== undefined || s.offset !== undefined;
}

// ---------- package internals used by the database ----------

export function renderStatement(w: SqlWriter, q: Query<unknown>): Rendered {
  renderQuery(w, stateOf(q), true, true);
  return w.statement();
}

/** SELECT COUNT(*) over the query; ORDER BY is dropped unless a LIMIT needs it. */
export function renderCount(w: SqlWriter, q: Query<unknown>): Rendered {
  w.sql("SELECT COUNT(*) FROM (");
  renderQuery(w, stateOf(q), false, true);
  w.sql(") ").alias(w.aliasFor({}, "counted"));
  return w.statement();
}

/** The rendering of a query on one database instance, computed once. */
export function rendered(q: Query<unknown>, key: object, render: () => Rendered): Rendered {
  const memo = memoOf(q);
  let r = memo.get(key);
  if (r === undefined) {
    r = render();
    memo.set(key, r);
  }
  return r;
}

/** Decodes driver rows: the column types, then the projection's assembly. */
export function decoderOf<R>(q: Query<R>): { readonly types: readonly SqlType<unknown>[]; readonly assemble: (values: readonly unknown[]) => R } {
  return { types: q[COLUMN_TYPES], assemble: stateOf(q).assemble as (values: readonly unknown[]) => R };
}

export function ttlOf(q: Query<unknown>): number | undefined {
  return stateOf(q).ttl;
}

/** Lowers the limit to at most n, keeping a smaller limit the query already has. */
export function limitAtMost<R>(q: Query<R>, n: number): Query<R> {
  const s = stateOf(q);
  const current = s.arms.length === 1 ? s.arms[0]!.spec.limit : s.limit;
  return current !== undefined && current <= n ? q : q.limit(n);
}

function renderQuery(w: SqlWriter, s: State, ordered: boolean, labeled: boolean): void {
  s.arms.forEach((arm, i) => {
    if (i > 0) w.sql(`\n${s.operators[i - 1]}\n`);
    renderArm(w, arm, i === 0 && labeled ? s.labels : undefined, ordered);
  });
  if (s.arms.length > 1) w.dialect.limit(w, s.limit, s.offset);
}

/** @param ordered false drops ORDER BY from arms that have no LIMIT or OFFSET */
function renderArm(w: SqlWriter, arm: Arm, labels: readonly string[] | undefined, ordered: boolean): void {
  const s = arm.spec;
  // Aliases in join order, so the FROM table gets the plain name even if SELECT mentions a joined one first.
  for (const j of s.joins) ownerOf(j.ref).alias(w);
  const dialect: Dialect = w.dialect;
  for (const c of s.clauses) {
    if (c.dialect !== undefined && !(dialect instanceof c.dialect)) throw new Error(`A ${c.dialect.name} clause cannot run on ${w.dialect.name}`);
  }
  w.sql("SELECT ");
  for (const c of s.clauses) if (c.position === "hint") w.render(c).sql(" ");
  const distinctClauses = s.clauses.filter((c) => c.position === "distinct");
  for (const c of distinctClauses) w.render(c).sql(" ");
  if (s.distinct && distinctClauses.length === 0) w.sql("DISTINCT ");
  // Constants in the SELECT list are literals: HANA cannot type a parameter there (COALESCE(x, ?) in a
  // CTE is not numeric to an outer SUM). GROUP BY, DISTINCT and their ORDER BY match expressions
  // textually, so their constants must be literals too.
  const grouped = s.groupBy.length > 0 || s.distinct || distinctClauses.length > 0;
  const outer = w.inline(true);
  arm.items.forEach((item, i) => {
    if (i > 0) w.sql(", ");
    w.render(item);
    if (labels !== undefined) w.sql(" AS ").identifier(labels[i]!);
  });
  w.inline(false);
  if (s.joins.length === 0) w.dialect.fromNothing(w);
  const filters = s.filtered ? ((w.settings.filters as readonly SourceFilter[] | undefined) ?? []) : [];
  const where: Cond[] = [];
  for (const j of s.joins) {
    const extra = conditionsFor(filters, j.ref);
    const owner = ownerOf(j.ref);
    if (j.kind === "FROM" || j.kind === "CROSS JOIN") {
      w.sql(`\n${j.kind} `);
      owner.renderSource(w);
      where.push(...extra);
    } else {
      w.sql(`\n${j.kind} `);
      owner.renderSource(w);
      // Filters of a joined table belong to ON, so a LEFT JOIN stays a LEFT JOIN.
      w.sql(" ON ").render(all(j.on, ...extra));
    }
  }
  const whereAll = all(...where, ...s.where);
  if (!whereAll.isEmpty) w.sql("\nWHERE ").render(whereAll);
  w.inline(grouped);
  if (s.groupBy.length > 0) {
    w.sql("\nGROUP BY ");
    w.dialect.groupBy(w, s.groupBy, s.rollup);
  }
  const having = all(...s.having);
  if (!having.isEmpty) w.sql("\nHAVING ").render(having);
  const limited = s.limit !== undefined || s.offset !== undefined;
  if (s.orderBy.length > 0 && (ordered || limited)) w.sql("\nORDER BY ").join(s.orderBy, ", ");
  w.inline(outer);
  w.dialect.limit(w, s.limit, s.offset);
  for (const c of s.clauses) if (c.position === "end") w.sql("\n").render(c);
}

// ---------- scopes ----------

type Target<E> = Table<E> | Source<E>;

function refTo<E>(target: Target<E>): Ref<E> {
  return createRef(typeof target === "function" ? tableSource(target) : target);
}

let makeFrom: <T extends readonly unknown[]>(spec: SelectSpec, refs: T) => From<T>;

/**
 * A query being built. Every joined source adds a typed parameter to the callbacks, in join
 * order; after `leftJoin` that parameter's columns may be null. Immutable: every call returns a
 * new scope, so a scope can be shared and extended in different ways.
 */
export class From<T extends readonly unknown[]> {
  readonly #spec: SelectSpec;
  readonly #refs: T;

  private constructor(spec: SelectSpec, refs: T) {
    this.#spec = spec;
    this.#refs = refs;
  }

  static {
    makeFrom = <T extends readonly unknown[]>(spec: SelectSpec, refs: T) => new From<T>(spec, refs);
  }

  join<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => Cond): From<[...T, Ref<E>]> {
    return this.#join("JOIN", target, on);
  }

  leftJoin<E>(target: Target<E>, on: (...refs: [...T, Ref<E>]) => Cond): From<[...T, NullableRef<E>]> {
    return this.#join("LEFT JOIN", target, on);
  }

  crossJoin<E>(target: Target<E>): From<[...T, Ref<E>]> {
    return this.#join("CROSS JOIN", target, undefined);
  }

  #join<U extends readonly unknown[], E>(kind: JoinKind, target: Target<E>, on: ((...refs: [...T, Ref<E>]) => Cond) | undefined): From<U> {
    const ref = refTo(target);
    const refs = [...this.#refs, ref] as unknown as [...T, Ref<E>];
    const condition = on === undefined ? Cond.EMPTY : nonNull(on(...refs));
    if (kind !== "CROSS JOIN" && condition.isEmpty) throw new Error(`${kind} needs a condition; use crossJoin for a cartesian product`);
    return makeFrom({ ...this.#spec, joins: [...this.#spec.joins, { kind, ref, on: condition }] }, refs as unknown as U);
  }

  where(cond: (...refs: T) => Cond): From<T> {
    return this.#with({ where: [...this.#spec.where, nonNull(cond(...this.#refs))] });
  }

  /** The condition only when test holds. */
  whereIf(test: boolean, cond: (...refs: T) => Cond): From<T> {
    return test ? this.where(cond) : this;
  }

  groupBy(keys: (...refs: T) => Expr<unknown> | readonly Expr<unknown>[]): From<T> {
    if (this.#spec.rollup) throw new Error("groupByRollup already lists every grouping key");
    return this.#with({ groupBy: [...this.#spec.groupBy, ...list(keys(...this.#refs))] });
  }

  /** GROUP BY ROLLUP over all keys: subtotals for every prefix of the keys, then a grand total. */
  groupByRollup(keys: (...refs: T) => Expr<unknown> | readonly Expr<unknown>[]): From<T> {
    if (this.#spec.groupBy.length > 0) throw new Error("groupByRollup must list every grouping key; it cannot follow groupBy");
    const all = list(keys(...this.#refs));
    if (all.length === 0) throw new Error("groupByRollup needs at least one key");
    return this.#with({ groupBy: all, rollup: true });
  }

  having(cond: (...refs: T) => Cond): From<T> {
    return this.#with({ having: [...this.#spec.having, nonNull(cond(...this.#refs))] });
  }

  /** An expression sorts ascending; use `.desc()`, `.nullsLast()` and friends for more. */
  orderBy(items: (...refs: T) => OrderItem | readonly OrderItem[]): From<T> {
    return this.#with({ orderBy: [...this.#spec.orderBy, ...list(items(...this.#refs)).map(toOrder)] });
  }

  distinct(): From<T> {
    return this.#with({ distinct: true });
  }

  limit(n: number): From<T> {
    return this.#with({ limit: rows(n, "limit") });
  }

  offset(n: number): From<T> {
    return this.#with({ offset: rows(n, "offset") });
  }

  /** Skips the database instance's global filters for this SELECT. */
  unfiltered(): From<T> {
    return this.#with({ filtered: false });
  }

  /** Adds a dialect clause, e.g. `hint("NO_CS_JOIN")` from the HANA package. */
  with(clause: Clause | ((...refs: T) => Clause)): From<T> {
    const c = typeof clause === "function" ? clause(...this.#refs) : clause;
    return this.#with({ clauses: [...this.#spec.clauses, c] });
  }

  /** Applies a reusable fragment: a plain function from scope to scope (or to a query). */
  pipe<R>(fragment: (scope: this) => R): R {
    return fragment(this);
  }

  /**
   * An object projection: keys are column labels, nested objects give nested results. A flat one
   * can serve as a CTE or derived table.
   */
  select<P extends Projection>(projection: (...refs: T) => P): Query<Shape<P>> {
    const items: Expr<unknown>[] = [];
    const paths: string[][] = [];
    flatten(projection(...this.#refs), [], items, paths);
    if (items.length === 0) throw new Error("The projection selects no column");
    return makeQuery({
      arms: [{ spec: this.#spec, items }],
      operators: [],
      labels: paths.map((p) => p.join(".")),
      keyed: true,
      flat: paths.every((p) => p.length === 1),
      assemble: assembler(paths),
      limit: undefined,
      offset: undefined,
      ttl: undefined,
    });
  }

  /** One column: a list of values, a scalar subquery or an IN subquery. */
  selectValue<V>(value: (...refs: T) => Expr<V>): Query<V> {
    const item = value(...this.#refs);
    return this.#single(item as Expr<unknown>, item.label ?? "value");
  }

  /** SELECT 1, for EXISTS subqueries. */
  selectOne(): Query<number> {
    return this.#single(new Literal(integer, 1) as Expr<unknown>, "value");
  }

  /**
   * Every field of the class taken from the same-named column of exactly one source, or from the
   * source its `@mapFrom` names; rows are instances of the class.
   */
  selectAs<C extends object>(type: new () => C): Query<C> {
    const fields = shapeFields(type);
    const items = fields.map((f) => this.#autoColumn(type, f).as(f));
    return makeQuery({
      arms: [{ spec: this.#spec, items }],
      operators: [],
      labels: fields,
      keyed: true,
      flat: true,
      assemble: (values) => {
        const target = new type() as Record<string, unknown>;
        fields.forEach((f, i) => (target[f] = values[i]));
        return target;
      },
      limit: undefined,
      offset: undefined,
      ttl: undefined,
    });
  }

  #autoColumn(type: Ctor<unknown>, field: string): Expr<unknown> {
    const refs = this.#spec.joins.map((j) => j.ref as Record<string, Expr<unknown>>);
    const from = entityMeta(type).mapFrom(field);
    if (from !== undefined) {
      const hits = refs.filter((r) => ownerOf(r).source.entity === from.entity);
      if (hits.length !== 1) {
        throw new Error(`${type.name}.${field}: @mapFrom(${from.entity.name}) needs exactly one ${from.entity.name} in the query, found ${hits.length}`);
      }
      const column = hits[0]![from.property];
      if (column === undefined) throw new Error(`${from.entity.name} has no column '${from.property}'`);
      return column;
    }
    const hits = refs.map((r) => r[field]).filter((c) => c !== undefined);
    if (hits.length === 1) return hits[0]!;
    throw new Error(
      `${type.name}.${field} ` +
        (hits.length === 0
          ? `has no same-named column in the joined sources; use select(...) with mapTo or @mapFrom`
          : `matches ${hits.length} sources; pick one with @mapFrom or select(...) with mapTo`),
    );
  }

  #single<V>(item: Expr<unknown>, label: string): Query<V> {
    return makeQuery({
      arms: [{ spec: this.#spec, items: [item] }],
      operators: [],
      labels: [label],
      keyed: false,
      flat: false,
      assemble: (values) => values[0],
      limit: undefined,
      offset: undefined,
      ttl: undefined,
    });
  }

  #with(change: Partial<SelectSpec>): From<T> {
    return makeFrom({ ...this.#spec, ...change }, this.#refs);
  }
}

function emptySpec(): SelectSpec {
  return {
    joins: [],
    where: [],
    groupBy: [],
    rollup: false,
    having: [],
    orderBy: [],
    distinct: false,
    limit: undefined,
    offset: undefined,
    filtered: true,
    clauses: [],
  };
}

/** Starts a query from a table entity, a CTE or a derived table. */
export function from<E>(target: Target<E>): From<[Ref<E>]> {
  const ref = refTo(target);
  return makeFrom({ ...emptySpec(), joins: [{ kind: "FROM", ref, on: Cond.EMPTY }] }, [ref] as [Ref<E>]);
}

/** A named CTE over a query with a flat object projection. */
export function cte<E>(name: string, body: Query<E>): Cte<E> {
  return new Cte(name, body);
}

/** A value of a constant row. */
export type ConstantValue = string | number | bigint | boolean;

/**
 * Constant rows such as a code list: one SELECT of literals per row, joined by UNION ALL (with
 * `FROM DUMMY` on HANA). Use it as a CTE or a derived table. Column types follow the values;
 * give `types` for dates, decimals or converted columns. Every row needs the same keys and values.
 */
export function values<R extends Record<string, ConstantValue>>(rows: readonly R[], types: { readonly [K in keyof R]?: SqlType<R[K]> } = {}): Query<R> {
  const first = rows[0];
  if (first === undefined) throw new Error("values() needs at least one row");
  const keys = Object.keys(first);
  rows.forEach((row, n) => {
    const extra = Object.keys(row).filter((k) => !keys.includes(k));
    if (extra.length > 0) throw new Error(`values() row ${n + 1} has keys the first row lacks: ${extra.join(", ")}`);
    const missing = keys.find((k) => row[k] == null);
    if (missing !== undefined) throw new Error(`values() row ${n + 1} has no value for '${missing}'; every column of constant rows needs one`);
  });
  const columnTypes = keys.map((k) => {
    const given = (types as Record<string, SqlType<unknown> | undefined>)[k];
    if (given !== undefined) return given;
    const numbers = rows.map((r) => r[k]).filter((v) => typeof v === "number");
    return numbers.length > 0 && numbers.some((v) => !Number.isInteger(v)) ? (double as SqlType<unknown>) : typeOf(first[k]);
  });
  const none = emptySpec();
  const arms = rows.map((row) => ({ spec: none, items: keys.map((k, i) => new Literal<unknown>(columnTypes[i] as SqlType<{}>, row[k])) }));
  return makeQuery({
    arms,
    operators: arms.slice(1).map(() => "UNION ALL"),
    labels: keys,
    keyed: true,
    flat: true,
    assemble: assembler(keys.map((k) => [k])),
    limit: undefined,
    offset: undefined,
    ttl: undefined,
  });
}

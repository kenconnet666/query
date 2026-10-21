import type { Dialect, FunctionDef, FunctionRenderer } from "./dialect.js";
import { shapeFields, type NamingStrategy } from "./metadata.js";
import { decoderOf, limitAtMost, renderCount, rendered, renderStatement, ttlOf, type Query, type SourceFilter } from "./query.js";
import { integer, untyped, type SqlType } from "./types.js";
import { SqlWriter, type Rendered, type RenderSettings } from "./writer.js";

// ---------- drivers ----------

/** Rows in column order, with the column labels the database reported. */
export interface DriverResult {
  readonly columns: readonly string[];
  readonly rows: readonly (readonly unknown[])[];
}

/**
 * What a dialect package's driver adapter provides. Parameters arrive encoded by their types;
 * drivers hand decimals, big integers, dates and timestamps back as strings.
 */
export interface Driver {
  query(sql: string, parameters: readonly unknown[]): Promise<DriverResult>;
  /** Rows one at a time from one cursor; databases without it read everything first. */
  stream?(sql: string, parameters: readonly unknown[]): AsyncIterable<readonly unknown[]>;
}

// ---------- listeners, caching, paging, errors ----------

export interface QueryEvent {
  readonly database: string;
  readonly statement: Rendered;
  readonly elapsedMs: number;
  /** Rows read, or delivered from the cache. */
  readonly rows: number;
  readonly error: unknown;
  readonly cached: boolean;
}

/** Observes executed statements: logging, timing, slow-query reports. Exceptions it throws are ignored. */
export type QueryListener = (event: QueryEvent) => void;

export interface CacheKey {
  readonly database: string;
  readonly sql: string;
  readonly parameters: readonly unknown[];
}

type Rows = readonly (readonly unknown[])[];

/**
 * Result cache for queries marked `.cached(ttl)`. It stores decoded rows, not mapped objects,
 * because a projection may build different objects on each call. Implement it to use Redis or
 * anything else.
 */
export interface QueryCache {
  get(key: CacheKey, ttlMs: number, load: () => Promise<Rows>): Promise<Rows>;
  clear(): void;
}

/** In-process cache with per-entry expiry and a size bound; concurrent loads of one key share a query. */
export function inMemoryCache(maxEntries = 1000): QueryCache {
  if (!Number.isInteger(maxEntries) || maxEntries < 1) throw new RangeError("maxEntries must be a positive integer");
  const entries = new Map<string, { rows: Promise<Rows>; expiresAt: number }>();
  const keyOf = (k: CacheKey) =>
    JSON.stringify([k.database, k.sql, k.parameters], (_, v: unknown) =>
      typeof v === "bigint" ? `${v}n` : v instanceof Uint8Array ? Array.from(v) : v,
    );
  return {
    get(key, ttlMs, load) {
      const now = Date.now();
      const k = keyOf(key);
      const hit = entries.get(k);
      if (hit !== undefined && hit.expiresAt > now) return hit.rows;
      for (const [old, e] of entries) if (e.expiresAt <= now) entries.delete(old);
      while (entries.size >= maxEntries) entries.delete(entries.keys().next().value!);
      const rows = load();
      entries.set(k, { rows, expiresAt: now + ttlMs });
      rows.catch(() => entries.delete(k));
      return rows;
    },
    clear() {
      entries.clear();
    },
  };
}

/** One page of rows and the total row count. Pages are numbered from 1. */
export interface Page<R> {
  readonly rows: readonly R[];
  readonly total: number;
  readonly page: number;
  readonly size: number;
  readonly totalPages: number;
}

export class QueryExecutionError extends Error {
  constructor(
    readonly database: string,
    readonly statement: Rendered,
    cause: unknown,
  ) {
    super(`${database}: ${cause instanceof Error ? cause.message : String(cause)}\n${statement.sql}`, { cause });
    this.name = "QueryExecutionError";
  }
}

// ---------- the database instance ----------

export interface DatabaseOptions {
  /** Conditions for every table of an entity type, e.g. the current client: `filter(ClientEntity, r => ...)`. */
  readonly filters?: readonly SourceFilter[];
  readonly cache?: QueryCache;
  readonly listener?: QueryListener;
  /** Column names for decorated fields without an explicit name. */
  readonly naming?: NamingStrategy;
  /** Renders functions differently on this database only. */
  readonly functions?: ReadonlyMap<FunctionDef, FunctionRenderer>;
  /** Shown in errors, listener events and cache keys; defaults to the dialect name and a number. */
  readonly name?: string;
}

let instances = 0;
let executeNative: (db: Database, statement: Rendered) => Promise<DriverResult>;

/**
 * One database of one dialect. Each dialect package subclasses it; there is no dialect-neutral
 * instance. Meant to be a long-lived singleton per connection pool.
 */
export abstract class Database {
  readonly name: string;
  readonly #driver: Driver;
  readonly #settings: RenderSettings;
  readonly #cache: QueryCache | undefined;
  readonly #listener: QueryListener | undefined;

  protected constructor(
    readonly dialect: Dialect,
    driver: Driver,
    options: DatabaseOptions = {},
  ) {
    this.#driver = driver;
    this.name = options.name ?? `${dialect.name}#${++instances}`;
    this.#settings = Object.freeze({
      filters: options.filters ?? [],
      ...(options.naming === undefined ? {} : { naming: options.naming }),
      ...(options.functions === undefined ? {} : { functions: options.functions }),
    });
    this.#cache = options.cache;
    this.#listener = options.listener;
  }

  // ---------- execution ----------

  async list<R>(q: Query<R>): Promise<R[]> {
    const { assemble } = decoderOf(q);
    return (await this.#rows(q, this.render(q))).map(assemble);
  }

  /** Exactly one row, otherwise an error. */
  async one<R>(q: Query<R>): Promise<R> {
    const rows = await this.list(limitAtMost(q, 2));
    if (rows.length !== 1) throw new Error(`Expected exactly one row, got ${rows.length === 0 ? "none" : "more than one"}`);
    return rows[0]!;
  }

  /** The first row, reading at most one. */
  async first<R>(q: Query<R>): Promise<R | undefined> {
    return (await this.list(limitAtMost(q, 1)))[0];
  }

  /** Rows read one at a time from one cursor where the driver supports it. */
  async *stream<R>(q: Query<R>): AsyncGenerator<R, void, undefined> {
    if (this.#driver.stream === undefined) {
      yield* await this.list(q);
      return;
    }
    const statement = this.render(q);
    const { types, assemble } = decoderOf(q);
    const start = Date.now();
    let count = 0;
    try {
      for await (const raw of this.#driver.stream(statement.sql, statement.parameters)) {
        count++;
        yield assemble(decode(q, types, raw));
      }
    } catch (e) {
      this.#notify(statement, start, count, e, false);
      throw e instanceof QueryExecutionError ? e : new QueryExecutionError(this.name, statement, e);
    }
    this.#notify(statement, start, count, undefined, false);
  }

  async forEach<R>(q: Query<R>, action: (row: R) => void | Promise<void>): Promise<void> {
    for await (const row of this.stream(q)) await action(row);
  }

  async count(q: Query<unknown>): Promise<number> {
    const statement = renderCount(this.#writer(), q);
    const result = await this.#execute(statement);
    return integer.decode(result.rows[0]?.[0]);
  }

  /** Page numbers start at 1; the total comes from a COUNT(*) over the same query. */
  async page<R>(q: Query<R>, page: number, size: number): Promise<Page<R>> {
    if (!Number.isInteger(page) || !Number.isInteger(size) || page < 1 || size < 1) throw new RangeError("page and size are integers from 1");
    const [rows, total] = await Promise.all([this.list(q.limit(size).offset((page - 1) * size)), this.count(q)]);
    return Object.freeze({ rows, total, page, size, totalPages: Math.ceil(total / size) });
  }

  /** Hand-written SQL with :named parameters, mapped by column label. Arrays expand for IN (:list). */
  sql(text: string): NativeQuery {
    return new NativeQuery(this, text);
  }

  /** The SQL this database runs for the query, with filters and overrides applied. */
  render(q: Query<unknown>): Rendered {
    return rendered(q, this, () => renderStatement(this.#writer(), q));
  }

  clearCache(): void {
    this.#cache?.clear();
  }

  // ---------- internals ----------

  #writer(): SqlWriter {
    return new SqlWriter(this.dialect, this.#settings);
  }

  async #rows(q: Query<unknown>, statement: Rendered): Promise<Rows> {
    const { types } = decoderOf(q);
    const fetch = async () => (await this.#execute(statement)).rows.map((raw) => decode(q, types, raw));
    const ttl = ttlOf(q);
    if (ttl === undefined) return fetch();
    if (this.#cache === undefined) throw new Error(`The query is .cached(...) but database ${this.name} has no cache; pass cache: inMemoryCache()`);
    let loaded = false;
    const start = Date.now();
    const rows = await this.#cache.get({ database: this.name, sql: statement.sql, parameters: statement.parameters }, ttl, () => {
      loaded = true;
      return fetch();
    });
    if (!loaded) this.#notify(statement, start, rows.length, undefined, true);
    return rows;
  }

  /** Runs a statement and reports it to the listener. */
  async #execute(statement: Rendered): Promise<DriverResult> {
    const start = Date.now();
    try {
      const result = await this.#driver.query(statement.sql, statement.parameters);
      this.#notify(statement, start, result.rows.length, undefined, false);
      return result;
    } catch (e) {
      this.#notify(statement, start, 0, e, false);
      throw new QueryExecutionError(this.name, statement, e);
    }
  }

  #notify(statement: Rendered, start: number, rows: number, error: unknown, cached: boolean): void {
    if (this.#listener === undefined) return;
    try {
      this.#listener({ database: this.name, statement, elapsedMs: Date.now() - start, rows, error, cached });
    } catch {
      // A failing listener must not fail the query.
    }
  }

  static {
    executeNative = (db, statement) => db.#execute(statement);
  }
}

function decode(q: Query<unknown>, types: readonly SqlType<unknown>[], raw: readonly unknown[]): unknown[] {
  return types.map((type, i) => {
    const value = raw[i];
    if (value == null) return null;
    try {
      return type.decode(value);
    } catch (e) {
      throw new TypeError(`Column '${q.labels[i]}': ${e instanceof Error ? e.message : String(e)}`, { cause: e });
    }
  });
}

// ---------- native SQL ----------

export type NativeValue = string | number | bigint | boolean | Uint8Array | null;

const normalized = (label: string) => label.replaceAll("_", "").toLowerCase();

/**
 * Hand-written SQL with `:name` parameters, mapped by column label. An array value expands to
 * `?, ?, ...` for `IN (:names)`. The escape hatch for statements the builder cannot express, and a
 * migration path for existing SQL text. Values are read as the driver returns them.
 */
export class NativeQuery {
  readonly #params = new Map<string, NativeValue | readonly NativeValue[]>();

  constructor(
    private readonly database: Database,
    readonly text: string,
  ) {}

  bind(name: string, value: NativeValue | readonly NativeValue[]): this {
    if (value === undefined) throw new TypeError(`:${name} is undefined; bind null for NULL`);
    this.#params.set(name, value);
    return this;
  }

  /** Rows as objects keyed by column label, or as instances of a class matched by label ignoring case and underscores. */
  async list(): Promise<Record<string, unknown>[]>;
  async list<C extends object>(type: new () => C): Promise<C[]>;
  async list(type?: new () => object): Promise<object[]> {
    const { columns, rows } = await executeNative(this.database, this.render());
    if (type === undefined) {
      return rows.map((row) => Object.fromEntries(columns.map((c, i) => [c, row[i]])));
    }
    const index = new Map(columns.map((c, i) => [normalized(c), i]));
    const fields = shapeFields(type).map((f) => {
      const i = index.get(normalized(f));
      if (i === undefined) throw new Error(`${type.name}.${f} has no column among ${columns.join(", ")}`);
      return [f, i] as const;
    });
    return rows.map((row) => {
      const target = new type() as Record<string, unknown>;
      for (const [f, i] of fields) target[f] = row[i];
      return target;
    });
  }

  /** The first column of every row. */
  async values<T = unknown>(): Promise<T[]> {
    const { rows } = await executeNative(this.database, this.render());
    return rows.map((row) => row[0] as T);
  }

  async first(): Promise<Record<string, unknown> | undefined> {
    return (await this.list())[0];
  }

  async one(): Promise<Record<string, unknown>> {
    const rows = await this.list();
    if (rows.length !== 1) throw new Error(`Expected exactly one row, got ${rows.length}`);
    return rows[0]!;
  }

  /** Replaces :name outside quotes, comments and PostgreSQL's :: casts. */
  render(): Rendered {
    const w = new SqlWriter(this.database.dialect);
    const s = this.text;
    let plain = 0;
    const flush = (to: number) => {
      if (to > plain) w.sql(s.slice(plain, to));
    };
    for (let i = 0; i < s.length; i++) {
      const c = s[i]!;
      if (c === "'" || c === '"' || c === "`") {
        let end = s.indexOf(c, i + 1);
        while (end >= 0 && s[end + 1] === c) end = s.indexOf(c, end + 2);
        i = end < 0 ? s.length - 1 : end;
      } else if (c === "-" && s[i + 1] === "-") {
        const end = s.indexOf("\n", i);
        i = end < 0 ? s.length - 1 : end;
      } else if (c === "/" && s[i + 1] === "*") {
        const end = s.indexOf("*/", i + 2);
        i = end < 0 ? s.length - 1 : end + 1;
      } else if (c === ":" && s[i + 1] === ":") {
        i++;
      } else if (c === ":" && /[A-Za-z_$]/.test(s[i + 1] ?? "")) {
        let end = i + 1;
        while (end < s.length && /[A-Za-z0-9_$]/.test(s[end]!)) end++;
        const name = s.slice(i + 1, end);
        if (!this.#params.has(name)) throw new Error(`Parameter :${name} is not bound`);
        flush(i);
        this.#expand(w, name, this.#params.get(name)!);
        plain = end;
        i = end - 1;
      }
    }
    flush(s.length);
    return w.statement();
  }

  #expand(w: SqlWriter, name: string, value: NativeValue | readonly NativeValue[]): void {
    if (!Array.isArray(value)) {
      w.bind(value, untyped);
      return;
    }
    if (value.length === 0) throw new Error(`:${name} is an empty array; IN () is invalid SQL`);
    (value as readonly NativeValue[]).forEach((v, i) => {
      if (v === null) throw new Error(`:${name} contains null`);
      if (i > 0) w.sql(", ");
      w.bind(v, untyped);
    });
  }
}

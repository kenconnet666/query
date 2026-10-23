import type { Dialect, FunctionDef, FunctionRenderer } from "./dialect.js";
import { shapeFields, type NamingStrategy } from "./metadata.js";
import { decoderOf, limitAtMost, renderCount, rendered, renderStatement, timeoutOf, ttlOf, type Query, type SourceFilter } from "./query.js";
import { integer, untyped, type SqlType } from "./types.js";
import { SqlWriter, type Rendered, type RenderSettings } from "./writer.js";

// ---------- 驱动 ----------

/** 按列顺序排列的行，以及数据库返回的列标签。 */
export interface DriverResult {
  readonly columns: readonly string[];
  readonly rows: readonly (readonly unknown[])[];
}

/**
 * 方言包的驱动适配器需要提供的能力。参数按各自类型编码后传入；
 * 驱动把 decimal、大整数、日期和时间戳以字符串形式返回。
 */
export interface Driver {
  /** 执行一条语句并一次性返回全部行。 */
  query(sql: string, parameters: readonly unknown[], options?: ExecuteOptions): Promise<DriverResult>;
  /** 用一个游标逐行读取；不支持游标的数据库先读完全部再返回。 */
  stream?(sql: string, parameters: readonly unknown[], options?: ExecuteOptions): AsyncIterable<readonly unknown[]>;
}

/** 驱动对单条语句应用的设置；驱动不支持的项直接忽略。 */
export interface ExecuteOptions {
  /** 超过该毫秒数后由数据库取消语句。 */
  readonly timeoutMs?: number;
}

// ---------- 监听、缓存、分页、错误 ----------

/** 一次语句执行的记录，交给 {@link QueryListener}。 */
export interface QueryEvent {
  readonly database: string;
  readonly statement: Rendered;
  readonly elapsedMs: number;
  /** 读取到的行数，或从缓存取得的行数。 */
  readonly rows: number;
  readonly error: unknown;
  readonly cached: boolean;
}

/** 观察已执行的语句：日志、计时、慢查询报告。监听器抛出的异常会被忽略。 */
export type QueryListener = (event: QueryEvent) => void;

/** 查询结果缓存的键：数据库名、SQL 文本和参数。 */
export interface CacheKey {
  readonly database: string;
  readonly sql: string;
  readonly parameters: readonly unknown[];
}

type Rows = readonly (readonly unknown[])[];

/**
 * 标记了 `.cached(ttl)` 的查询所用的结果缓存。缓存的是解码后的行而不是映射后的对象，
 * 因为投影每次调用可能构造不同的对象。实现它即可接入 Redis 等任何存储。
 */
export interface QueryCache {
  /** 命中且未过期时返回缓存的行，否则调用 `load` 加载并缓存。 */
  get(key: CacheKey, ttlMs: number, load: () => Promise<Rows>): Promise<Rows>;
  /** 清空全部缓存项。 */
  clear(): void;
}

/** 进程内缓存：每项独立过期并限制总条数；同一个键的并发加载共用一次查询。 */
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

/** 一页数据和总行数。页码从 1 开始。 */
export interface Page<R> {
  readonly rows: readonly R[];
  readonly total: number;
  readonly page: number;
  readonly size: number;
  readonly totalPages: number;
}

/** 语句执行失败时抛出的错误，附带数据库名和渲染后的语句；原始异常放在 `cause`。 */
export class QueryExecutionError extends Error {
  constructor(
    readonly database: string,
    readonly statement: Rendered,
    cause: unknown,
    /** 数据库是否因语句超时而取消了它。 */
    readonly timedOut = false,
  ) {
    super(`${database}${timedOut ? " (timed out)" : ""}: ${cause instanceof Error ? cause.message : String(cause)}\n${statement.sql}`, { cause });
    this.name = "QueryExecutionError";
  }
}

// ---------- 数据库实例 ----------

/** 创建 {@link Database} 时的可选项。 */
export interface DatabaseOptions {
  /** 作用于某实体类型所有表的条件，例如当前租户：`filter(ClientEntity, r => ...)`。 */
  readonly filters?: readonly SourceFilter[];
  readonly cache?: QueryCache;
  readonly listener?: QueryListener;
  /** 未显式指定列名的装饰字段所用的命名策略。 */
  readonly naming?: NamingStrategy;
  /** 仅在这个数据库上改写函数的渲染方式。 */
  readonly functions?: ReadonlyMap<FunctionDef, FunctionRenderer>;
  /** 出现在错误、监听事件和缓存键中；默认是方言名加序号。 */
  readonly name?: string;
  /**
   * 语句被取消的毫秒数；查询自己的 `.timeout(ms)` 优先。
   * HANA 和 PostgreSQL 由服务端取消，详见方言包的驱动说明。
   */
  readonly queryTimeoutMs?: number;
}

let instances = 0;
let executeNative: (db: Database, statement: Rendered, timeoutMs: number | undefined) => Promise<DriverResult>;

/**
 * 某个方言的一个数据库。各方言包各自继承它，不存在与方言无关的实例。
 * 每个连接池对应一个长生命周期的单例。
 */
export abstract class Database {
  readonly name: string;
  readonly #driver: Driver;
  readonly #settings: RenderSettings;
  readonly #cache: QueryCache | undefined;
  readonly #listener: QueryListener | undefined;
  readonly #timeout: number | undefined;

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
    if (options.queryTimeoutMs !== undefined && !(options.queryTimeoutMs > 0)) throw new RangeError("queryTimeoutMs must be positive");
    this.#timeout = options.queryTimeoutMs;
  }

  // ---------- 执行 ----------

  /** 执行查询，返回全部行映射后的对象。 */
  async list<R>(q: Query<R>): Promise<R[]> {
    const { assemble } = decoderOf(q);
    return (await this.#rows(q, this.render(q))).map(assemble);
  }

  /** 恰好一行，否则抛错。 */
  async one<R>(q: Query<R>): Promise<R> {
    const rows = await this.list(limitAtMost(q, 2));
    if (rows.length !== 1) throw new Error(`Expected exactly one row, got ${rows.length === 0 ? "none" : "more than one"}`);
    return rows[0]!;
  }

  /** 第一行，至多读取一行；没有结果时返回 `undefined`。 */
  async first<R>(q: Query<R>): Promise<R | undefined> {
    return (await this.list(limitAtMost(q, 1)))[0];
  }

  /** 驱动支持时，用一个游标逐行读取。 */
  async *stream<R>(q: Query<R>): AsyncGenerator<R, void, undefined> {
    if (this.#driver.stream === undefined) {
      yield* await this.list(q);
      return;
    }
    const statement = this.render(q);
    this.#checkParameterCount(statement);
    const { types, assemble } = decoderOf(q);
    const start = Date.now();
    let count = 0;
    try {
      for await (const raw of this.#driver.stream(statement.sql, statement.parameters, this.#options(timeoutOf(q)))) {
        count++;
        yield assemble(decode(q, types, raw));
      }
    } catch (e) {
      this.#notify(statement, start, count, e, false);
      throw e instanceof QueryExecutionError ? e : new QueryExecutionError(this.name, statement, e, this.dialect.isTimeout(e));
    }
    this.#notify(statement, start, count, undefined, false);
  }

  /** 逐行对结果调用 `action`，基于 {@link stream}，会等待每次回调完成。 */
  async forEach<R>(q: Query<R>, action: (row: R) => void | Promise<void>): Promise<void> {
    for await (const row of this.stream(q)) await action(row);
  }

  /** 查询的总行数，用 `COUNT(*)` 包住同一个查询得到。 */
  async count(q: Query<unknown>): Promise<number> {
    const statement = renderCount(this.#writer(), q);
    const result = await this.#execute(statement, timeoutOf(q));
    return integer.decode(result.rows[0]?.[0]);
  }

  /** 页码从 1 开始；总数来自对同一查询的 COUNT(*)。 */
  async page<R>(q: Query<R>, page: number, size: number): Promise<Page<R>> {
    if (!Number.isInteger(page) || !Number.isInteger(size) || page < 1 || size < 1) throw new RangeError("page and size are integers from 1");
    const [rows, total] = await Promise.all([this.list(q.limit(size).offset((page - 1) * size)), this.count(q)]);
    return Object.freeze({ rows, total, page, size, totalPages: Math.ceil(total / size) });
  }

  /** 手写 SQL，使用 :name 命名参数，按列标签映射。数组会为 IN (:list) 展开。 */
  sql(text: string): NativeQuery {
    return new NativeQuery(this, text);
  }

  /** 这个数据库实际执行的 SQL，已应用过滤条件和函数改写。 */
  render(q: Query<unknown>): Rendered {
    return rendered(q, this, () => renderStatement(this.#writer(), q));
  }

  /** 清空结果缓存；没有配置缓存时什么也不做。 */
  clearCache(): void {
    this.#cache?.clear();
  }

  // ---------- 内部实现 ----------

  /** 每次渲染新建一个写入器，因为写入器带有别名和参数的状态。 */
  #writer(): SqlWriter {
    return new SqlWriter(this.dialect, this.#settings);
  }

  /** 取得解码后的行：标记了 `.cached(...)` 的查询经由缓存，其余直接执行。 */
  async #rows(q: Query<unknown>, statement: Rendered): Promise<Rows> {
    const { types } = decoderOf(q);
    const fetch = async () => (await this.#execute(statement, timeoutOf(q))).rows.map((raw) => decode(q, types, raw));
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

  /** 执行语句并通知监听器；`timeoutMs` 为 undefined 时用实例的默认值。 */
  async #execute(statement: Rendered, timeoutMs: number | undefined): Promise<DriverResult> {
    this.#checkParameterCount(statement);
    const start = Date.now();
    try {
      const result = await this.#driver.query(statement.sql, statement.parameters, this.#options(timeoutMs));
      this.#notify(statement, start, result.rows.length, undefined, false);
      return result;
    } catch (e) {
      this.#notify(statement, start, 0, e, false);
      throw new QueryExecutionError(this.name, statement, e, this.dialect.isTimeout(e));
    }
  }

  /** 合并查询自带的超时和实例默认超时，得到传给驱动的选项。 */
  #options(timeoutMs: number | undefined): ExecuteOptions {
    const limit = timeoutMs ?? this.#timeout;
    return limit === undefined ? {} : { timeoutMs: limit };
  }

  /** 在联系数据库之前就失败，因为数据库自己对参数过多的报错往往难以理解。 */
  #checkParameterCount(statement: Rendered): void {
    const count = statement.parameters.length;
    const max = this.dialect.maxParameters;
    if (count > max) {
      throw new Error(
        `${this.name}: the statement binds ${count} parameters; ${this.dialect.name} accepts at most ${max}. ` +
          "Split the list into several queries, or select the values with a subquery instead of passing them",
      );
    }
  }

  /** 把一次执行报告给监听器。 */
  #notify(statement: Rendered, start: number, rows: number, error: unknown, cached: boolean): void {
    if (this.#listener === undefined) return;
    try {
      this.#listener({ database: this.name, statement, elapsedMs: Date.now() - start, rows, error, cached });
    } catch {
      // 监听器出错不能让查询失败。
    }
  }

  // 让同一模块里的 NativeQuery 能调用私有的 #execute。
  static {
    executeNative = (db, statement, timeoutMs) => db.#execute(statement, timeoutMs);
  }
}

/** 按列类型解码一行原始值；`null` 原样保留，解码失败时指出列标签。 */
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

// ---------- 原生 SQL ----------

/** 原生查询可绑定的参数值类型。 */
export type NativeValue = string | number | bigint | boolean | Uint8Array | null;

/** 列标签的比较形式：去掉下划线并转小写。 */
const normalized = (label: string) => label.replaceAll("_", "").toLowerCase();

/**
 * 手写 SQL，使用 `:name` 参数，按列标签映射。数组值会为 `IN (:names)` 展开成 `?, ?, ...`。
 * 用于构建器表达不了的语句，也是已有 SQL 文本的迁移通道。值按驱动返回的样子读取。
 */
export class NativeQuery {
  readonly #params = new Map<string, NativeValue | readonly NativeValue[]>();
  #timeout: number | undefined;

  constructor(
    private readonly database: Database,
    readonly text: string,
  ) {}

  /** 绑定 `:name` 参数；`undefined` 会被拒绝，要表示 NULL 请传 `null`。 */
  bind(name: string, value: NativeValue | readonly NativeValue[]): this {
    if (value === undefined) throw new TypeError(`:${name} is undefined; bind null for NULL`);
    this.#params.set(name, value);
    return this;
  }

  /** 语句运行超过该毫秒数时取消；优先于数据库实例的 queryTimeoutMs。 */
  timeout(ms: number): this {
    if (!(ms > 0)) throw new RangeError("timeout must be positive");
    this.#timeout = ms;
    return this;
  }

  /** 行以列标签为键的对象返回，或传入类时按忽略大小写和下划线的标签匹配成该类的实例。 */
  async list(): Promise<Record<string, unknown>[]>;
  async list<C extends object>(type: new () => C): Promise<C[]>;
  async list(type?: new () => object): Promise<object[]> {
    const { columns, rows } = await executeNative(this.database, this.render(), this.#timeout);
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

  /** 每一行的第一列。 */
  async values<T = unknown>(): Promise<T[]> {
    const { rows } = await executeNative(this.database, this.render(), this.#timeout);
    return rows.map((row) => row[0] as T);
  }

  /** 第一行；没有结果时返回 `undefined`。 */
  async first(): Promise<Record<string, unknown> | undefined> {
    return (await this.list())[0];
  }

  /** 恰好一行，否则抛错。 */
  async one(): Promise<Record<string, unknown>> {
    const rows = await this.list();
    if (rows.length !== 1) throw new Error(`Expected exactly one row, got ${rows.length}`);
    return rows[0]!;
  }

  /** 替换 :name，但跳过方言的引号部分、注释和 PostgreSQL 的 :: 类型转换。 */
  render(): Rendered {
    const dialect = this.database.dialect;
    const w = new SqlWriter(dialect);
    const s = this.text;
    let plain = 0;
    const flush = (to: number) => {
      if (to > plain) w.sql(s.slice(plain, to));
    };
    for (let i = 0; i < s.length; i++) {
      const c = s[i]!;
      const quoted = dialect.quotedEnd(s, i);
      if (quoted >= 0) {
        i = quoted;
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

  /** 写出一个参数：数组展开成逗号分隔的占位符，空数组和含 null 的数组都不合法。 */
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

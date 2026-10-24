import type { Dialect, FunctionDef, FunctionRenderer } from "../sql/dialect.js";
import { columnName, shapeFields, type NamingStrategy } from "../schema/metadata.js";
import { assembleRows, decoderOf, hasChildren, limitAtMost, renderCount, rendered, renderExists, renderStatement, timeoutOf, ttlOf, type Query, type SourceFilter } from "../query/query.js";
import { integer, typeOf, untyped, type SqlType } from "../schema/types.js";
import { SchemaTools } from "../ddl/tools.js";
import { Migrator, type Migration } from "../ddl/migrator.js";
import { Write, type Fill, type Insert } from "../write/write.js";
import { Rendered, SqlWriter, type RenderSettings } from "../sql/writer.js";
import { Tracker } from "./tracker.js";
import { Sharding, type ShardConfig } from "../query/sharding.js";

// ---------- 驱动 ----------

/** 按列顺序排列的行，以及数据库返回的列标签。 */
export interface DriverResult {
  readonly columns: readonly string[];
  readonly rows: readonly (readonly unknown[])[];
  /** 写语句影响的行数；查询不用填。 */
  readonly affected?: number;
  /** 插入一行后数据库生成的值（MySQL 的 AUTO_INCREMENT）；没有时不填。 */
  readonly insertId?: unknown;
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
  /**
   * 在一个事务里执行 work：给它一个绑定在同一个连接上的驱动，work 正常结束时提交，抛出异常时回滚。
   * 已经在事务里的驱动直接执行 work。不支持时 `db.transaction(...)` 报错。
   */
  transaction?<R>(work: (driver: Driver) => Promise<R>): Promise<R>;
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

/**
 * 把一条语句的执行结果格式化成 MyBatis 风格的几行，`printSql` 打印的就是它：
 * ```
 * ==> Preparing: SELECT ... WHERE LIPS.MATNR = ?
 * ==> Parameters: M1(text)
 * <== Total: 3, Time: 12 ms
 * ```
 * 命中缓存时最后一行标明 `(cached)`，失败时最后一行是错误信息。
 */
export function formatQueryEvent(event: QueryEvent): string {
  let text = `==> Preparing: ${event.statement.sql}`;
  if (event.statement.bindings.length > 0) text += `\n==> Parameters: ${event.statement.parameterText()}`;
  if (event.error !== undefined && event.error !== null) {
    text += `\n<== Error: ${event.error instanceof Error ? event.error.message : String(event.error)}`;
  } else {
    text += `\n<== Total: ${event.rows}, Time: ${event.elapsedMs} ms${event.cached ? " (cached)" : ""}`;
  }
  return text;
}

/**
 * 空跑驱动：不连接任何数据库，每条查询都得到空结果（计数为 0），但照常渲染、打印和触发监听器。
 * 配合 {@link Database.capture}，不用数据库也能测试代码发出的 SQL：`new HanaDatabase(dryRunDriver(), { printSql: true })`。
 */
export function dryRunDriver(): Driver {
  const driver: Driver = {
    query: async () => ({ columns: [], rows: [], affected: 0 }),
    stream: async function* () {},
    transaction: (work) => work(driver),
  };
  DRY_RUN.add(driver);
  return driver;
}

/** 空跑驱动的写语句不报告真实的行数，所以不检查期望的行数。 */
const DRY_RUN = new WeakSet<Driver>();

/**
 * 写语句影响的行数与期望的不符：`expect(n)` 写明的行数，或 `byId(row)` 期望的每行一行。
 * 带版本列的按主键更新出现它，通常是别人已经改过或删掉了这一行（乐观锁冲突）。
 */
export class UnexpectedRowCountError extends Error {
  constructor(
    readonly database: string,
    readonly statement: Rendered,
    readonly expected: number,
    readonly actual: number,
  ) {
    super(`${database}: expected ${expected} row(s) to change but ${actual} did; the rows may have been changed or deleted by someone else\n${statement.sql}`);
    this.name = "UnexpectedRowCountError";
  }
}

/** 要求恰好一行（`db.one(q)`）的查询没有读到行；应用可以在全局错误处理里把它单独映射成"未找到"。 */
export class EmptyResultError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EmptyResultError";
  }
}

/** 最多一行（`db.one(q)`、`db.optional(q)`）的查询读到了多行，通常说明条件写漏了。 */
export class TooManyRowsError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TooManyRowsError";
  }
}

/**
 * 语句执行前的钩子：查询、写语句、计数、原生 SQL 都经过它。返回要执行的语句，原样返回表示不改；抛出异常则语句不执行。
 * 用 `new Rendered(sql, s.bindings, s.dialect)` 改写 SQL 文本，例如加注释或提示；改写不要改变参数的个数和顺序。
 * 多个钩子按顺序依次调用；设置了 `cached(...)` 的查询按改写后的 SQL 作缓存键。
 */
export type StatementInterceptor = (statement: Rendered) => Rendered;

/** 一次 {@link Database.transaction}：该数据库在其中使用的驱动。 */
interface Transaction {
  readonly database: Database;
  readonly driver: Driver;
  readonly outer: Transaction | undefined;
}

let activeTransaction: AsyncStorage<Transaction> | undefined;

/** 一次 {@link Database.capture}：只收集该数据库的语句；outer 是外层的捕获。 */
interface Capture {
  readonly database: Database;
  readonly events: QueryEvent[];
  readonly outer: Capture | undefined;
}

/** Node 的 AsyncLocalStorage，只取用到的部分；核心包不依赖 Node 的类型声明。 */
interface AsyncStorage<T> {
  run<R>(store: T, fn: () => R): R;
  getStore(): T | undefined;
}

let activeCapture: AsyncStorage<Capture> | undefined;

/** printSql: true 时的输出：console.info；核心包不依赖 DOM 或 Node 的类型声明。 */
function printToConsole(text: string): void {
  (globalThis as { console?: { info(text: string): void } }).console?.info(text);
}

/** 惰性创建跨异步调用传递捕获状态的存储；不在 Node 上时 capture 不可用。 */
function captures(): AsyncStorage<Capture> {
  activeCapture ??= asyncStorage("capture()");
  return activeCapture;
}

/** 一个追踪范围：所属的数据库、它的 Tracker 和外层范围（另一个数据库的）。 */
interface TrackingScope {
  readonly database: Database;
  readonly tracker: Tracker;
  readonly outer: TrackingScope | undefined;
}

let activeTracking: AsyncStorage<TrackingScope> | undefined;

/** 惰性创建跨异步调用传递追踪范围的存储；不在 Node 上时 tracking 不可用。 */
function trackers(): AsyncStorage<TrackingScope> {
  activeTracking ??= asyncStorage("tracking()");
  return activeTracking;
}

/** 惰性创建跨异步调用传递事务的存储；不在 Node 上时 transaction 不可用。 */
function transactions(): AsyncStorage<Transaction> {
  activeTransaction ??= asyncStorage("transaction()");
  return activeTransaction;
}

/** 新建 Node 的 AsyncLocalStorage；what 是需要它的功能，用于报错。 */
function asyncStorage<T>(what: string): AsyncStorage<T> {
  const hooks = (globalThis as { process?: { getBuiltinModule?(id: string): unknown } }).process?.getBuiltinModule?.("node:async_hooks") as
    | { AsyncLocalStorage: new () => AsyncStorage<T> }
    | undefined;
  if (hooks === undefined) throw new Error(`${what} needs Node's AsyncLocalStorage (Node 22.3 or later)`);
  return new hooks.AsyncLocalStorage();
}

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
    /** 命中且未过期时返回缓存的行，否则调用 load 并保存结果。 */
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
    /** 清空全部缓存。 */
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
  /** 写入时自动填充的列（审计字段）：`[onInsert(Audited, (a) => a.createdAt, now), onUpdate(Audited, (a) => a.updatedBy, user)]`。 */
  readonly fills?: readonly Fill[];
  /** 执行前依次改写或拦截语句的钩子，见 {@link StatementInterceptor}。 */
  readonly interceptors?: readonly StatementInterceptor[];
  /** 渲染时的警告，例如 SQLite 上需要编译选项的函数（每个数据库实例每种只警告一次）；缺省写到 console.warn。 */
  readonly warn?: (message: string) => void;
  /**
   * 同库分表，例如按月：`[shard(Event, monthly("day", "2025-01", "2026-12"))]`。之后查询按 WHERE 里分表键的条件选表、写成 UNION ALL，
   * 写入按行或按条件写到对应的表；推不出来时报错，除非语句写了 `allShards()` 或 `onShards(...)`。`db.schema()` 按物理表建表和比较。
   */
  readonly shards?: readonly ShardConfig[];
  /**
   * 表声明里的 schema 换成实际的名字，例如 SAP 各环境的 schema 不同：`{ ERP: "SAPHANADB" }`，或按规则 `(s) => s + "_TEST"`。
   * 查询、写语句和关系里的表都换；原生 SQL 文本不换。
   */
  readonly schemas?: Readonly<Record<string, string>> | ((schema: string) => string);
  readonly cache?: QueryCache;
  readonly listener?: QueryListener;
  /**
   * 打印每条执行过的语句（SQL、带类型的参数、行数和耗时），格式见 {@link formatQueryEvent}。
   * `true` 时用 `console.info`，也可以传入自己的输出函数，例如 `(text) => logger.info(text)`。
   */
  readonly printSql?: boolean | ((text: string) => void);
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

/** 把 `DatabaseOptions.schemas` 变成换名规则：对象按名字查，没有的不变。 */
function schemaMapping(schemas: Readonly<Record<string, string>> | ((schema: string) => string)): (schema: string) => string {
  return typeof schemas === "function" ? schemas : (schema) => (Object.hasOwn(schemas, schema) ? schemas[schema]! : schema);
}

let instances = 0;
/** attach 每批至多这么多个键，免得 IN 列表和参数个数过大。 */
const ATTACH_BATCH = 1000;
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
  readonly #interceptors: readonly StatementInterceptor[];

  protected constructor(
    readonly dialect: Dialect,
    driver: Driver,
    options: DatabaseOptions = {},
  ) {
    this.#driver = driver;
    this.name = options.name ?? `${dialect.name}#${++instances}`;
    this.#settings = Object.freeze({
      filters: options.filters ?? [],
      fills: options.fills ?? [],
      sharding: options.shards === undefined || options.shards.length === 0 ? Sharding.NONE : new Sharding(options.shards),
      ...(options.warn === undefined ? {} : { warn: options.warn }),
      ...(options.schemas === undefined ? {} : { schema: schemaMapping(options.schemas) }),
      ...(options.naming === undefined ? {} : { naming: options.naming }),
      ...(options.functions === undefined ? {} : { functions: options.functions }),
    });
    this.#cache = options.cache;
    this.#interceptors = [...(options.interceptors ?? [])];
    const print = options.printSql === true ? printToConsole : options.printSql || undefined;
    const listener = options.listener;
    this.#listener =
      print === undefined ? listener : (event) => {
        print(formatQueryEvent(event));
        listener?.(event);
      };
    if (options.queryTimeoutMs !== undefined && !(options.queryTimeoutMs > 0)) throw new RangeError("queryTimeoutMs must be positive");
    this.#timeout = options.queryTimeoutMs;
  }

  // ---------- 执行 ----------

  /** 执行查询，返回全部行映射后的对象。 */
  async list<R>(q: Query<R>): Promise<R[]> {
    return assembleRows(q, await this.#rows(q, this.render(q)), (child) => this.list(child));
  }

  /** 恰好一行：没有时抛 {@link EmptyResultError}，多于一行时抛 {@link TooManyRowsError}；至多读取两行。 */
  async one<R>(q: Query<R>): Promise<R> {
    const rows = await this.list(limitAtMost(q, 2));
    if (rows.length === 0) throw new EmptyResultError("Expected exactly one row, got none");
    if (rows.length > 1) throw new TooManyRowsError("Expected exactly one row, got more than one");
    return rows[0]!;
  }

  /** 零行或一行：没有时返回 `undefined`，多于一行抛 {@link TooManyRowsError}；至多读取两行。 */
  async optional<R>(q: Query<R>): Promise<R | undefined> {
    const rows = await this.list(limitAtMost(q, 2));
    if (rows.length > 1) throw new TooManyRowsError("Expected at most one row, got more than one");
    return rows[0];
  }

  /**
   * 按键把另一个查询的结果装回已经读出的行（一对一）：收集各行的键（去掉 null 和重复），每批至多 1000 个交给 load
   * 得到查询，执行后按 keyOf 对应回去，用 set 写进行里；没有对应的行写 null。适合不便声明成关系的数据：
   * `await db.attach(orders, (o) => o.customerId, (ids) => from(Customer).where((c) => c.id.in(ids)).select(...), (c) => c.id, (o, c) => { o.customer = c; })`。
   * 同一个键对应多行时抛出 {@link TooManyRowsError}。
   */
  async attach<R, K, V>(rows: readonly R[], key: (row: R) => K | null | undefined, load: (keys: K[]) => Query<V>, keyOf: (value: V) => K, set: (row: R, value: V | null) => void): Promise<void> {
    const found = new Map<K, V>();
    for (const v of await this.#loadAll(rows, key, load)) {
      if (found.has(keyOf(v))) throw new TooManyRowsError(`attach(...) found more than one row for key ${String(keyOf(v))}`);
      found.set(keyOf(v), v);
    }
    for (const r of rows) {
      const k = key(r);
      set(r, k == null ? null : (found.get(k) ?? null));
    }
  }

  /** 同 {@link attach}，但一个键对应多行（一对多），按查询返回的顺序装成数组；没有对应的行写空数组。 */
  async attachMany<R, K, V>(rows: readonly R[], key: (row: R) => K | null | undefined, load: (keys: K[]) => Query<V>, keyOf: (value: V) => K, set: (row: R, values: V[]) => void): Promise<void> {
    const found = new Map<K, V[]>();
    for (const v of await this.#loadAll(rows, key, load)) {
      const list = found.get(keyOf(v));
      if (list === undefined) found.set(keyOf(v), [v]);
      else list.push(v);
    }
    for (const r of rows) {
      const k = key(r);
      set(r, k == null ? [] : [...(found.get(k) ?? [])]);
    }
  }

  /** 去重后的非空键分批交给 load，返回全部结果行。 */
  async #loadAll<R, K, V>(rows: readonly R[], key: (row: R) => K | null | undefined, load: (keys: K[]) => Query<V>): Promise<V[]> {
    const keys = [...new Set(rows.map(key).filter((k): k is K => k != null))];
    const out: V[] = [];
    for (let from = 0; from < keys.length; from += ATTACH_BATCH) out.push(...(await this.list(load(keys.slice(from, from + ATTACH_BATCH)))));
    return out;
  }

  /** 第一行，至多读取一行；没有结果时返回 `undefined`。 */
  async first<R>(q: Query<R>): Promise<R | undefined> {
    return (await this.list(limitAtMost(q, 1)))[0];
  }

  /** 驱动支持时，用一个游标逐行读取。 */
  async *stream<R>(q: Query<R>): AsyncGenerator<R, void, undefined> {
    const driver = this.#current();
    if (driver.stream === undefined || hasChildren(q)) {
      yield* await this.list(q);
      return;
    }
    const statement = this.#intercept(this.render(q));
    this.#checkParameterCount(statement);
    const { types, assemble } = decoderOf(q);
    const start = Date.now();
    let count = 0;
    try {
      for await (const raw of driver.stream(statement.sql, statement.parameters, this.#options(timeoutOf(q)))) {
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

  /** 查询是否至少有一行：`SELECT CASE WHEN EXISTS (查询) THEN 1 ELSE 0 END`，不读出任何行。 */
  async exists(q: Query<unknown>): Promise<boolean> {
    const result = await this.#execute(renderExists(this.#writer(), q), timeoutOf(q));
    return result.rows.length > 0 && integer.decode(result.rows[0]![0]) === 1; // 空跑时没有行
  }

  /** 查询的总行数，用 `COUNT(*)` 包住同一个查询得到。 */
  async count(q: Query<unknown>): Promise<number> {
    const statement = renderCount(this.#writer(), q);
    const result = await this.#execute(statement, timeoutOf(q));
    return result.rows.length === 0 ? 0 : integer.decode(result.rows[0]![0]); // 空跑时没有行
  }

  /**
   * 运行 work，返回它（包括其中的异步调用）通过本数据库执行的全部语句，按执行顺序，含缓存命中，
   * 便于在测试里断言 SQL 和参数：`const sqls = await db.capture(() => service.load(req))`。
   * 配合 {@link dryRunDriver} 可以不连数据库。可以嵌套，内层捕获的语句不再计入外层。需要 Node 的 AsyncLocalStorage。
   */
  async capture(work: () => unknown): Promise<QueryEvent[]> {
    const events: QueryEvent[] = [];
    const storage = captures();
    await storage.run({ database: this, events, outer: storage.getStore() }, async () => {
      await work();
    });
    return events;
  }

  /** 页码从 1 开始；总数来自对同一查询的 COUNT(*)，已知总数（例如翻页时沿用第一页的）时传入 total，不再执行 COUNT。 */
  async page<R>(q: Query<R>, page: number, size: number, total?: number): Promise<Page<R>> {
    if (!Number.isInteger(page) || !Number.isInteger(size) || page < 1 || size < 1) throw new RangeError("page and size are integers from 1");
    if (total !== undefined && (!Number.isSafeInteger(total) || total < 0)) throw new RangeError("total is a non-negative integer");
    const [rows, counted] = await Promise.all([this.list(q.limit(size).offset((page - 1) * size)), total ?? this.count(q)]);
    return Object.freeze({ rows, total: counted, page, size, totalPages: Math.ceil(counted / size) });
  }

  /** 手写 SQL，使用 :name 命名参数，按列标签映射。数组会为 IN (:list) 展开。 */
  sql(text: string): NativeQuery {
    return new NativeQuery(this, text);
  }

  /** 这个数据库实际执行的 SQL，已应用过滤条件和函数改写。 */
  render(q: Query<unknown>): Rendered;
  /** 这个数据库会为写语句执行的语句与参数，已套用全局过滤器；多行插入每行一条。 */
  render(write: Write): Rendered[];
  render(q: Query<unknown> | Write): Rendered | Rendered[] {
    if (q instanceof Write) return q.render(() => this.#writer());
    return rendered(q, this, () => renderStatement(this.#writer(), q));
  }

  // ---------- 写入 ----------

  /**
   * 执行写语句（`insertInto`、`update`、`deleteFrom`），返回影响的总行数；多行插入在一个事务里逐行执行。
   * 语句带期望的行数（`expect(n)`、`byId(row)`）而实际不符时抛出 {@link UnexpectedRowCountError}。
   * 写入成功后清空结果缓存，免得读到改动前的结果。
   */
  async execute(write: Write): Promise<number> {
    const statements = this.render(write);
    if (statements.length === 0) return 0;
    const run = async () => {
      let rows = 0;
      for (const s of statements) rows += (await this.#execute(s, undefined)).affected ?? 0;
      return rows;
    };
    const rows = statements.length > 1 ? await this.#atomically(run) : await run();
    this.#cache?.clear();
    const expected = write.expected;
    if (expected !== undefined && !DRY_RUN.has(this.#driver) && rows !== expected) throw new UnexpectedRowCountError(this.name, statements[0]!, expected, rows);
    return rows;
  }

  /**
   * 插入并按行的顺序取回 `@generated` 列生成的值：PostgreSQL 在语句后加 RETURNING，MySQL 读驱动返回的 insertId，
   * HANA 在同一个连接上查 `CURRENT_IDENTITY_VALUE()`。在一个事务里逐行执行。
   */
  async insertKeys<K = unknown>(insert: Insert<any>): Promise<K[]> {
    const column = insert.generated();
    const statements = this.render(insert);
    if (statements.length === 0) return [];
    const returning = this.dialect.returning(columnName(column, this.#writer().naming));
    const identity = this.dialect.identityQuery;
    const keys = await this.transaction(async () => {
      const out: unknown[] = [];
      for (const s of statements) {
        const result = await this.#execute(returning === undefined ? s : new Rendered(s.sql + returning, s.bindings, s.dialect), undefined);
        const raw =
          returning !== undefined ? result.rows[0]?.[0] : identity !== undefined ? (await this.#execute(new Rendered(identity, [], this.dialect), undefined)).rows[0]?.[0] : result.insertId;
        out.push(raw == null ? null : column.type.decode(raw));
      }
      return out;
    });
    this.#cache?.clear();
    return keys as K[];
  }

  /**
   * 执行会返回行的写语句（例如 PostgreSQL 的 `... RETURNING`），用 decode 把每行变成对象，各条语句的行依次连起来；
   * 供方言的数据库实例使用。多条语句在一个事务里执行，执行后清空结果缓存。
   */
  protected async writeReturning<R>(statements: readonly Rendered[], decode: (row: readonly unknown[]) => R): Promise<R[]> {
    if (statements.length === 0) return [];
    const run = async () => {
      const out: R[] = [];
      for (const s of statements) out.push(...(await this.#execute(s, undefined)).rows.map(decode));
      return out;
    };
    const rows = statements.length > 1 ? await this.#atomically(run) : await run();
    this.#cache?.clear();
    return rows;
  }

  // ---------- 结构变更 ----------

  /** 结构变更：比较实体类的声明与实库并生成 DDL，例如 `await (await db.schema().plan(Note)).apply()`；见 {@link SchemaTools}。 */
  schema(): SchemaTools {
    const syntax = this.dialect.schemaSyntax;
    return new SchemaTools(syntax, this.naming, this.#settings.schema ?? ((s: string) => s), (this.#settings.sharding as Sharding | undefined) ?? Sharding.NONE, (sql, ...parameters) => this.#catalog(sql, parameters), (statements) => this.#runStatements(statements, syntax.transactionalDdl));
  }

  /**
   * 版本化迁移的执行器：`await db.migrator([v1, v2]).migrateToLatest()`，见 {@link Migrator}。
   * 适合作为部署前的一次性任务运行，而不是在每个应用副本启动时运行。
   */
  migrator(migrations: readonly Migration[]): Migrator {
    return new Migrator(migrations, this.dialect, this.dialect.schemaSyntax, {
      query: (sql, ...parameters) => this.#catalog(sql, parameters),
      update: async (sql, ...parameters) => (await this.#execute(this.#native(sql, parameters), undefined)).affected ?? 0,
      run: (statements) => this.#runStatements(statements, false),
      render: (write) => this.render(write),
      execute: (write) => this.execute(write),
      transaction: (work) => this.transaction(work),
    });
  }

  /** 手写的 SQL：问号按顺序换成方言的占位符，参数按值推断类型。 */
  #native(sql: string, parameters: readonly unknown[]): Rendered {
    let n = 0;
    return new Rendered(sql.replace(/\?/g, () => this.dialect.placeholder(++n)), parameters.map((value) => ({ value, type: typeOf(value) })), this.dialect);
  }

  /** 系统目录查询：每行是小写列标签到值的映射。 */
  async #catalog(sql: string, parameters: readonly unknown[]): Promise<Record<string, unknown>[]> {
    const result = await this.#execute(this.#native(sql, parameters), undefined);
    return result.rows.map((row) => Object.fromEntries(result.columns.map((c, i) => [c.toLowerCase(), row[i]])));
  }

  /** 执行手写的语句（DDL 等），一条一次；transactional 时在一个事务里执行。执行后清空结果缓存。 */
  async #runStatements(statements: readonly string[], transactional: boolean): Promise<void> {
    const run = async () => {
      for (const s of statements) await this.#execute(new Rendered(s, [], this.dialect), undefined);
    };
    if (transactional) await this.#atomically(run);
    else await run();
    this.#cache?.clear();
  }

  /** 本库未显式指定列名的字段所用的命名策略。 */
  protected get naming(): NamingStrategy {
    return this.#writer().naming;
  }

  /**
   * 在一个事务里执行 work 并返回它的结果：其中通过这个数据库执行的查询和写语句用同一个连接，正常结束时提交，
   * 抛出异常时回滚；嵌套调用加入外层事务。驱动不支持事务时报错。
   */
  async transaction<R>(work: () => Promise<R>): Promise<R> {
    const outer = this.#bound();
    if (outer !== undefined) return work();
    const driver = this.#driver;
    if (driver.transaction === undefined) throw new Error(`${this.name}: the driver does not support transactions`);
    const storage = transactions();
    return driver.transaction((bound) => storage.run({ database: this, driver: bound, outer: storage.getStore() }, work));
  }

  /** 在一个事务里执行 run；已经在事务里、或驱动不支持事务时直接执行。 */
  #atomically<R>(run: () => Promise<R>): Promise<R> {
    return this.#current() === this.#driver && this.#driver.transaction !== undefined ? this.transaction(run) : run();
  }

  /**
   * 在一个追踪范围里执行 work：用 {@link Tracker} 读到并登记的行，范围正常结束时把变了的按主键写回
   * （`update(表).byId(行).diffFrom(快照)`，同一个事务），抛出异常时什么也不写。范围跟着异步上下文走（AsyncLocalStorage），
   * 嵌套调用加入外层，由最外层写回。写回成功后行的版本字段变成库里的新值；外层事务之后回滚时，已写回的版本不会撤销。
   * 例如 `await db.tracking(async (t) => { const d = await t.one(Doc, q); d.title = "x"; })`。
   */
  async tracking<R>(work: (t: Tracker) => Promise<R>): Promise<R> {
    const storage = trackers();
    let scope = storage.getStore();
    while (scope !== undefined && scope.database !== this) scope = scope.outer;
    if (scope !== undefined) return work(scope.tracker);
    const tracker = new Tracker({
      list: (q) => this.list(q),
      one: (q) => this.one(q),
      optional: (q) => this.optional(q),
      execute: (w) => this.execute(w),
      atomically: (run) => this.#atomically(run),
    });
    const result = await storage.run({ database: this, tracker, outer: storage.getStore() }, () => work(tracker));
    await tracker.flush();
    return result;
  }

  /** 当前异步上下文里这个数据库所在的事务。 */
  #bound(): Transaction | undefined {
    let t = activeTransaction?.getStore();
    while (t !== undefined && t.database !== this) t = t.outer;
    return t;
  }

  /** 当前要用的驱动：在事务里时是事务的驱动。 */
  #current(): Driver {
    return this.#bound()?.driver ?? this.#driver;
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
  async #rows(q: Query<unknown>, rendered: Rendered): Promise<Rows> {
    const { types } = decoderOf(q);
    // 缓存键用执行前钩子改写后的语句，所以先改写，执行时不再改写。
    const statement = this.#intercept(rendered);
    const fetch = async () => (await this.#execute(statement, timeoutOf(q), true)).rows.map((raw) => decode(q, types, raw));
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

  /** 把语句依次交给各个执行前钩子，返回最终要执行的语句。 */
  #intercept(statement: Rendered): Rendered {
    let current = statement;
    for (const intercept of this.#interceptors) {
      current = intercept(current);
      if (!(current instanceof Rendered)) throw new Error(`A statement interceptor returned ${String(current)} for ${statement.sql}`);
    }
    return current;
  }

  /** 执行语句并通知监听器；`timeoutMs` 为 undefined 时用实例的默认值。intercepted 为 true 表示已经经过执行前钩子。 */
  async #execute(rendered: Rendered, timeoutMs: number | undefined, intercepted = false): Promise<DriverResult> {
    const statement = intercepted ? rendered : this.#intercept(rendered);
    this.#checkParameterCount(statement);
    const start = Date.now();
    try {
      const result = await this.#current().query(statement.sql, statement.parameters, this.#options(timeoutMs));
      this.#notify(statement, start, result.affected ?? result.rows.length, undefined, false);
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
    let capture = activeCapture?.getStore();
    while (capture !== undefined && capture.database !== this) capture = capture.outer;
    if (this.#listener === undefined && capture === undefined) return;
    const event: QueryEvent = { database: this.name, statement, elapsedMs: Date.now() - start, rows, error, cached };
    capture?.events.push(event);
    if (this.#listener === undefined) return;
    try {
      this.#listener(event);
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
    if (rows.length === 0) throw new EmptyResultError("Expected exactly one row, got none");
    if (rows.length > 1) throw new TooManyRowsError(`Expected exactly one row, got ${rows.length}`);
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

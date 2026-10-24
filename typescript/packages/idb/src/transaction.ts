import type { StoreClass, StoreMeta } from "./metadata.js";
import { encodeKey, fromRecord, InvalidRowError, toRecord } from "./rows.js";
import { Source } from "./query.js";

/**
 * 事务已经结束后又经由它读写：通常是回调里 await 了 IndexedDB 以外的工作（fetch、定时器、别的数据库），
 * 浏览器在那时就自动提交了事务。把那些工作挪到事务之前或之后。
 */
export class TransactionClosedError extends Error {
  constructor(stores: readonly string[]) {
    super(
      `The transaction on [${stores.join(", ")}] has already committed: the callback awaited non-IndexedDB work (fetch, a timer, another database). Do that work before or after the transaction.`,
    );
    this.name = "TransactionClosedError";
  }
}

/** 批量写入里有行失败：整批（连同所在的事务）回滚。failures 逐行列出序号和原因。 */
export class BatchWriteError extends Error {
  constructor(
    readonly store: string,
    readonly failures: readonly { readonly index: number; readonly reason: string }[],
  ) {
    super(`${failures.length} of the rows could not be written to ${store}, so none were: ${failures.map((f) => `#${f.index}: ${f.reason}`).join("; ")}`);
    this.name = "BatchWriteError";
  }
}

/** 写入超出了浏览器给这个源的配额。usage、quota 是 `navigator.storage.estimate()` 的估计值（字节），拿不到时为 undefined。 */
export class QuotaError extends Error {
  constructor(
    readonly usage: number | undefined,
    readonly quota: number | undefined,
    cause: unknown,
  ) {
    super(`The browser's storage quota is exhausted${usage === undefined ? "" : ` (using ${usage} of ${quota} bytes)`}; delete data or ask for persistent storage`, { cause });
    this.name = "QuotaError";
  }

  /** e 是配额错误时换成 QuotaError（带上估计值），否则原样返回。 */
  static async wrap(e: unknown): Promise<unknown> {
    if ((e as DOMException | undefined)?.name !== "QuotaExceededError") return e;
    const estimate = await storageEstimate();
    return new QuotaError(estimate?.usage, estimate?.quota, e);
  }
}

/** `navigator.storage.estimate()`；没有 StorageManager 时为 undefined。 */
export async function storageEstimate(): Promise<StorageEstimate | undefined> {
  const storage = (globalThis as { navigator?: { storage?: StorageManager } }).navigator?.storage;
  return storage?.estimate === undefined ? undefined : storage.estimate().catch(() => undefined);
}

/** 查询在哪里执行：数据库（每次一个新事务）或已有的事务（加入它）。 */
export interface Runner {
  /** 仓库类的元数据；类不在 openLocal 的 stores 里时报错。 */
  meta(type: StoreClass): StoreMeta;
  /** 在覆盖 stores、模式为 mode 的事务里执行 work。 */
  run<R>(stores: readonly StoreClass[], mode: IDBTransactionMode, work: (tx: Transaction) => Promise<R>): Promise<R>;
  /** 实时查询：stores 里任何一个有写入提交时调用 refresh；返回取消的函数。只有数据库有，事务里没有。 */
  watch?(stores: readonly string[], refresh: () => void): () => void;
}

/** 本包内部（查询）经由事务读写原生仓库的方式；不对外导出。 */
export interface Access {
  /** 用 make 在 meta 的仓库上发一个请求并等它的结果；事务已结束时报 {@link TransactionClosedError}。 */
  request<T>(meta: StoreMeta, make: (store: IDBObjectStore) => IDBRequest<T>): Promise<T>;
  /** 打开游标并对每一条调用 visit，visit 返回 false 时停下。整个遍历在请求的回调里完成，不跨越别的 await。 */
  cursor(meta: StoreMeta, make: (store: IDBObjectStore) => IDBRequest<IDBCursorWithValue | null>, visit: (cursor: IDBCursorWithValue) => boolean): Promise<void>;
}

const ACCESS = new WeakMap<Transaction, Access>();

/** 查询用：取事务的 {@link Access}。 */
export function accessOf(tx: Transaction): Access {
  return ACCESS.get(tx)!;
}

/**
 * 一个 IndexedDB 事务，由 `db.transaction(...)` 交给回调，也是 `openLocal` 的 upgrade 收到的 tx。回调里只用 tx 上的操作：
 *
 * ```ts
 * await db.transaction([Note], "readwrite", async (tx) => {
 *   const n = await tx.get(Note, "n1");
 *   if (n) await tx.put(Note, { ...n, title: "x" });
 * });
 * ```
 *
 * 回调正常结束时提交，抛出异常时回滚。回调里 await 了别的 Promise 时浏览器会自动提交事务，之后再用 tx 报 {@link TransactionClosedError}。
 */
export class Transaction implements Runner {
  readonly #raw: IDBTransaction;
  readonly #runner: Runner;
  readonly #finished: Promise<void>;
  #done = false;
  /** 写过的仓库名，提交后用于通知实时查询。 */
  readonly written = new Set<string>();

  /** 包装原生事务；runner 是它所属的数据库，用于查找仓库的元数据。 */
  constructor(raw: IDBTransaction, runner: Runner) {
    this.#raw = raw;
    this.#runner = runner;
    this.#finished = new Promise<void>((resolve, reject) => {
      raw.addEventListener("complete", () => {
        this.#done = true;
        resolve();
      });
      raw.addEventListener("abort", () => {
        this.#done = true;
        reject(raw.error ?? new DOMException("The transaction was aborted", "AbortError"));
      });
    });
    this.#finished.catch(() => undefined); // 由 execute 读取结果；这里只是不让它成为未处理的拒绝
    ACCESS.set(this, {
      request: (meta, make) => this.#request(meta, make),
      cursor: (meta, make, visit) =>
        new Promise((resolve, reject) => {
          const req = this.#guard(meta, make);
          req.onsuccess = () => {
            const c = req.result;
            if (c === null || !visit(c)) resolve();
            else c.continue();
          };
          req.onerror = () => reject(req.error);
        }),
    });
  }

  /** 事务覆盖的仓库名。 */
  get stores(): readonly string[] {
    return Array.from(this.#raw.objectStoreNames);
  }

  /** 事务的模式：`readonly`、`readwrite` 或升级时的 `versionchange`。 */
  get mode(): IDBTransactionMode {
    return this.#raw.mode;
  }

  /** 按主键读一行；复合主键传数组。没有时返回 undefined。 */
  async get<T>(type: StoreClass<T>, key: unknown): Promise<T | undefined> {
    const meta = this.meta(type);
    const record = await this.#request(meta, (s) => s.get(encodeKey(meta, meta.key, key)));
    return record === undefined ? undefined : fromRecord<T>(meta, record);
  }

  /** 插入或覆盖一行。 */
  async put<T>(type: StoreClass<T>, row: T): Promise<void> {
    const meta = this.meta(type);
    const record = toRecord(meta, row as object);
    await this.#request(meta, (s) => s.put(record));
    this.written.add(meta.name);
  }

  /** 插入一行；主键已存在或违反唯一索引时报错。 */
  async add<T>(type: StoreClass<T>, row: T): Promise<void> {
    const meta = this.meta(type);
    const record = toRecord(meta, row as object);
    await this.#request(meta, (s) => s.add(record));
    this.written.add(meta.name);
  }

  /** 插入或覆盖多行；任何一行失败时整个事务回滚，报 {@link BatchWriteError}。 */
  putAll<T>(type: StoreClass<T>, rows: readonly T[]): Promise<void> {
    return this.#writeAll(type, rows, "put");
  }

  /** 插入多行；任何一行失败（主键重复、违反唯一索引）时整个事务回滚，报 {@link BatchWriteError}，列出全部失败的行。 */
  addAll<T>(type: StoreClass<T>, rows: readonly T[]): Promise<void> {
    return this.#writeAll(type, rows, "add");
  }

  /** 按主键删一行；没有这行时什么也不做。 */
  async delete(type: StoreClass, key: unknown): Promise<void> {
    const meta = this.meta(type);
    await this.#request(meta, (s) => s.delete(encodeKey(meta, meta.key, key)));
    this.written.add(meta.name);
  }

  /** 清空仓库。 */
  async clear(type: StoreClass): Promise<void> {
    const meta = this.meta(type);
    await this.#request(meta, (s) => s.clear());
    this.written.add(meta.name);
  }

  /** 在这个事务里查询仓库，见 {@link Source}。 */
  from<T>(type: StoreClass<T>): Source<T> {
    return new Source<T>(this, this.meta(type));
  }

  /** 嵌套的事务加入这一个：stores 必须在本事务里，本事务是只读时不能要求读写。 */
  transaction<R>(stores: readonly StoreClass[], mode: IDBTransactionMode, work: (tx: Transaction) => Promise<R>): Promise<R> {
    return this.run(stores, mode, work);
  }

  meta(type: StoreClass): StoreMeta {
    return this.#runner.meta(type);
  }

  /** 加入本事务执行 work；仓库或模式超出本事务时报错。 */
  async run<R>(stores: readonly StoreClass[], mode: IDBTransactionMode, work: (tx: Transaction) => Promise<R>): Promise<R> {
    const missing = stores.map((s) => this.meta(s).name).filter((n) => !this.#raw.objectStoreNames.contains(n));
    if (missing.length > 0) throw new Error(`[${missing.join(", ")}] are not in this transaction on [${this.stores.join(", ")}]; list every store when opening the outer transaction`);
    if (mode !== "readonly" && this.#raw.mode === "readonly") throw new Error(`This transaction on [${this.stores.join(", ")}] is readonly; open the outer transaction as readwrite`);
    return work(this);
  }

  /** 在事务里执行 work：正常结束时提交并等到完成，抛出异常时回滚；配额用尽时报 {@link QuotaError}。由数据库调用。 */
  static async execute<R>(tx: Transaction, work: (tx: Transaction) => Promise<R>): Promise<R> {
    let result: R;
    try {
      result = await work(tx);
    } catch (e) {
      tx.#abort();
      await tx.#finished.catch(() => undefined);
      throw await QuotaError.wrap(e);
    }
    if (!tx.#done) {
      try {
        tx.#raw.commit();
      } catch {
        // 事务已经不活跃，正在自动提交
      }
    }
    try {
      await tx.#finished;
    } catch (e) {
      throw await QuotaError.wrap(e);
    }
    return result;
  }

  /** 中止事务；已经结束时什么也不做。 */
  #abort(): void {
    if (this.#done) return;
    try {
      this.#raw.abort();
    } catch {
      // 已经在提交或中止
    }
  }

  /** 发一个请求并等它的结果。 */
  #request<T>(meta: StoreMeta, make: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const req = this.#guard(meta, make);
    return new Promise<T>((resolve, reject) => {
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  /** 在 meta 的仓库上调用 make；事务已结束、仓库不在事务里时给出明确的报错。 */
  #guard<T>(meta: StoreMeta, make: (store: IDBObjectStore) => T): T {
    if (this.#done) throw new TransactionClosedError(this.stores);
    try {
      return make(this.#raw.objectStore(meta.name));
    } catch (e) {
      const name = (e as DOMException | undefined)?.name;
      // objectStore() 只在事务已结束时报 InvalidStateError；complete 事件可能还没派发到这里，所以不看 #done
      if (name === "TransactionInactiveError" || name === "InvalidStateError") throw new TransactionClosedError(this.stores);
      if (name === "NotFoundError" && !this.#raw.objectStoreNames.contains(meta.name)) throw new Error(`${meta.name} is not in this transaction on [${this.stores.join(", ")}]`, { cause: e });
      throw e;
    }
  }

  /** 批量写入：先检查全部行，再在同一个事务里逐行写；有失败的行时中止事务并列出它们。 */
  async #writeAll<T>(type: StoreClass<T>, rows: readonly T[], method: "put" | "add"): Promise<void> {
    const meta = this.meta(type);
    const failures: { index: number; reason: string }[] = [];
    const records = rows.map((row, index) => {
      try {
        return toRecord(meta, row as object);
      } catch (e) {
        failures.push({ index, reason: e instanceof InvalidRowError ? e.problems.join("; ") : String(e) });
        return undefined;
      }
    });
    if (failures.length > 0) throw new BatchWriteError(meta.name, failures);
    await Promise.all(
      records.map(
        (record, index) =>
          new Promise<void>((resolve) => {
            const req = this.#guard(meta, (s) => s[method](record));
            req.onsuccess = () => resolve();
            req.onerror = (event) => {
              event.preventDefault(); // 先不让这一行的错误中止事务，收集完全部失败的行再中止
              event.stopPropagation();
              failures.push({ index, reason: req.error?.message ?? String(req.error) });
              resolve();
            };
          }),
      ),
    );
    if (failures.length > 0) {
      this.#abort();
      throw new BatchWriteError(meta.name, failures.sort((a, b) => a.index - b.index));
    }
    this.written.add(meta.name);
  }
}

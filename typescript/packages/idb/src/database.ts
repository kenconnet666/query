import { storeMeta, type IndexMeta, type StoreClass, type StoreMeta } from "./metadata.js";
import { Source } from "./query.js";
import { storageEstimate, Transaction, type Runner } from "./transaction.js";

/** 一个版本的数据迁移，在升级的 versionchange 事务里执行；只用 tx 上的操作，不要 await 别的工作。 */
export type UpgradeStep = (tx: Transaction, info: { readonly oldVersion: number }) => void | Promise<void>;

/** {@link openLocal} 的选项。 */
export interface OpenOptions {
  /** 结构的版本号，正整数；声明的仓库或索引变了就加大它。 */
  readonly version: number;
  /** 这个库的全部仓库类。 */
  readonly stores: readonly StoreClass[];
  /** 各版本的数据迁移：从旧版本升级时，按版本号依次执行大于旧版本、不大于 version 的那些。 */
  readonly upgrade?: Readonly<Record<number, UpgradeStep>>;
  /** 升级时删掉的仓库名（会丢数据，所以要显式写）。库里多出的、没有列在这里的仓库保留不动。 */
  readonly dropStores?: readonly string[];
  /** 别的标签页要升级这个库：本页的连接已经关闭，通常提示用户刷新。 */
  readonly onVersionChange?: () => void;
  /** 本页要升级，但别的标签页还开着旧版本的连接。 */
  readonly onBlocked?: () => void;
  /** 写事务的持久性，缺省 `"default"`；`"strict"` 在提交前落盘。 */
  readonly durability?: IDBTransactionDurability;
}

/**
 * 打开（必要时创建或升级）一个 IndexedDB 库：
 *
 * ```ts
 * const db = await openLocal("app", { version: 2, stores: [Note, Tag], upgrade: { 2: async (tx) => { ... } } });
 * ```
 *
 * 升级时比较声明与库里的结构：缺的仓库和索引自动建，库里多出的索引自动删，主键变了时报错（IndexedDB 不能就地改主键）。
 * 版本号没变而声明变了时报错，提示加大 version，不用旧结构悄悄地跑。
 */
export async function openLocal(name: string, options: OpenOptions): Promise<LocalDatabase> {
  if (!Number.isInteger(options.version) || options.version < 1) throw new RangeError("version is a positive integer");
  const metas = new Map(options.stores.map((s) => [s, storeMeta(s)]));
  const names = new Set<string>();
  for (const m of metas.values()) {
    if (names.has(m.name)) throw new Error(`Two store classes are both named ${m.name}`);
    names.add(m.name);
  }
  const registry: Runner = {
    meta: (type) => lookup(name, metas, type),
    run: () => Promise.reject(new Error("not available during the upgrade")),
  };
  const raw = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(name, options.version);
    let failure: unknown;
    request.onupgradeneeded = (event) => {
      const db = request.result;
      const tx = request.transaction!;
      try {
        applySchema(db, tx, [...metas.values()], options.dropStores ?? []);
      } catch (e) {
        failure = e;
        tx.abort();
        return;
      }
      const steps = Object.keys(options.upgrade ?? {})
        .map(Number)
        .filter((v) => v > event.oldVersion && v <= options.version)
        .sort((a, b) => a - b);
      if (steps.length === 0) return;
      const wrapped = new Transaction(tx, registry);
      (async () => {
        for (const v of steps) await options.upgrade![v]!(wrapped, { oldVersion: event.oldVersion });
      })().catch((e: unknown) => {
        failure = e;
        try {
          tx.abort();
        } catch {
          // 升级事务已经提交了（步骤里 await 了别的工作）；打开成功后再报出 failure
        }
      });
    };
    request.onblocked = () => options.onBlocked?.();
    request.onsuccess = () => {
      if (failure === undefined) resolve(request.result);
      else {
        request.result.close();
        reject(failure);
      }
    };
    request.onerror = () => reject(failure ?? request.error);
  });
  const problems = await differences(raw, [...metas.values()], options.dropStores ?? []);
  if (problems.length > 0) {
    raw.close();
    throw new Error(`The stores declared for ${name} differ from version ${raw.version} in this browser (${problems.join("; ")}); raise version to ${raw.version + 1}`);
  }
  return new LocalDatabase(raw, metas, options);
}

/**
 * 打开的 IndexedDB 库，由 {@link openLocal} 得到。单条读写各自一个事务；要多步一起成功或失败时用 {@link transaction}。
 * 查询从 {@link from} 开始。
 */
export class LocalDatabase implements Runner {
  readonly #raw: IDBDatabase;
  readonly #metas: ReadonlyMap<StoreClass, StoreMeta>;
  readonly #durability: IDBTransactionDurability;
  /** 实时查询：仓库名到要重新执行的查询。 */
  readonly #watchers = new Map<string, Set<() => void>>();
  /** 与同源的其他标签页互通写入的频道，第一次需要时创建。 */
  #channel: BroadcastChannel | undefined;
  #closed = false;

  /** 由 openLocal 创建。 */
  constructor(raw: IDBDatabase, metas: ReadonlyMap<StoreClass, StoreMeta>, options: OpenOptions) {
    this.#raw = raw;
    this.#metas = metas;
    this.#durability = options.durability ?? "default";
    raw.onversionchange = () => {
      this.close();
      options.onVersionChange?.();
    };
  }

  /** 库名。 */
  get name(): string {
    return this.#raw.name;
  }

  /** 结构的版本号。 */
  get version(): number {
    return this.#raw.version;
  }

  /** 按主键读一行；复合主键传数组。 */
  get<T>(type: StoreClass<T>, key: unknown): Promise<T | undefined> {
    return this.run([type], "readonly", (tx) => tx.get(type, key));
  }

  /** 插入或覆盖一行。 */
  put<T>(type: StoreClass<T>, row: T): Promise<void> {
    return this.run([type], "readwrite", (tx) => tx.put(type, row));
  }

  /** 插入一行；主键已存在或违反唯一索引时报错。 */
  add<T>(type: StoreClass<T>, row: T): Promise<void> {
    return this.run([type], "readwrite", (tx) => tx.add(type, row));
  }

  /** 在一个事务里插入或覆盖多行；任何一行失败时都不写。 */
  putAll<T>(type: StoreClass<T>, rows: readonly T[]): Promise<void> {
    return this.run([type], "readwrite", (tx) => tx.putAll(type, rows));
  }

  /** 在一个事务里插入多行；任何一行失败时都不写，错误里列出失败的行。 */
  addAll<T>(type: StoreClass<T>, rows: readonly T[]): Promise<void> {
    return this.run([type], "readwrite", (tx) => tx.addAll(type, rows));
  }

  /** 按主键删一行。 */
  delete(type: StoreClass, key: unknown): Promise<void> {
    return this.run([type], "readwrite", (tx) => tx.delete(type, key));
  }

  /** 清空仓库。 */
  clear(type: StoreClass): Promise<void> {
    return this.run([type], "readwrite", (tx) => tx.clear(type));
  }

  /** 查询一个仓库，见 {@link Source}。每个终结操作一个只读（或读写）事务。 */
  from<T>(type: StoreClass<T>): Source<T> {
    return new Source<T>(this, this.meta(type));
  }

  /**
   * 在覆盖 stores 的事务里执行 work：正常结束时提交，抛出异常时回滚。回调里只用 tx 的操作，
   * await 了别的工作时浏览器会提前提交，之后再用 tx 报 {@link TransactionClosedError}。
   */
  transaction<R>(stores: readonly StoreClass[], mode: "readonly" | "readwrite", work: (tx: Transaction) => Promise<R>): Promise<R> {
    return this.run(stores, mode, work);
  }

  /**
   * 请求持久存储（`navigator.storage.persist()`），返回是否获准。浏览器多半要求在用户手势（点击）里调用；
   * 没有获准时，空间紧张或（Safari 下）七天没有访问时数据可能被清除，重要的数据不要只存在这里。
   */
  async persist(): Promise<boolean> {
    const storage = (globalThis as { navigator?: { storage?: StorageManager } }).navigator?.storage;
    return storage?.persist === undefined ? false : storage.persist();
  }

  /** 这个源已用和可用的存储空间估计（`navigator.storage.estimate()`，字节）；浏览器不支持时为 undefined。 */
  estimate(): Promise<StorageEstimate | undefined> {
    return storageEstimate();
  }

  /** 关闭连接和标签页之间的频道；之后的读写报错，实时查询不再更新。 */
  close(): void {
    this.#closed = true;
    this.#channel?.close();
    this.#channel = undefined;
    this.#raw.close();
  }

  watch(stores: readonly string[], refresh: () => void): () => void {
    for (const s of stores) {
      let set = this.#watchers.get(s);
      if (set === undefined) this.#watchers.set(s, (set = new Set()));
      set.add(refresh);
    }
    this.#connect();
    return () => {
      for (const s of stores) this.#watchers.get(s)?.delete(refresh);
    };
  }

  meta(type: StoreClass): StoreMeta {
    return lookup(this.name, this.#metas, type);
  }

  /** 开一个新事务执行 work。 */
  run<R>(stores: readonly StoreClass[], mode: IDBTransactionMode, work: (tx: Transaction) => Promise<R>): Promise<R> {
    if (this.#closed) return Promise.reject(new Error(`The database ${this.name} is closed (close() was called, or another tab upgraded it)`));
    const names = [...new Set(stores.map((s) => this.meta(s).name))];
    const tx = new Transaction(this.#raw.transaction(names, mode, { durability: this.#durability }), this);
    return Transaction.execute(tx, work).then((result) => {
      if (tx.written.size > 0) {
        const written = [...tx.written];
        this.#notify(written);
        this.#connect()?.postMessage({ stores: written });
      }
      return result;
    });
  }

  /** 重新执行读过这些仓库的实时查询（每个只执行一次）。 */
  #notify(stores: readonly string[]): void {
    const due = new Set<() => void>();
    for (const s of stores) for (const refresh of this.#watchers.get(s) ?? []) due.add(refresh);
    for (const refresh of due) refresh();
  }

  /** 标签页之间的频道（同名的库共用）；没有 BroadcastChannel 或已关闭时为 undefined。 */
  #connect(): BroadcastChannel | undefined {
    if (this.#channel !== undefined || this.#closed || typeof BroadcastChannel === "undefined") return this.#channel;
    this.#channel = new BroadcastChannel(`@zerodep-dev/idb:${this.name}`);
    (this.#channel as { unref?(): void }).unref?.(); // Node 上频道不让进程一直不退出；浏览器没有 unref
    this.#channel.onmessage = (event: MessageEvent<{ stores?: unknown }>) => {
      if (Array.isArray(event.data?.stores)) this.#notify(event.data.stores.filter((s): s is string => typeof s === "string"));
    };
    return this.#channel;
  }
}

/** 仓库类在这个库里的元数据；没有登记时报错。 */
function lookup(name: string, metas: ReadonlyMap<StoreClass, StoreMeta>, type: StoreClass): StoreMeta {
  const meta = metas.get(type);
  if (meta === undefined) throw new Error(`${type.name} is not among the stores passed to openLocal("${name}")`);
  return meta;
}

/** 两个 keyPath 是否相同。 */
function sameKeyPath(a: string | string[] | null, b: string | readonly string[]): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** 库里的索引是否与声明一致。 */
function sameIndex(actual: IDBIndex, wanted: IndexMeta): boolean {
  return sameKeyPath(actual.keyPath, wanted.keyPath) && actual.unique === wanted.unique && actual.multiEntry === wanted.multiEntry;
}

/** 升级时让库里的结构与声明一致：删列出的仓库，建缺的仓库和索引，删多出的或变了的索引；主键变了时报错。 */
function applySchema(db: IDBDatabase, tx: IDBTransaction, metas: readonly StoreMeta[], drop: readonly string[]): void {
  for (const name of drop) if (db.objectStoreNames.contains(name)) db.deleteObjectStore(name);
  for (const meta of metas) {
    const exists = db.objectStoreNames.contains(meta.name);
    const store = exists ? tx.objectStore(meta.name) : db.createObjectStore(meta.name, { keyPath: meta.key.keyPath as string | string[] });
    if (exists && !sameKeyPath(store.keyPath, meta.key.keyPath)) {
      throw new Error(`${meta.name}: the primary key changed from ${JSON.stringify(store.keyPath)} to ${JSON.stringify(meta.key.keyPath)}; IndexedDB cannot change it in place, so give the store a new name and list the old one in dropStores`);
    }
    for (const name of Array.from(store.indexNames)) {
      const wanted = meta.indexes.get(name);
      if (wanted === undefined || !sameIndex(store.index(name), wanted)) store.deleteIndex(name);
    }
    for (const index of meta.indexes.values()) {
      if (!store.indexNames.contains(index.name)) store.createIndex(index.name, index.keyPath as string | string[], { unique: index.unique, multiEntry: index.multiEntry });
    }
  }
}

/** 库里的结构与声明的差异（不升级时检查）；库里多出的、没有列进 dropStores 的仓库不算。 */
async function differences(db: IDBDatabase, metas: readonly StoreMeta[], drop: readonly string[]): Promise<string[]> {
  const out: string[] = [];
  for (const name of drop) if (db.objectStoreNames.contains(name)) out.push(`${name} is listed in dropStores but still exists`);
  const present = metas.filter((m) => {
    if (db.objectStoreNames.contains(m.name)) return true;
    out.push(`the store ${m.name} is missing`);
    return false;
  });
  if (present.length === 0) return out;
  const tx = db.transaction(
    present.map((m) => m.name),
    "readonly",
  );
  for (const meta of present) {
    const store = tx.objectStore(meta.name);
    if (!sameKeyPath(store.keyPath, meta.key.keyPath)) out.push(`${meta.name} has the primary key ${JSON.stringify(store.keyPath)}`);
    for (const name of Array.from(store.indexNames)) {
      const wanted = meta.indexes.get(name);
      if (wanted === undefined) out.push(`${meta.name} has the undeclared index ${name}`);
      else if (!sameIndex(store.index(name), wanted)) out.push(`${meta.name}.${name} differs`);
    }
    for (const index of meta.indexes.values()) if (!store.indexNames.contains(index.name)) out.push(`${meta.name}.${index.name} is missing`);
  }
  await new Promise<void>((resolve) => {
    tx.oncomplete = () => resolve();
    tx.onabort = () => resolve();
  });
  return out;
}

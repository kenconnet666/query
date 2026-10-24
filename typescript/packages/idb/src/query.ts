import type { IndexMeta, StoreMeta } from "./metadata.js";
import { decodeKey, encodeKey, fromRecord, keyAt, keyText, primaryKeyOf, toRecord } from "./rows.js";
import { accessOf, type Runner, type Transaction } from "./transaction.js";

/** 行上可以查询的字段名（不含方法）。 */
type Fields<T> = { [K in keyof T]-?: T[K] extends (...args: never[]) => unknown ? never : K }[keyof T] & string;

/** 多值索引按元素查：数组字段取元素类型。 */
type Element<V> = V extends readonly (infer E)[] ? E : V;

/** 查询的选项：都在构造查询时确定，查询不可变。 */
interface Options<T> {
  readonly reverse: boolean;
  readonly offset: number;
  readonly limit: number | undefined;
  readonly scans: readonly ((row: T) => boolean)[];
  readonly sortBy: ((a: T, b: T) => number) | undefined;
  readonly include: readonly string[];
}

const DEFAULTS: Options<any> = Object.freeze({ reverse: false, offset: 0, limit: undefined, scans: [], sortBy: undefined, include: [] });

/** 一行读到的结果：存的主键和还原后的行。 */
interface Entry<T> {
  readonly key: IDBValidKey;
  readonly row: T;
}

/**
 * 一个仓库上的查询起点，由 `db.from(Note)` 或 `tx.from(Note)` 得到。只能按索引查：`where(索引)`；
 * 要全表时写 `all()`。不在索引上的筛选写 `scan(...)`，名字就说明它在内存里做。
 */
export class Source<T> {
  constructor(
    readonly runner: Runner,
    readonly meta: StoreMeta,
  ) {}

  /**
   * 按一个索引（或主键）查：字段名，复合索引给字段名的数组（按声明的顺序）。名字不是索引时立刻报错并列出已有的索引。
   * 结果按这个索引的顺序。
   */
  where<K extends Fields<T>>(field: K): Where<T, Element<NonNullable<T[K]>>>;
  where<const K extends readonly Fields<T>[]>(fields: K): Where<T, { [I in keyof K]: NonNullable<T[K[I] & keyof T]> }>;
  where(field: string | readonly string[]): Where<T, unknown> {
    const name = typeof field === "string" ? field : field.join("+");
    const index = name === this.meta.key.fields.join("+") ? this.meta.key : this.meta.indexes.get(name);
    if (index === undefined) {
      const known = [...this.meta.indexes.keys(), `${this.meta.key.fields.join("+")} (the primary key)`];
      throw new Error(`${this.meta.name} has no index ${name}; its indexes are ${known.join(", ")}. Add @index (or a compound index on @store), or use all().scan(...)`);
    }
    return new Where<T, unknown>(this, index);
  }

  /** 全部行，按主键的顺序。 */
  all(): Query<T> {
    return new Query<T>(this, this.meta.key, [null], DEFAULTS);
  }
}

/** `between` 的端点是否开区间；缺省两端都包含（与 SQL 的 BETWEEN 一致）。 */
export interface BetweenOptions {
  readonly lowerOpen?: boolean;
  readonly upperOpen?: boolean;
}

/** 索引上的条件，由 {@link Source.where} 得到；每个方法给出一个 {@link Query}。V 是索引值的类型（复合索引是元组）。 */
export class Where<T, V> {
  constructor(
    readonly source: Source<T>,
    readonly index: IndexMeta,
  ) {}

  /** 等于 value。 */
  eq(value: V): Query<T> {
    return this.#query([IDBKeyRange.only(this.#key(value))]);
  }

  /** 等于其中任何一个值；结果按索引的顺序，一行只出现一次。空列表时没有结果。 */
  anyOf(values: readonly V[]): Query<T> {
    const keys = values.map((v) => this.#key(v)).sort((a, b) => indexedDB.cmp(a, b));
    const distinct = keys.filter((k, i) => i === 0 || indexedDB.cmp(k, keys[i - 1]!) !== 0);
    return this.#query(distinct.map((k) => IDBKeyRange.only(k)));
  }

  /** 在 lower 与 upper 之间，缺省两端都包含。 */
  between(lower: V, upper: V, options: BetweenOptions = {}): Query<T> {
    const lo = this.#key(lower);
    const hi = this.#key(upper);
    if (indexedDB.cmp(lo, hi) > 0) return this.#query([]);
    return this.#query([IDBKeyRange.bound(lo, hi, options.lowerOpen ?? false, options.upperOpen ?? false)]);
  }

  /** 大于 value。 */
  gt(value: V): Query<T> {
    return this.#query([IDBKeyRange.lowerBound(this.#key(value), true)]);
  }

  /** 大于等于 value。 */
  ge(value: V): Query<T> {
    return this.#query([IDBKeyRange.lowerBound(this.#key(value))]);
  }

  /** 小于 value。 */
  lt(value: V): Query<T> {
    return this.#query([IDBKeyRange.upperBound(this.#key(value), true)]);
  }

  /** 小于等于 value。 */
  le(value: V): Query<T> {
    return this.#query([IDBKeyRange.upperBound(this.#key(value))]);
  }

  /** 文本以 prefix 开头（区分大小写；要不区分时给索引 `codec: lowercase`）。只用于单字段的文本索引。 */
  startsWith(prefix: string): Query<T> {
    if (this.index.fields.length > 1) throw new Error(`startsWith needs a single-field index, not ${this.index.name}; use between on the compound index`);
    const lo = this.#key(prefix as V) as string;
    if (typeof lo !== "string") throw new TypeError(`startsWith needs a text index; ${this.index.name} encodes values as ${typeof lo}`);
    return this.#query([IDBKeyRange.bound(lo, lo + "￿")]);
  }

  /** 查询值编码成这个索引的键。 */
  #key(value: V): IDBValidKey {
    return encodeKey(this.source.meta, this.index, value);
  }

  #query(ranges: readonly IDBKeyRange[]): Query<T> {
    return new Query<T>(this.source, this.index, ranges, DEFAULTS);
  }
}

/**
 * 按索引的查询，不可变：每个方法返回新的查询。结果按索引的顺序（`reverse()` 倒过来），`offset`、`limit` 分页；
 * `scan` 和 `sortBy` 在内存里做。终结操作：`list`、`first`、`count`、`keys`，以及写操作 `delete`、`modify`。
 *
 * ```ts
 * await db.from(Note).where("owner").eq("u1").reverse().limit(20).list();
 * await db.from(Note).all().scan((n) => n.title.includes("会议")).list();
 * ```
 */
export class Query<T> {
  constructor(
    readonly source: Source<T>,
    readonly index: IndexMeta,
    /** 要读的键范围，按索引的顺序；null 代表全部。 */
    readonly ranges: readonly (IDBKeyRange | null)[],
    readonly options: Options<T>,
  ) {}

  /** 倒序。 */
  reverse(): Query<T> {
    return this.#with({ reverse: !this.options.reverse });
  }

  /** 跳过前 n 行（在 scan 之后、sortBy 之后计算）。 */
  offset(n: number): Query<T> {
    if (!Number.isInteger(n) || n < 0) throw new RangeError("offset is a non-negative integer");
    return this.#with({ offset: n });
  }

  /** 至多 n 行。 */
  limit(n: number): Query<T> {
    if (!Number.isInteger(n) || n < 1) throw new RangeError("limit is a positive integer");
    return this.#with({ limit: n });
  }

  /** 在内存里筛选：读出索引范围内的行再逐行判断。多个 scan 都要满足。 */
  scan(predicate: (row: T) => boolean): Query<T> {
    return this.#with({ scans: [...this.options.scans, predicate] });
  }

  /** 在内存里排序：读出全部匹配的行再排，然后才算 offset、limit。 */
  sortBy(compare: (a: T, b: T) => number): Query<T> {
    return this.#with({ sortBy: compare });
  }

  /** 读完行后按 `@one` / `@many` 的关系批量装回这些字段：每个关系一批读取，不按行逐条读。 */
  include(...relations: Extract<keyof T, string>[]): Query<T> {
    for (const r of relations) {
      if (!this.source.meta.relations.has(r)) throw new Error(`${this.source.meta.name} has no relation ${r}; declare it with @one or @many`);
    }
    return this.#with({ include: [...new Set([...this.options.include, ...relations])] });
  }

  /** 全部匹配的行。 */
  list(): Promise<T[]> {
    return this.#run("readonly", async (tx) => {
      const rows = (await this.#collect(tx)).map((e) => e.row);
      await this.#include(tx, rows);
      return rows;
    });
  }

  /** 第一行，没有时为 undefined。 */
  async first(): Promise<T | undefined> {
    return (await this.limit(1).list())[0];
  }

  /** 匹配的行数；没有 scan、只有一个范围时由索引直接计数。 */
  count(): Promise<number> {
    return this.#run("readonly", async (tx) => {
      if (!this.#direct() || this.options.scans.length > 0 || this.options.sortBy !== undefined) return (await this.#collect(tx)).length;
      const n = await accessOf(tx).request(this.source.meta, (s) => this.#on(s).count(this.ranges[0] ?? undefined));
      return Math.max(0, Math.min(this.options.limit ?? Infinity, n - this.options.offset));
    });
  }

  /** 匹配的行的主键（复合主键是数组），按查询的顺序。 */
  keys(): Promise<unknown[]> {
    return this.#run("readonly", async (tx) => {
      const meta = this.source.meta;
      if (this.#fast()) return (await accessOf(tx).request(meta, (s) => this.#on(s).getAllKeys(this.ranges[0], this.options.limit))).map((k) => decodeKey(meta, k));
      return (await this.#collect(tx)).map((e) => decodeKey(meta, e.key));
    });
  }

  /** 删掉匹配的行，返回删了几行。 */
  delete(): Promise<number> {
    return this.#run("readwrite", async (tx) => {
      const meta = this.source.meta;
      const entries = await this.#collect(tx);
      await Promise.all(entries.map((e) => accessOf(tx).request(meta, (s) => s.delete(e.key))));
      if (entries.length > 0) tx.written.add(meta.name);
      return entries.length;
    });
  }

  /** 改写匹配的行：patch 是要覆盖的字段，或原地修改行的函数；不能改主键。返回改了几行。 */
  modify(patch: Partial<T> | ((row: T) => void)): Promise<number> {
    return this.#run("readwrite", async (tx) => {
      const meta = this.source.meta;
      const entries = await this.#collect(tx);
      const records = entries.map((e) => {
        if (typeof patch === "function") patch(e.row);
        else Object.assign(e.row as object, patch);
        const record = toRecord(meta, e.row as object);
        if (keyText(primaryKeyOf(meta, e.row as object)) !== keyText(e.key)) throw new Error(`modify cannot change the primary key of ${meta.name}; delete the row and put a new one`);
        return record;
      });
      await Promise.all(records.map((r) => accessOf(tx).request(meta, (s) => s.put(r))));
      if (records.length > 0) tx.written.add(meta.name);
      return records.length;
    });
  }

  /** 逐行遍历全部匹配的行（先读完再交出，遍历中可以 await 别的工作）。 */
  async *[Symbol.asyncIterator](): AsyncIterator<T> {
    yield* await this.list();
  }

  /** 在覆盖本仓库和要装载的关系仓库的事务里执行。 */
  #run<R>(mode: IDBTransactionMode, work: (tx: Transaction) => Promise<R>): Promise<R> {
    const meta = this.source.meta;
    const targets = this.options.include.map((r) => meta.relations.get(r)!.target());
    return this.source.runner.run([meta.type, ...targets], mode, work);
  }

  /** 只读一个范围、不是多值索引：可以直接用索引的计数和游标。 */
  #direct(): boolean {
    return this.ranges.length === 1 && !this.index.multiEntry;
  }

  /** 还能用一次 getAll 读完：一个范围、正序、不跳行、不在内存里筛选或排序。 */
  #fast(): boolean {
    const o = this.options;
    return this.#direct() && !o.reverse && o.offset === 0 && o.scans.length === 0 && o.sortBy === undefined;
  }

  /** 查询所在的索引或仓库本身。 */
  #on(store: IDBObjectStore): IDBObjectStore | IDBIndex {
    return this.index === this.source.meta.key ? store : store.index(this.index.name);
  }

  /** 读出匹配的行（已还原），按查询的顺序，已经过 scan、sortBy、offset、limit。 */
  async #collect(tx: Transaction): Promise<Entry<T>[]> {
    const meta = this.source.meta;
    const access = accessOf(tx);
    const o = this.options;
    const keep = (row: T) => o.scans.every((p) => p(row));
    if (this.#fast()) {
      const records = await access.request(meta, (s) => this.#on(s).getAll(this.ranges[0], o.limit));
      return records.map((r) => ({ key: keyAt(r, meta.key.keyPath), row: fromRecord<T>(meta, r) }));
    }
    if (this.#direct() && o.sortBy === undefined) {
      const out: Entry<T>[] = [];
      let skip = o.offset;
      await access.cursor(
        meta,
        (s) => this.#on(s).openCursor(this.ranges[0], o.reverse ? "prev" : "next"),
        (c) => {
          const row = fromRecord<T>(meta, c.value);
          if (!keep(row)) return true;
          if (skip > 0) skip--;
          else out.push({ key: c.primaryKey, row });
          return o.limit === undefined || out.length < o.limit;
        },
      );
      return out;
    }
    // 多个范围或多值索引：逐个范围读，按索引顺序连起来，同一行只留第一次出现
    const batches = await Promise.all(this.ranges.map((range) => access.request(meta, (s) => this.#on(s).getAll(range))));
    let records = batches.flat();
    if (o.reverse) records = records.reverse();
    const seen = new Set<string>();
    let entries: Entry<T>[] = [];
    for (const r of records) {
      const key = keyAt(r, meta.key.keyPath);
      const text = keyText(key);
      if (seen.has(text)) continue;
      seen.add(text);
      const row = fromRecord<T>(meta, r);
      if (keep(row)) entries.push({ key, row });
    }
    if (o.sortBy !== undefined) entries = entries.sort((a, b) => o.sortBy!(a.row, b.row));
    return entries.slice(o.offset, o.limit === undefined ? undefined : o.offset + o.limit);
  }

  /** 按 include 装回关系：多对一按目标的主键，一对多按目标上指回来的索引，每个关系一批请求。 */
  async #include(tx: Transaction, rows: T[]): Promise<void> {
    const meta = this.source.meta;
    const access = accessOf(tx);
    for (const name of this.options.include) {
      const relation = meta.relations.get(name)!;
      const target = tx.meta(relation.target());
      if (relation.kind === "one") {
        const keys = new Map<string, IDBValidKey>();
        for (const row of rows) {
          const v = (row as Record<string, unknown>)[relation.field];
          if (v != null) {
            const k = encodeKey(target, target.key, v);
            keys.set(keyText(k), k);
          }
        }
        const found = new Map<string, unknown>();
        await Promise.all(
          [...keys].map(async ([text, k]) => {
            const record = await access.request(target, (s) => s.get(k));
            if (record !== undefined) found.set(text, fromRecord(target, record));
          }),
        );
        for (const row of rows) {
          const v = (row as Record<string, unknown>)[relation.field];
          (row as Record<string, unknown>)[name] = v == null ? undefined : found.get(keyText(encodeKey(target, target.key, v)));
        }
      } else {
        const index = target.indexes.get(relation.field);
        if (index === undefined) throw new Error(`${meta.name}.${name}: ${target.name}.${relation.field} needs @index to load the relation`);
        const parents = new Map<string, IDBValidKey>();
        for (const row of rows) {
          const k = primaryKeyOf(meta, row as object);
          parents.set(keyText(k), k);
        }
        const children = new Map<string, unknown[]>();
        await Promise.all(
          [...parents].map(async ([text, k]) => {
            const records = await access.request(target, (s) => s.index(index.name).getAll(IDBKeyRange.only(k)));
            children.set(text, records.map((r) => fromRecord(target, r)));
          }),
        );
        for (const row of rows) {
          const k = primaryKeyOf(meta, row as object);
          (row as Record<string, unknown>)[name] = children.get(keyText(k)) ?? [];
        }
      }
    }
  }

  #with(changes: Partial<Options<T>>): Query<T> {
    return new Query<T>(this.source, this.index, this.ranges, Object.freeze({ ...this.options, ...changes }));
  }
}

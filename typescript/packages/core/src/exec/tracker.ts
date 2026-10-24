import type { Query } from "../query/query.js";
import { entityMeta, type Table } from "../schema/metadata.js";
import { update, type Update } from "../write/write.js";

/** Tracker 用到的数据库能力，由 Database 提供。 */
export interface TrackerHost {
  list<R>(q: Query<R>): Promise<R[]>;
  one<R>(q: Query<R>): Promise<R>;
  optional<R>(q: Query<R>): Promise<R | undefined>;
  execute(write: Update<unknown>): Promise<number>;
  /** 在一个事务里执行 run；已经在事务里或驱动不支持事务时直接执行。 */
  atomically<R>(run: () => Promise<R>): Promise<R>;
}

/** 一个登记的行：表、行对象和修改前的快照。 */
interface Entry {
  readonly table: Table<unknown>;
  readonly row: object;
  snapshot: object;
}

/**
 * `db.tracking(...)` 的工作单元，与 Java 的 `Tracker` 对应：登记读到的行和它们的快照，范围正常结束时把变了的行按主键写回。
 *
 * ```ts
 * await db.tracking(async (t) => {
 *   const d = await t.one(Doc, from(Doc).where((x) => x.id.eq("D1")));
 *   d.title = "new";
 * }); // UPDATE ... SET TITLE = ?, VERSION = VERSION + 1 WHERE ID = ? AND VERSION = ?，d.version 变成新值
 * ```
 *
 * 同一张表、同一主键只登记一份：再次读到时返回已登记的对象，库里的新值不覆盖范围内的修改。快照是浅拷贝，
 * 改值要换成新值，不要原地改数组或 Uint8Array。不做级联、不自动判断插入或更新；插入和删除仍然显式写，删掉的行用 `forget` 取消登记。
 */
export class Tracker {
  readonly #db: TrackerHost;
  /** 每张表按主键文本登记的行，按登记顺序。 */
  readonly #entries = new Map<Table<unknown>, Map<string, Entry>>();
  /** 行对象到它的登记，用于 forget 和避免重复登记同一个对象。 */
  readonly #keys = new Map<object, readonly [Table<unknown>, string]>();

  /** 由 `db.tracking(...)` 创建。 */
  constructor(db: TrackerHost) {
    this.#db = db;
  }

  /** 执行查询并登记读到的那一行；查询必须恰好返回一行。 */
  async one<T, R extends object>(table: Table<T>, q: Query<R>): Promise<R> {
    return this.track(table, await this.#db.one(q));
  }

  /** 执行查询并登记读到的行（最多一行）。 */
  async optional<T, R extends object>(table: Table<T>, q: Query<R>): Promise<R | undefined> {
    const row = await this.#db.optional(q);
    return row === undefined ? undefined : this.track(table, row);
  }

  /** 执行查询并登记读到的每一行，返回的数组里已登记过的主键换成已登记的对象。 */
  async list<T, R extends object>(table: Table<T>, q: Query<R>): Promise<R[]> {
    return (await this.#db.list(q)).map((row) => this.track(table, row));
  }

  /**
   * 登记 table 的一行并记下快照，返回要使用的对象：这个主键已经登记过时返回先登记的对象，否则返回 row。
   * 行的字段必须都是表的列，带表的全部主键字段（表上有版本列时还要带版本字段），且主键不为空。
   */
  track<T, R extends object>(table: Table<T>, row: R): R {
    if (this.#keys.has(row)) return row;
    // 按写回时的规则核对：字段都是表的列、带主键和版本。
    update(table).byId(row as never);
    const key = keyOf(table, row);
    let rows = this.#entries.get(table);
    if (rows === undefined) this.#entries.set(table, (rows = new Map()));
    const known = rows.get(key);
    if (known !== undefined) return known.row as R;
    rows.set(key, { table, row, snapshot: copy(row) });
    this.#keys.set(row, [table, key]);
    return row;
  }

  /** 取消登记一行（例如已经删除了它），范围结束时不再比较和写回。 */
  forget(row: object): void {
    const key = this.#keys.get(row);
    if (key === undefined) return;
    this.#keys.delete(row);
    this.#entries.get(key[0])?.delete(key[1]);
  }

  /**
   * 把变了的行写回：每张表一条 `update(表).byId(行).diffFrom(快照)`，全部在一个事务里执行。
   * 成功后变了的行的版本字段加一并刷新快照；失败时都不动。由 `db.tracking(...)` 在最外层范围结束时调用。
   */
  async flush(): Promise<void> {
    const updates: Update<unknown>[] = [];
    for (const [table, rows] of this.#entries) {
      if (rows.size === 0) continue;
      const entries = [...rows.values()];
      const u = update(table).byId(...(entries.map((e) => e.row) as never[])).diffFrom(...(entries.map((e) => e.snapshot) as never[]));
      if (u.changedRows.length > 0) updates.push(u as Update<unknown>);
    }
    if (updates.length === 0) return;
    await this.#db.atomically(async () => {
      for (const u of updates) await this.#db.execute(u);
    });
    for (const u of updates) {
      for (const row of u.changedRows) {
        const [table, key] = this.#keys.get(row)!;
        const entry = this.#entries.get(table)!.get(key)!;
        bumpVersion(table, row);
        entry.snapshot = copy(row);
      }
    }
  }
}

/** 行的主键文本，按表的 `@id` 顺序；表没有主键或主键为空时报错。 */
function keyOf(table: Table<unknown>, row: object): string {
  const ids = entityMeta(table).writes.ids;
  return ids
    .map((id) => {
      const value = (row as Record<string, unknown>)[id];
      if (value == null) throw new Error(`${table.name}.${id} is empty; tracking needs the key`);
      return `${typeof value}:${String(value)}`;
    })
    .join("\u0000");
}

/** 写成功后把行的版本字段加一，与库里的 `VERSION = VERSION + 1` 一致；表上没有版本列时什么也不做。 */
function bumpVersion(table: Table<unknown>, row: object): void {
  const version = entityMeta(table).writes.version;
  if (version === undefined) return;
  const r = row as Record<string, unknown>;
  const v = r[version];
  if (typeof v === "number") r[version] = v + 1;
  else if (typeof v === "bigint") r[version] = v + 1n;
  else throw new Error(`${table.name}.${version} must be a number or bigint version`);
}

/** 行的浅拷贝，保留原型（类的实例拷贝后仍是同一个类）。 */
function copy<R extends object>(row: R): R {
  return Object.assign(Object.create(Object.getPrototypeOf(row) as object | null) as R, row);
}

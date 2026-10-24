import { openLocal, TransactionClosedError, type LocalDatabase } from "@zerodep-dev/idb";
import { Cell, Comment, Counter, Note, note, User } from "../fixtures/local.js";

/**
 * 浏览器里 IndexedDB 实测用的页面脚本：把操作挂在 `globalThis.idbTest` 上，由 `idb.browser.test.ts` 在一个或两个标签页里调用。
 * 覆盖 fake-indexeddb 模拟不了的部分：真实的自动提交时机、标签页之间的通知与升级、存储空间的估计。
 */
const STORES = [User, Note, Comment, Counter, Cell];

/** 本页的实时查询看到的结果、收到的版本变更和被挡住的次数。 */
const state = { seen: [] as string[][], versionChanged: 0, blocked: 0, db: undefined as LocalDatabase | undefined };

const api = {
  /** 事务里 await 了 fetch：之后再用 tx 报 TransactionClosedError，之前的写入已经提交。 */
  async autocommit(name: string): Promise<{ error: string; keys: unknown[] }> {
    const db = await openLocal(name, { version: 1, stores: STORES });
    let error = "";
    try {
      await db.transaction([Note], "readwrite", async (tx) => {
        await tx.put(Note, note("n1"));
        await fetch("/");
        await tx.put(Note, note("n2"));
      });
    } catch (e) {
      error = e instanceof TransactionClosedError ? "TransactionClosedError" : String(e);
    }
    const keys = await db.from(Note).all().keys();
    db.close();
    return { error, keys };
  },

  /** 打开库并在本页登记一个实时查询（全部便签的编号）。 */
  async watch(name: string): Promise<void> {
    state.db = await openLocal(name, { version: 1, stores: STORES, onVersionChange: () => state.versionChanged++ });
    state.db.from(Note).all().live((rows) => state.seen.push(rows.map((n) => n.id)));
  },

  /** 另开一个连接写一条便签。 */
  async write(name: string, id: string): Promise<void> {
    const db = await openLocal(name, { version: 1, stores: STORES });
    await db.put(Note, note(id));
    db.close();
  },

  /** 把库升级到版本 2，返回升级后的版本。 */
  async upgrade(name: string): Promise<number> {
    const db = await openLocal(name, { version: 2, stores: STORES, onBlocked: () => state.blocked++ });
    const version = db.version;
    db.close();
    return version;
  },

  /** 本页的状态。 */
  state: () => ({ seen: state.seen, versionChanged: state.versionChanged, blocked: state.blocked }),

  /** 存储空间的估计是不是数字。 */
  async storage(name: string): Promise<{ usage: string; quota: string }> {
    const db = await openLocal(name, { version: 1, stores: STORES });
    const e = await db.estimate();
    db.close();
    return { usage: typeof e?.usage, quota: typeof e?.quota };
  },
};

/** 供 page.evaluate 调用。 */
export type IdbTest = typeof api;

(globalThis as unknown as { idbTest: IdbTest }).idbTest = api;

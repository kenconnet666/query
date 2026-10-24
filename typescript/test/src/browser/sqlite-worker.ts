import sqlite3InitModule from "@sqlite.org/sqlite-wasm";
import { count, from, insertInto, migration, SqliteDatabase, wasmDriver } from "@zerodep-dev/query-sqlite";
import { CREATE_TASK } from "../fixtures/schemas.js";
import { Doc, Note } from "../fixtures/writes.js";

/**
 * 浏览器 Worker 里的 SQLite WASM 加本库，按 0009 推荐的方式：opfs-sahpool 持久化。由 `sqlite-wasm.browser.test.ts` 打开两次：
 * 第一次（`?phase=write`）建表并经由事务、追踪、迁移写入；第二次（`?phase=read`）在新的 Worker 里读回，验证数据留在 OPFS 里。
 * 结果用 postMessage 发给页面。
 */
const scope = globalThis as unknown as { location: { href: string }; postMessage(message: unknown): void };

const phase = new URL(scope.location.href).searchParams.get("phase");
const sqlite3 = await sqlite3InitModule();
const pool = await sqlite3.installOpfsSAHPoolVfs({});
const raw = new pool.OpfsSAHPoolDb("/main.db");
raw.exec("ATTACH DATABASE 'file:/query_it.db?vfs=opfs-sahpool' AS QUERY_IT");
const db = new SqliteDatabase(wasmDriver(raw), { warn: () => undefined });

const note = (id: string) => ({ id, tenant: "T1", title: null, qty: 1, status: "OPEN" });
const notes = async () => Number(await db.one(from(Note).select(() => count())));
const docRow = () => from(Doc).where((x) => x.id.eq("D1")).select((x) => ({ id: x.id, title: x.title, version: x.version }));
const migrator = db.migrator([CREATE_TASK, migration("2", "seed", (steps) => steps.sql("INSERT INTO QUERY_IT.M_TASK (ID, TITLE) VALUES ('T1', 'x')"))]);

try {
  if (phase === "write") {
    await (await db.schema().plan(Note, Doc)).apply();
    await db
      .transaction(async (tx) => {
        await tx.execute(insertInto(Note).values(note("N1")));
        throw new Error("rollback");
      })
      .catch(() => undefined);
    const afterRollback = await notes();
    await db.transaction(async (tx) => {
      await tx.execute(insertInto(Note).values(note("N1"), note("N2")));
      await tx.execute(insertInto(Doc).values({ id: "D1", title: "old", version: 1, source: null }));
    });
    await db.tracking(async (t) => {
      (await t.one(Doc, docRow())).title = "new";
    });
    const migrated = await migrator.migrateToLatest();
    scope.postMessage({ afterRollback, migrated });
  } else {
    const doc = await db.one(docRow());
    await migrator.verify();
    scope.postMessage({ notes: await notes(), doc: [doc.title, doc.version], tasks: Number((await db.sql("SELECT COUNT(*) AS n FROM QUERY_IT.M_TASK").one()).n) });
  }
} catch (e) {
  scope.postMessage({ error: e instanceof Error ? `${e.message}\n${e.stack}` : String(e) });
} finally {
  raw.close();
}

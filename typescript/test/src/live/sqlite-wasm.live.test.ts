import sqlite3InitModule from "@sqlite.org/sqlite-wasm";
import { wasmDriver } from "@zerodep-dev/query-sqlite";
import { liveScenarios, sqliteTarget } from "./live.js";

/** SQLite WASM（官方构建）上的共享场景：Node 里只支持内存库，QUERY_IT 也 ATTACH 成内存库。浏览器里的持久化见 0009。 */
liveScenarios({
  ...sqliteTarget,
  name: "SQLITE_WASM",
  async connect() {
    const sqlite3 = await sqlite3InitModule();
    const db = new sqlite3.oo1.DB(":memory:", "c");
    db.exec("ATTACH DATABASE ':memory:' AS QUERY_IT; PRAGMA foreign_keys = ON");
    return { driver: wasmDriver(db), close: async () => db.close() };
  },
});

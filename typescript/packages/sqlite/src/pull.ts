#!/usr/bin/env node
/**
 * `query-pull-sqlite [query-pull.json] [--only=T1,T2]`：从 SQLite 文件读表结构，生成只有列的基类，配置见 {@link PullConfig}。
 * connection 写 `{ "path": "app.db" }`，可以再写 `"attach": { "APP": "app2.db" }` 把别的库按 schema 名 ATTACH 进来；用 Node 内置的 node:sqlite。
 */
import { runPull, type PullConfig } from "@zerodep-dev/query-core";
import { database } from "./index.js";

await runPull("password", async (connection) => {
  const { DatabaseSync } = await import("node:sqlite");
  const db = new DatabaseSync(String(connection.path), { readOnly: true });
  for (const [schema, path] of Object.entries((connection.attach ?? {}) as Record<string, string>)) {
    db.exec(`ATTACH DATABASE '${path.replaceAll("'", "''")}' AS "${schema.replaceAll('"', '""')}"`);
  }
  return { db: database(db), close: async () => db.close() };
});

export type { PullConfig };

#!/usr/bin/env node
/**
 * `query-pull-postgres [query-pull.json] [--only=T1,T2]`：从 PostgreSQL 读表结构，生成只有列的基类，配置见 {@link PullConfig}。
 * connection 是 pg 的 PoolConfig（host、port、database、user），密码来自 passwordEnv 指定的环境变量。需要安装 pg。
 */
import { runPull, type PullConfig } from "@zerodep-dev/query-core";
import { database } from "./index.js";

await runPull("password", async (connection) => {
  const pg = (await import("pg")).default;
  const pool = new pg.Pool({ ...connection, max: 1 });
  return { db: database(pool), close: () => pool.end() };
});

export type { PullConfig };

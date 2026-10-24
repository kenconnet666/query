#!/usr/bin/env node
/**
 * `query-pull-mysql [query-pull.json] [--only=T1,T2]`：从 MySQL 读表结构，生成只有列的基类，配置见 {@link PullConfig}。
 * connection 是 mysql2 的连接参数（host、port、database、user），密码来自 passwordEnv 指定的环境变量。需要安装 mysql2。
 */
import { runPull, type PullConfig } from "@zerodep-dev/query-core";
import { database } from "./index.js";

await runPull("password", async (connection) => {
  const mysql = (await import("mysql2/promise")).default;
  const pool = mysql.createPool({ ...connection, connectionLimit: 1 });
  return { db: database(pool), close: () => pool.end() };
});

export type { PullConfig };

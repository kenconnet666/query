#!/usr/bin/env node
/**
 * `query-pull-hana [query-pull.json] [--only=T1,T2]`：从 SAP HANA 读表结构，生成只有列的基类，配置见 {@link PullConfig}。
 * connection 是 @sap/hana-client 的连接参数（serverNode、uid），密码来自 passwordEnv 指定的环境变量。需要安装 @sap/hana-client。
 */
import { runPull, type PullConfig } from "@zerodep-dev/query-core";
import { database } from "./index.js";

await runPull("pwd", async (connection) => {
  const hana = (await import("@sap/hana-client")).default;
  const pool = hana.createPool(connection, { maxConnectedOrPooled: 1 });
  return { db: database(pool), close: async () => pool.clear() };
});

export type { PullConfig };

import { mkdirSync, rmSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";
import hana from "@sap/hana-client";
import sqlite3InitModule from "@sqlite.org/sqlite-wasm";
import mysql from "mysql2/promise";
import pg from "pg";
import type { Database, DatabaseOptions, Driver } from "@zerodep-dev/query-core";
import { hanaDriver, HanaDatabase } from "@zerodep-dev/query-hana";
import { mysqlDriver, MysqlDatabase } from "@zerodep-dev/query-mysql";
import { PolardbDatabase } from "@zerodep-dev/query-polardb";
import { pgDriver, PostgresDatabase } from "@zerodep-dev/query-postgres";
import { nodeSqliteDriver, SqliteDatabase, wasmDriver } from "@zerodep-dev/query-sqlite";

/** 实库测试覆盖的库名，也是 QUERY_DATABASES 里写的名字。 */
export type DbName = "hana" | "postgres" | "mysql" | "polardb" | "sqlite" | "sqlite-wasm";

/** 从 JDBC 形式的 QUERY_{前缀}_URL 解析出的连接设置，与 Java 读同一组变量。 */
export interface LiveConfig {
  readonly host: string;
  readonly port: number;
  readonly database: string | undefined;
  readonly user: string;
  readonly password: string;
}

/**
 * 一个库的实库测试目标：怎么连、用哪个方言的数据库类、手写 SQL 有哪些不同，都只写在这里，测试里不再出现。
 * 与 Java 的 support/Db 一一对应；TS 另有 SQLite WASM。
 */
export interface Target {
  readonly name: DbName;
  /** 测试报告里的名字，例如 `[MySQL] 分页`。 */
  readonly title: string;
  /** 连接变量的前缀（QUERY_{前缀}_URL 等）；本地库（SQLite）为 undefined，总是运行。 */
  readonly variables?: string;
  /** 建连接池或打开库；config 对本地库是占位值。 */
  connect(config: LiveConfig): Promise<{ driver: Driver; close(): Promise<void> }>;
  /** 用这个库的方言建数据库实例。 */
  database(driver: Driver, options?: DatabaseOptions): Database;
  /** 建测试 schema 的手写语句；"-" 开头的允许失败（已经存在）。SQLite 的 QUERY_IT 由打开库时 ATTACH。 */
  readonly createSchema: readonly string[];
  /** 建好 schema 之后、样例数据之前执行的手写语句。 */
  readonly setup: readonly string[];
  /** 一条要运行好几秒才返回的手写语句，超时测试用；驱动不能中途取消语句时（SQLite）为 undefined。 */
  readonly slowStatement?: string;
}

/** mysql2 连接池：MySQL 和 PolarDB 共用，utf8mb4 与 Java URL 的默认值一致。 */
async function mysqlPool(config: LiveConfig) {
  const pool = mysql.createPool({
    host: config.host,
    port: config.port,
    ...(config.database === undefined ? {} : { database: config.database }),
    user: config.user,
    password: config.password,
    connectionLimit: 4,
    charset: "utf8mb4",
  });
  return { driver: mysqlDriver(pool), close: () => pool.end() };
}

/** MySQL 的 SLEEP() 被 max_execution_time 打断时返回 1 而不报错，所以改成数十亿行的计数。 */
const MYSQL_SLOW = `SELECT COUNT(*) FROM ${Array.from({ length: 3 }, (_, i) => `(SELECT 1 FROM information_schema.columns LIMIT 1000) t${i}`).join(", ")}`;

/** 全部目标，按 Java 的顺序。 */
export const TARGETS: readonly Target[] = [
  {
    name: "hana",
    title: "HANA",
    variables: "HANA",
    async connect(config) {
      const pool = hana.createPool({ serverNode: `${config.host}:${config.port}`, uid: config.user, pwd: config.password }, { maxConnectedOrPooled: 4 });
      return { driver: hanaDriver(pool), close: async () => pool.clear() };
    },
    database: (driver, options) => new HanaDatabase(driver, options),
    createSchema: ['-CREATE SCHEMA "QUERY_IT"'],
    // HANA 的 SQL 没有 sleep，SQLScript 自带的 SQLSCRIPT_SYNC 库有。
    setup: [
      "-DROP PROCEDURE QUERY_IT.SLOW_ROW",
      "CREATE PROCEDURE QUERY_IT.SLOW_ROW(IN SECONDS INTEGER) READS SQL DATA AS BEGIN USING SQLSCRIPT_SYNC AS SYNCLIB; " +
        "CALL SYNCLIB:SLEEP_SECONDS(:SECONDS); SELECT 1 AS X FROM DUMMY; END",
    ],
    slowStatement: "CALL QUERY_IT.SLOW_ROW(3)",
  },
  {
    name: "postgres",
    title: "PostgreSQL",
    variables: "PG",
    async connect(config) {
      const pool = new pg.Pool({ host: config.host, port: config.port, database: config.database, user: config.user, password: config.password, max: 4 });
      return { driver: pgDriver(pool), close: () => pool.end() };
    },
    database: (driver, options) => new PostgresDatabase(driver, options),
    createSchema: ['-CREATE SCHEMA "QUERY_IT"'],
    setup: [],
    slowStatement: "SELECT pg_sleep(3)",
  },
  {
    name: "mysql",
    title: "MySQL",
    variables: "MYSQL",
    connect: mysqlPool,
    database: (driver, options) => new MysqlDatabase(driver, options),
    createSchema: ["CREATE SCHEMA IF NOT EXISTS `QUERY_IT`"],
    setup: [],
    slowStatement: MYSQL_SLOW,
  },
  {
    name: "polardb",
    title: "PolarDB",
    variables: "POLARDB",
    connect: mysqlPool,
    database: (driver, options) => new PolardbDatabase(driver, options),
    createSchema: ["CREATE SCHEMA IF NOT EXISTS `QUERY_IT`"],
    setup: [],
    slowStatement: MYSQL_SLOW,
  },
  {
    name: "sqlite",
    title: "SQLite",
    // 库是 test/dist/sqlite-it/<进程号> 下的两个新文件：每个测试文件在自己的进程里跑，互不干扰。
    async connect() {
      const dir = fileURLToPath(new URL(`../../sqlite-it/${process.pid}/`, import.meta.url));
      rmSync(dir, { recursive: true, force: true });
      mkdirSync(dir, { recursive: true });
      const db = new DatabaseSync(dir + "main.db");
      db.exec(`ATTACH DATABASE '${(dir + "query_it.db").replaceAll("'", "''")}' AS QUERY_IT; PRAGMA foreign_keys = ON`);
      return {
        driver: nodeSqliteDriver(db),
        close: async () => {
          db.close();
          rmSync(dir, { recursive: true, force: true });
        },
      };
    },
    database: (driver, options) => new SqliteDatabase(driver, options),
    createSchema: [],
    setup: [],
  },
  {
    name: "sqlite-wasm",
    title: "SQLite WASM",
    // 官方的 WASM 构建在 Node 里只支持内存库，QUERY_IT 也 ATTACH 成内存库；浏览器里的持久化见 0009。
    async connect() {
      const sqlite3 = await sqlite3InitModule();
      const db = new sqlite3.oo1.DB(":memory:", "c");
      db.exec("ATTACH DATABASE ':memory:' AS QUERY_IT; PRAGMA foreign_keys = ON");
      return { driver: wasmDriver(db), close: async () => db.close() };
    },
    database: (driver, options) => new SqliteDatabase(driver, options),
    createSchema: [],
    setup: [],
  },
];

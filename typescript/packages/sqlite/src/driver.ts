import type { DatabaseSync } from "node:sqlite";
import type { Database as WasmDatabase } from "@sqlite.org/sqlite-wasm";
import type { Driver, DriverResult } from "@zerodep-dev/query-core";
import { SqliteDialect } from "./dialect.js";

/** 在一个连接上同步执行一条语句：返回列名、各行（数组）和改到的行数。 */
type Run = (sql: string, parameters: readonly unknown[]) => DriverResult;

/**
 * 单连接的驱动：语句按到达顺序排队执行，事务期间别的调用等事务结束，避免两个异步流程的语句交错进同一个事务。
 * 事务用 `BEGIN IMMEDIATE`（一开始就拿写锁，避免读锁升级时的死锁）。node:sqlite 与 SQLite WASM 都不能中途取消语句，
 * 所以 `timeoutMs` 被忽略。
 */
function serialDriver(run: Run): Driver {
  let tail: Promise<unknown> = Promise.resolve();
  /** 排在前面的工作都结束后再执行 work。 */
  const queued = <R>(work: () => Promise<R>): Promise<R> => {
    const next = tail.then(work, work);
    tail = next.catch(() => undefined);
    return next;
  };
  /** 已经在事务里（拿到了队列）的驱动：直接执行，事务嵌套时加入外层。 */
  const inTransaction: Driver = {
    query: async (sql, parameters) => run(sql, parameters),
    transaction: (work) => work(inTransaction),
  };
  return {
    dialect: SqliteDialect.instance,
    query: (sql, parameters) => queued(async () => run(sql, parameters)),
    transaction: (work) =>
      queued(async () => {
        run("BEGIN IMMEDIATE", []);
        try {
          const result = await work(inTransaction);
          run("COMMIT", []);
          return result;
        } catch (e) {
          try {
            run("ROLLBACK", []);
          } catch {
            // 语句失败时 SQLite 可能已经自动回滚了
          }
          throw e;
        }
      }),
  };
}

/** 绑定值转成 SQLite 接受的类型：布尔写成 1 / 0，undefined 写成 NULL。 */
function parameter(value: unknown): unknown {
  if (typeof value === "boolean") return value ? 1 : 0;
  return value === undefined ? null : value;
}

/**
 * 适配 Node 内置的 `node:sqlite`（`new DatabaseSync(path)`），用于 Node 服务端和测试。整数按 bigint 读（超过 2^53 也不丢精度），
 * 由列类型再转成 number。要 ATTACH 别的库、打开外键检查时在交给驱动之前对 db 执行。
 */
export function nodeSqliteDriver(db: DatabaseSync): Driver {
  const statements = new Map<string, ReturnType<DatabaseSync["prepare"]>>();
  return serialDriver((sql, parameters) => {
    let stmt = statements.get(sql);
    if (stmt === undefined) {
      stmt = db.prepare(sql);
      stmt.setReadBigInts(true);
      stmt.setReturnArrays(true);
      if (statements.size >= 200) statements.delete(statements.keys().next().value!);
      statements.set(sql, stmt);
    }
    const values = parameters.map(parameter) as never[];
    const columns = stmt.columns().map((c) => c.name);
    if (columns.length === 0) {
      const result = stmt.run(...values);
      return { columns, rows: [], affected: Number(result.changes) };
    }
    const rows = stmt.all(...values) as unknown as unknown[][];
    return { columns, rows, affected: rows.length }; // 有结果列的写语句（RETURNING）每改一行返回一行
  });
}

/**
 * 适配 `@sqlite.org/sqlite-wasm` 的 oo1 `DB`（`new sqlite3.oo1.DB(...)`，或 Worker 里 `new sqlite3.oo1.OpfsSAHPoolDb(...)`）。
 * 浏览器里推荐把本库和 sqlite-wasm 一起放进一个 Worker，用 `opfs-sahpool` 持久化；页面与 Worker 之间的消息由使用方定。
 */
export function wasmDriver(db: WasmDatabase): Driver {
  return serialDriver((sql, parameters) => {
    const stmt = db.prepare(sql);
    try {
      if (parameters.length > 0) stmt.bind(parameters.map(parameter) as never);
      const columns = stmt.columnCount === 0 ? [] : stmt.getColumnNames();
      const rows: unknown[][] = [];
      while (stmt.step()) rows.push(stmt.get([]));
      return { columns, rows, affected: Number(db.changes()) };
    } finally {
      stmt.finalize();
    }
  });
}

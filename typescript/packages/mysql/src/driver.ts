import type { Connection, Pool, PoolConnection } from "mysql2/promise";
import type { Connection as CoreConnection, FieldPacket, TypeCast } from "mysql2";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult, ExecuteOptions } from "@zerodep-dev/query-core";

// DECIMAL 读成精确文本，BIGINT 读成文本，日期和时间读成文本：不经过 Date 或会话时区。
// decimalNumbers 是连接池的选项，所以这里显式读取 DECIMAL。
/** DECIMAL 以文本返回，其余交给默认解析。 */
const typeCast: TypeCast = (field, next) => (field.type === "DECIMAL" || field.type === "NEWDECIMAL" ? field.string() : next());
const OPTIONS = { rowsAsArray: true, dateStrings: true, supportBigNumbers: true, bigNumberStrings: true, typeCast } as const;

/**
 * 适配 `mysql2/promise` 的连接池或连接。语句作为服务端预编译语句执行。
 * 流式读取要从连接池借出连接；只有单个连接时会读完全部行。
 * 超时通过在语句前后设置会话的 `max_execution_time` 实现，服务端会中断运行过久的 SELECT；
 * 连接池会借出一个连接来做这件事，无法还原该设置的连接会被销毁。
 */
export function mysqlDriver(source: Pool | PoolConnection | Connection): Driver {
  const run = async (connection: Pool | PoolConnection | Connection, sql: string, parameters: readonly unknown[]): Promise<DriverResult> => {
    const [rows, fields] = (await connection.execute({ sql, ...OPTIONS }, parameters.map(parameter) as never)) as unknown as [unknown, FieldPacket[] | undefined];
    // 没有结果集的语句（DDL、DML）返回的是一个头信息对象而不是行。
    return { columns: (fields ?? []).map((f) => f.name), rows: Array.isArray(rows) ? (rows as unknown[][]) : [] };
  };
  const driver: Driver = {
    async query(sql, parameters, options): Promise<DriverResult> {
      const ms = milliseconds(options);
      if (ms === undefined) return run(source, sql, parameters);
      const lent = "getConnection" in source ? await source.getConnection() : undefined;
      const connection = lent ?? (source as PoolConnection | Connection);
      let reset = false;
      try {
        await connection.query(`SET SESSION max_execution_time = ${ms}`);
        return await run(connection, sql, parameters);
      } finally {
        reset = await connection.query("SET SESSION max_execution_time = DEFAULT").then(() => true, () => false);
        if (lent !== undefined && reset) lent.release();
        else if (lent !== undefined) lent.destroy();
      }
    },
  };
  if (!("getConnection" in source)) return driver;
  const pool = source;
  return {
    ...driver,
    async *stream(sql, parameters, options) {
      const connection = await pool.getConnection();
      const ms = milliseconds(options);
      let reset = true;
      try {
        if (ms !== undefined) await connection.query(`SET SESSION max_execution_time = ${ms}`);
        // 只有回调式 API 支持流式读取；它走文本协议，由 mysql2 把参数值转义后拼进语句。
        const core = connection.connection as unknown as CoreConnection;
        const rows = core.query({ sql, ...OPTIONS }, parameters.map((v) => (typeof v === "bigint" ? v.toString() : parameter(v)))).stream();
        for await (const row of rows) yield row as unknown[];
      } finally {
        if (ms !== undefined) reset = await connection.query("SET SESSION max_execution_time = DEFAULT").then(() => true, () => false);
        if (reset) connection.release();
        else connection.destroy();
      }
    },
  };
}

/** 超时毫秒数向上取整成整数，供拼进 SET 语句；未设置超时时为 undefined。 */
function milliseconds(options: ExecuteOptions | undefined): number | undefined {
  return options?.timeoutMs === undefined ? undefined : Math.ceil(options.timeoutMs);
}

/** 把非 Buffer 的字节数组转成 Buffer，其余原样。 */
function parameter(value: unknown): unknown {
  if (value instanceof Uint8Array && !Buffer.isBuffer(value)) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  return value;
}

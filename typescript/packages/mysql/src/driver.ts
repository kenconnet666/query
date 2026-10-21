import type { Connection, Pool, PoolConnection } from "mysql2/promise";
import type { Connection as CoreConnection, FieldPacket, TypeCast } from "mysql2";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult, ExecuteOptions } from "@query/core";

// DECIMAL as exact text, BIGINT as text, dates and times as text: nothing goes through Date or
// the session time zone. decimalNumbers is a pool option, so DECIMAL is read here explicitly.
const typeCast: TypeCast = (field, next) => (field.type === "DECIMAL" || field.type === "NEWDECIMAL" ? field.string() : next());
const OPTIONS = { rowsAsArray: true, dateStrings: true, supportBigNumbers: true, bigNumberStrings: true, typeCast } as const;

/**
 * Adapts a `mysql2/promise` pool or connection. Statements run as server-side prepared
 * statements. Streaming borrows a connection from a pool; with a single connection it reads all rows.
 * A timeout sets the session's `max_execution_time` around the statement, so the server interrupts
 * a SELECT that runs longer; a pool lends a connection for that, and one that cannot reset it is destroyed.
 */
export function mysqlDriver(source: Pool | PoolConnection | Connection): Driver {
  const run = async (connection: Pool | PoolConnection | Connection, sql: string, parameters: readonly unknown[]): Promise<DriverResult> => {
    const [rows, fields] = (await connection.execute({ sql, ...OPTIONS }, parameters.map(parameter) as never)) as unknown as [unknown, FieldPacket[] | undefined];
    // Statements without a result set (DDL, DML) return a header object instead of rows.
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
        // Only the callback API streams; it uses the text protocol, where mysql2 escapes the values into the statement.
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

function milliseconds(options: ExecuteOptions | undefined): number | undefined {
  return options?.timeoutMs === undefined ? undefined : Math.ceil(options.timeoutMs);
}

function parameter(value: unknown): unknown {
  if (value instanceof Uint8Array && !Buffer.isBuffer(value)) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  return value;
}

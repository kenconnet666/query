import type { Connection, Pool, PoolConnection } from "mysql2/promise";
import type { Connection as CoreConnection, FieldPacket, TypeCast } from "mysql2";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult } from "@query/core";

// DECIMAL as exact text, BIGINT as text, dates and times as text: nothing goes through Date or
// the session time zone. decimalNumbers is a pool option, so DECIMAL is read here explicitly.
const typeCast: TypeCast = (field, next) => (field.type === "DECIMAL" || field.type === "NEWDECIMAL" ? field.string() : next());
const OPTIONS = { rowsAsArray: true, dateStrings: true, supportBigNumbers: true, bigNumberStrings: true, typeCast } as const;

/**
 * Adapts a `mysql2/promise` pool or connection. Statements run as server-side prepared
 * statements. Streaming borrows a connection from a pool; with a single connection it reads all rows.
 */
export function mysqlDriver(source: Pool | PoolConnection | Connection): Driver {
  const driver: Driver = {
    async query(sql, parameters): Promise<DriverResult> {
      const [rows, fields] = (await source.execute({ sql, ...OPTIONS }, parameters.map(parameter) as never)) as unknown as [unknown, FieldPacket[] | undefined];
      // Statements without a result set (DDL, DML) return a header object instead of rows.
      return { columns: (fields ?? []).map((f) => f.name), rows: Array.isArray(rows) ? (rows as unknown[][]) : [] };
    },
  };
  if (!("getConnection" in source)) return driver;
  const pool = source;
  return {
    ...driver,
    async *stream(sql, parameters) {
      const connection = await pool.getConnection();
      try {
        // Only the callback API streams; it uses the text protocol, where mysql2 escapes the values into the statement.
        const core = connection.connection as unknown as CoreConnection;
        const rows = core.query({ sql, ...OPTIONS }, parameters.map((v) => (typeof v === "bigint" ? v.toString() : parameter(v)))).stream();
        for await (const row of rows) yield row as unknown[];
      } finally {
        connection.release();
      }
    },
  };
}

function parameter(value: unknown): unknown {
  if (value instanceof Uint8Array && !Buffer.isBuffer(value)) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  return value;
}

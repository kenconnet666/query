import type { Connection, ConnectionPool, HanaParameterType, ResultSet, Statement } from "@sap/hana-client";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult } from "@query/core";

/**
 * Adapts an `@sap/hana-client` pool or connection. A pool lends one connection per statement and
 * takes it back with `disconnect()`; a connection (for example one inside a transaction) is used
 * as it is and stays open. The client returns DECIMAL, large BIGINT, DATE and TIMESTAMP as strings.
 */
export function hanaDriver(source: ConnectionPool | Connection): Driver {
  const pooled = "getConnection" in source;
  const acquire = (): Promise<Connection> =>
    pooled
      ? new Promise((resolve, reject) => (source as ConnectionPool).getConnection((e: Error, c?: Connection) => (e ? reject(e) : resolve(c!))))
      : Promise.resolve(source as Connection);
  const release = (c: Connection) => {
    if (pooled) c.disconnect();
  };
  return {
    async query(sql, parameters): Promise<DriverResult> {
      const connection = await acquire();
      try {
        const statement = await prepare(connection, sql);
        try {
          // Statements without a result set (DDL, DML) report a row count instead of rows.
          const rows = await new Promise<unknown[][]>((resolve, reject) =>
            statement.exec<unknown>(parameters.map(parameter), { rowsAsArray: true }, (e: Error, r?: unknown) =>
              e ? reject(e) : resolve(Array.isArray(r) ? r : []),
            ),
          );
          return { columns: statement.getColumnInfo().map((c) => c.columnName), rows };
        } finally {
          statement.drop();
        }
      } finally {
        release(connection);
      }
    },

    async *stream(sql, parameters) {
      const connection = await acquire();
      try {
        const statement = await prepare(connection, sql);
        try {
          const rs = await new Promise<ResultSet>((resolve, reject) =>
            statement.execQuery(parameters.map(parameter), (e: Error, r?: ResultSet) => (e ? reject(e) : resolve(r!))),
          );
          try {
            rs.setAsArrayDefault(true);
            while (await new Promise<boolean>((resolve, reject) => rs.next((e: Error, more?: boolean) => (e ? reject(e) : resolve(more === true))))) {
              yield rs.getValues<unknown[]>();
            }
          } finally {
            rs.close();
          }
        } finally {
          statement.drop();
        }
      } finally {
        release(connection);
      }
    },
  };
}

function prepare(connection: Connection, sql: string): Promise<Statement> {
  return new Promise((resolve, reject) => connection.prepare(sql, (e: Error, s?: Statement) => (e ? reject(e) : resolve(s!))));
}

function parameter(value: unknown): HanaParameterType {
  if (value === null || typeof value === "string" || typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "bigint") return value.toString();
  if (value instanceof Uint8Array) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  throw new TypeError(`HANA parameters are text, numbers, booleans or bytes, not ${typeof value}`);
}

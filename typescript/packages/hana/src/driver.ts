import type { Connection, ConnectionPool, HanaParameterType, ResultSet, Statement } from "@sap/hana-client";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult, ExecuteOptions } from "@zerodep-dev/query-core";

/**
 * 适配 `@sap/hana-client` 的连接池或连接。连接池每条语句借出一个连接，用 `disconnect()` 归还；
 * 单个连接（例如事务中的连接）原样使用，并保持打开。客户端把 DECIMAL、较大的 BIGINT、DATE 和
 * TIMESTAMP 以字符串返回。超时交给 `Statement.setTimeout`，单位是整秒并向上取整，由服务端中止语句；
 * `Statement.exec` 的 `queryTimeout` 选项在客户端 2.30 中被静默忽略。
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
    async query(sql, parameters, options): Promise<DriverResult> {
      const connection = await acquire();
      try {
        const statement = await prepare(connection, sql, options);
        try {
          // 没有结果集的语句（DDL、DML）返回的是影响行数而不是行。
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

    async *stream(sql, parameters, options) {
      const connection = await acquire();
      try {
        const statement = await prepare(connection, sql, options);
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

/** 预编译语句，并按选项设置超时。 */
async function prepare(connection: Connection, sql: string, options: ExecuteOptions | undefined): Promise<Statement> {
  const statement = await new Promise<Statement>((resolve, reject) => connection.prepare(sql, (e: Error, s?: Statement) => (e ? reject(e) : resolve(s!))));
  if (options?.timeoutMs !== undefined) statement.setTimeout(Math.ceil(options.timeoutMs / 1000));
  return statement;
}

/** 把绑定值转成 HANA 客户端接受的类型：bigint 转文本，字节转 Buffer。 */
function parameter(value: unknown): HanaParameterType {
  if (value === null || typeof value === "string" || typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "bigint") return value.toString();
  if (value instanceof Uint8Array) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  throw new TypeError(`HANA parameters are text, numbers, booleans or bytes, not ${typeof value}`);
}

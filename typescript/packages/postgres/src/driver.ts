import type { Client, CustomTypesConfig, Pool, PoolClient, QueryArrayResult } from "pg";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult, ExecuteOptions } from "@zerodep-dev/query-core";
import { PostgresDialect } from "./dialect.js";

// 布尔值和较小的数字照常解析；NUMERIC、BIGINT、日期和时间戳保持为文本，
// 所以不会经过 Date 或进程时区。其余类型也保持为文本。
const NUMBERS = new Set([21, 23, 26, 700, 701]);
const TYPES = {
  getTypeParser: (oid: number) => (value: string) => (oid === 16 ? value === "t" : NUMBERS.has(oid) ? Number(value) : value),
} as unknown as CustomTypesConfig;

/** 流式读取时每次从游标取回的行数。 */
const BATCH = 500;

/**
 * 适配 `pg` 的连接池、池中的客户端或单个客户端。流式读取在自己的事务里使用服务端游标，
 * 所以需要能借出客户端的连接池；只有单个客户端时会读完全部行。
 * 超时通过在语句前后设置 `statement_timeout` 实现，由服务端取消；连接池会借出一个客户端来做这件事，
 * 无法还原该设置的客户端会被丢弃。
 */
export function pgDriver(source: Pool | PoolClient | Client): Driver {
  return driverFor(source, false);
}

/** pgDriver 的实现；inTransaction 为 true 时 source 是事务里的客户端，transaction 直接执行，加入这个事务。 */
function driverFor(source: Pool | PoolClient | Client, inTransaction: boolean): Driver {
  const run = async (client: Pool | PoolClient | Client, sql: string, parameters: readonly unknown[]): Promise<DriverResult> => {
    const result: QueryArrayResult = await client.query({ text: sql, values: parameters.map(parameter), rowMode: "array", types: TYPES });
    return { columns: result.fields.map((f) => f.name), rows: result.rows, affected: result.rowCount ?? 0 };
  };
  const driver: Driver = {
    dialect: PostgresDialect.instance,
    /** 执行一条语句，返回列名和各行的值。 */
    async query(sql, parameters, options): Promise<DriverResult> {
      const ms = milliseconds(options);
      if (ms === undefined) return run(source, sql, parameters);
      if (!("totalCount" in source)) {
        // 调用方自己的客户端，可能处在它的事务中：若语句使该事务中止，RESET 也会失败，
        // 此时由回滚来撤销 SET。
        await source.query(`SET statement_timeout = ${ms}`);
        try {
          return await run(source, sql, parameters);
        } finally {
          await source.query("RESET statement_timeout").catch(() => undefined);
        }
      }
      const client = await source.connect();
      let broken = false;
      try {
        await client.query(`SET statement_timeout = ${ms}`);
        return await run(client, sql, parameters);
      } finally {
        await client.query("RESET statement_timeout").catch(() => (broken = true));
        client.release(broken);
      }
    },

    /** 连接池借出一个客户端，单个客户端直接用，BEGIN 后执行 work，再 COMMIT 或 ROLLBACK。 */
    async transaction(work) {
      if (inTransaction) return work(driver);
      const lent = "totalCount" in source ? await source.connect() : undefined;
      const client = lent ?? (source as PoolClient | Client);
      let broken = false;
      try {
        await client.query("BEGIN");
        const result = await work(driverFor(client, true));
        await client.query("COMMIT");
        return result;
      } catch (e) {
        await client.query("ROLLBACK").catch(() => (broken = true));
        throw e;
      } finally {
        lent?.release(broken);
      }
    },
  };
  if (!("totalCount" in source)) return driver;
  const pool = source;
  return {
    ...driver,
    async *stream(sql, parameters, options) {
      const client = await pool.connect();
      let open = false;
      try {
        await client.query("BEGIN");
        open = true;
        const ms = milliseconds(options);
        if (ms !== undefined) await client.query(`SET LOCAL statement_timeout = ${ms}`);
        await client.query({ text: `DECLARE query_cursor NO SCROLL CURSOR FOR ${sql}`, values: parameters.map(parameter) });
        for (;;) {
          const batch: QueryArrayResult = await client.query({ text: `FETCH FORWARD ${BATCH} FROM query_cursor`, rowMode: "array", types: TYPES });
          yield* batch.rows;
          if (batch.rows.length < BATCH) break;
        }
        await client.query("COMMIT");
        open = false;
      } finally {
        // 出错，或消费方提前停止，都会让事务保持打开；连回滚都做不到的客户端会被丢弃，而不是放回连接池。
        let broken = false;
        if (open) await client.query("ROLLBACK").catch(() => (broken = true));
        client.release(broken);
      }
    },
  };
}

/** 超时毫秒数向上取整成整数，供拼进 SET 语句；未设置超时时为 undefined。 */
function milliseconds(options: ExecuteOptions | undefined): number | undefined {
  return options?.timeoutMs === undefined ? undefined : Math.ceil(options.timeoutMs);
}

/** 把绑定值转成 pg 接受的类型：bigint 转文本，字节转 Buffer。 */
function parameter(value: unknown): unknown {
  if (typeof value === "bigint") return value.toString();
  if (value instanceof Uint8Array) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  return value;
}

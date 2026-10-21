import type { Client, CustomTypesConfig, Pool, PoolClient, QueryArrayResult } from "pg";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult, ExecuteOptions } from "@query/core";

// Booleans and small numbers parse as usual; NUMERIC, BIGINT, dates and timestamps stay text, so
// nothing goes through Date or the process time zone. Everything else stays text as well.
const NUMBERS = new Set([21, 23, 26, 700, 701]);
const TYPES = {
  getTypeParser: (oid: number) => (value: string) => (oid === 16 ? value === "t" : NUMBERS.has(oid) ? Number(value) : value),
} as unknown as CustomTypesConfig;

const BATCH = 500;

/**
 * Adapts a `pg` pool, pooled client or client. Streaming uses a server-side cursor inside its own
 * transaction, so it needs a pool to borrow a client from; with a single client it reads all rows.
 * A timeout sets `statement_timeout` around the statement, so the server cancels it; a pool lends a
 * client for that, and a client that cannot reset the setting is discarded.
 */
export function pgDriver(source: Pool | PoolClient | Client): Driver {
  const run = async (client: Pool | PoolClient | Client, sql: string, parameters: readonly unknown[]): Promise<DriverResult> => {
    const result: QueryArrayResult = await client.query({ text: sql, values: parameters.map(parameter), rowMode: "array", types: TYPES });
    return { columns: result.fields.map((f) => f.name), rows: result.rows };
  };
  const driver: Driver = {
    async query(sql, parameters, options): Promise<DriverResult> {
      const ms = milliseconds(options);
      if (ms === undefined) return run(source, sql, parameters);
      if (!("totalCount" in source)) {
        // The caller's client, perhaps inside its transaction: if the statement aborts that
        // transaction, RESET fails too, and the rollback undoes the SET instead.
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
        // A failure, or a consumer that stops early, leaves the transaction open; a client that
        // cannot even roll back is discarded instead of going back to the pool.
        let broken = false;
        if (open) await client.query("ROLLBACK").catch(() => (broken = true));
        client.release(broken);
      }
    },
  };
}

function milliseconds(options: ExecuteOptions | undefined): number | undefined {
  return options?.timeoutMs === undefined ? undefined : Math.ceil(options.timeoutMs);
}

function parameter(value: unknown): unknown {
  if (typeof value === "bigint") return value.toString();
  if (value instanceof Uint8Array) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  return value;
}

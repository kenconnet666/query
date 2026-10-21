import type { Client, CustomTypesConfig, Pool, PoolClient, QueryArrayResult } from "pg";
import { Buffer } from "node:buffer";
import type { Driver, DriverResult } from "@query/core";

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
 */
export function pgDriver(source: Pool | PoolClient | Client): Driver {
  const driver: Driver = {
    async query(sql, parameters): Promise<DriverResult> {
      const result: QueryArrayResult = await source.query({ text: sql, values: parameters.map(parameter), rowMode: "array", types: TYPES });
      return { columns: result.fields.map((f) => f.name), rows: result.rows };
    },
  };
  if (!("totalCount" in source)) return driver;
  const pool = source;
  return {
    ...driver,
    async *stream(sql, parameters) {
      const client = await pool.connect();
      let open = false;
      try {
        await client.query("BEGIN");
        open = true;
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

function parameter(value: unknown): unknown {
  if (typeof value === "bigint") return value.toString();
  if (value instanceof Uint8Array) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  return value;
}

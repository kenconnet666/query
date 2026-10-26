import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { BatchWriteError, IDB_ERROR_CODES, InvalidRowError, QuotaError, TransactionClosedError } from "@zerodep-dev/idb";
import {
  dryRunDriver,
  EmptyResultError,
  from,
  InvalidRequestError,
  parseTableRequest,
  QUERY_ERROR_CODES,
  QueryExecutionError,
  TooManyRowsError,
  UnexpectedRowCountError,
  type Rendered,
} from "@zerodep-dev/query-core";
import { Lips } from "../fixtures/erp.js";
import { TestDatabase } from "../fixtures/support.js";

/** 与 Java、vue-tsx-pro 共用的清单 */
const SPEC = fileURLToPath(new URL("../../../../spec/error-codes.json", import.meta.url));
const codes = (pkg: string): string[] =>
  (JSON.parse(readFileSync(SPEC, "utf8")) as { codes: { code: string; package: string }[] }).codes.filter((c) => c.package === pkg).map((c) => c.code);

describe("业务错误码", () => {
  it("与共用清单一致，每个错误类带着清单里的码", () => {
    assert.deepEqual([...QUERY_ERROR_CODES], codes("core"));
    assert.deepEqual([...IDB_ERROR_CODES], codes("idb"));
    // 错误只读语句的 SQL 文字
    const statement = { sql: "SELECT 1" } as Rendered;
    const thrown = [
      new InvalidRequestError("x"),
      new EmptyResultError("x"),
      new TooManyRowsError("x"),
      new UnexpectedRowCountError("db", statement, 1, 0),
      new QueryExecutionError("db", statement, new Error("x"), true),
      new QueryExecutionError("db", statement, new Error("x")),
    ].map((e) => e.code);
    assert.deepEqual(thrown, [...QUERY_ERROR_CODES]);
    const local = [new InvalidRowError("s", []), new BatchWriteError("s", []), new TransactionClosedError([]), new QuotaError(undefined, undefined, null)].map((e) => e.code);
    assert.deepEqual(local, [...IDB_ERROR_CODES]);
  });

  it("表格请求读不懂时带 query.request-invalid", async () => {
    for (const params of [{ page: "x" }, { pageSize: "0" }, { condition: '{"field":"a"' }, { condition: '{"field":"a","op":"like","value":1}' }]) {
      assert.throws(() => parseTableRequest(params), (e: unknown) => e instanceof InvalidRequestError && e.code === "query.request-invalid", JSON.stringify(params));
    }
    const db = new TestDatabase({}, dryRunDriver());
    const q = from(Lips).select((l) => ({ delivery: l.delivery, quantity: l.quantity }));
    for (const params of [{ sort: "nope" }, { "filter.nope": "x" }, { "search.quantity": "1" }]) {
      await assert.rejects(db.table(q, parseTableRequest(params)), InvalidRequestError, JSON.stringify(params));
    }
  });
});

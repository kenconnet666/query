import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

/** 浏览器里没有 AsyncLocalStorage 和 node:crypto：在去掉它们的子进程里用 SQLite WASM 跑一遍，见 fixtures/no-async-context.ts。 */
describe("没有异步上下文的运行环境", () => {
  it("事务经由 tx、追踪、多行写入和迁移照常工作，capture 明确报错", async () => {
    const script = fileURLToPath(new URL("../fixtures/no-async-context.js", import.meta.url));
    const { stdout } = await promisify(execFile)(process.execPath, [script]);
    const r = JSON.parse(stdout.trim().split("\n").at(-1)!);
    assert.equal(r.sameClass, true, "tx is the same kind of database");
    assert.equal(r.afterRollback, 0, "the write through tx was rolled back");
    assert.equal(r.afterCommit, 4, "the nested transaction joined the outer one; the multi-row insert ran");
    assert.deepEqual(r.tracked, ["new", 2]);
    assert.deepEqual(r.doc, ["new", 2]);
    assert.match(r.capture, /capture\(\) needs Node's AsyncLocalStorage/);
    assert.deepEqual(r.migrated, ["1", "2"]);
    const seed = createHash("sha256").update("INSERT INTO QUERY_IT.M_TASK (ID, TITLE) VALUES ('T1', 'x')").digest("hex");
    assert.equal(r.checksums[1], seed, "Web Crypto gives the same SHA-256 as node:crypto");
  });
});

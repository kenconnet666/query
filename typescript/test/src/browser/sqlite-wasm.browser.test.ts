import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { chromium, type Browser } from "playwright-core";
import { serve } from "./server.js";

/** 页面：按 phase 起一个模块 Worker，把它发回的消息交给测试；每次读完就结束 Worker，释放 OPFS 的文件句柄。 */
const PAGE = `<!doctype html><meta charset="utf-8"><link rel="icon" href="data:,"><script type="module">
window.runWorker = (phase) => new Promise((resolve, reject) => {
  const w = new Worker("/test/dist/browser/sqlite-worker.js?phase=" + phase, { type: "module" });
  w.onmessage = (e) => { w.terminate(); resolve(e.data); };
  w.onerror = (e) => { w.terminate(); reject(new Error(e.message || "worker failed")); };
});
</script>`;

/** 依次试本机装的 Chrome 和 Edge（playwright-core 不下载浏览器）；都没有时返回 undefined。 */
async function launch(): Promise<Browser | undefined> {
  for (const channel of ["chrome", "msedge"]) {
    try {
      return await chromium.launch({ channel });
    } catch {
      // 换下一个
    }
  }
  return undefined;
}

/** 0009 的浏览器用法：Worker 里的 SQLite WASM 用 opfs-sahpool 持久化，事务经由 tx。CI 设 QUERY_REQUIRE_BROWSER=1，找不到浏览器时失败。 */
describe("浏览器里的 SQLite WASM", () => {
  let browser: Browser | undefined;
  let close = async () => undefined as void;
  let url = "";

  before(async () => {
    browser = await launch();
    if (browser === undefined && process.env.QUERY_REQUIRE_BROWSER === "1") throw new Error("QUERY_REQUIRE_BROWSER=1 but neither Chrome nor Edge could be launched");
    const s = await serve(PAGE);
    url = s.url;
    close = () => new Promise((resolve) => s.server.close(() => resolve()));
  });

  after(async () => {
    await browser?.close();
    await close();
  });

  it("Worker 里经由事务、追踪和迁移写入 OPFS，新的 Worker 读回同样的数据", async (t) => {
    if (browser === undefined) return t.skip("no Chrome or Edge installed");
    const page = await browser.newPage();
    const errors: string[] = [];
    page.on("console", (m) => {
      if (m.type() === "error") errors.push(m.text());
    });
    await page.goto(url);
    const run = (phase: string) => page.evaluate((p) => (globalThis as unknown as { runWorker(p: string): Promise<Record<string, unknown>> }).runWorker(p), phase);
    const written = await run("write");
    assert.equal(written.error, undefined, String(written.error));
    assert.deepEqual(written, { afterRollback: 0, migrated: ["1", "2"] });
    const read = await run("read");
    assert.equal(read.error, undefined, String(read.error));
    assert.deepEqual(read, { notes: 2, doc: ["new", 2], tasks: 1 }, errors.join("\n"));
  });
});

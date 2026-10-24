import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import type { Browser, BrowserContext, Page } from "playwright-core";
import { launch, serve } from "./harness.js";
import type { IdbTest } from "./idb-page.js";

/** 页面只加载 idb-page.js，它把操作挂在 globalThis.idbTest 上。 */
const PAGE = `<!doctype html><meta charset="utf-8"><link rel="icon" href="data:,"><script type="module" src="/test/dist/browser/idb-page.js"></script>`;

/** 在页面里调用 idbTest 的一个操作。 */
function call<K extends keyof IdbTest>(page: Page, name: K, ...args: Parameters<IdbTest[K]>): Promise<Awaited<ReturnType<IdbTest[K]>>> {
  return page.evaluate(
    ([n, a]) => {
      const api = (globalThis as unknown as { idbTest: Record<string, (...x: unknown[]) => unknown> }).idbTest;
      return api[n as string]!(...(a as unknown[]));
    },
    [name, args] as const,
  ) as Promise<Awaited<ReturnType<IdbTest[K]>>>;
}

/** 0010 的浏览器实测：真实的自动提交、标签页之间的实时查询与升级、存储估计。CI 设 QUERY_REQUIRE_BROWSER=1。 */
describe("浏览器里的 IndexedDB 本地存储", () => {
  let browser: Browser | undefined;
  let context: BrowserContext;
  let url = "";
  let stop = async () => undefined as void;

  before(async () => {
    browser = await launch();
    const s = await serve(PAGE);
    url = s.url;
    stop = () => new Promise((resolve) => s.server.close(() => resolve()));
    if (browser !== undefined) context = await browser.newContext();
  });

  after(async () => {
    await browser?.close();
    await stop();
  });

  /** 打开一个加载好脚本的标签页。 */
  async function tab(): Promise<Page> {
    const page = await context.newPage();
    await page.goto(url);
    await page.waitForFunction(() => "idbTest" in globalThis);
    return page;
  }

  it("事务里 await 了 fetch 后再用 tx 报 TransactionClosedError，之前的写入已提交", async (t) => {
    if (browser === undefined) return t.skip("no Chrome or Edge installed");
    const page = await tab();
    assert.deepEqual(await call(page, "autocommit", "autocommit"), { error: "TransactionClosedError", keys: ["n1"] });
    assert.deepEqual(await call(page, "storage", "autocommit"), { usage: "number", quota: "number" });
  });

  it("一个标签页的写入让另一个标签页的实时查询重新执行，升级时旧标签页的连接关闭并收到通知", async (t) => {
    if (browser === undefined) return t.skip("no Chrome or Edge installed");
    const a = await tab();
    const b = await tab();
    await call(a, "watch", "tabs");
    await a.waitForFunction(() => (globalThis as unknown as { idbTest: IdbTest }).idbTest.state().seen.length === 1);
    await call(b, "write", "tabs", "n1");
    await a.waitForFunction(() => (globalThis as unknown as { idbTest: IdbTest }).idbTest.state().seen.length === 2);
    assert.deepEqual((await call(a, "state")).seen, [[], ["n1"]]);
    assert.equal(await call(b, "upgrade", "tabs"), 2);
    const state = await call(a, "state");
    assert.equal(state.versionChanged, 1, "the old tab closed its connection and was told");
    assert.equal((await call(b, "state")).blocked, 0, "the upgrade was not blocked because the old tab let go");
  });
});

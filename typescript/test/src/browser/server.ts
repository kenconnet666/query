import { readFile } from "node:fs/promises";
import { createServer, type Server } from "node:http";
import { createRequire } from "node:module";
import { dirname, extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

/** 工作区根目录 `typescript/`：各包的 dist 和测试的 dist 都在它下面。 */
const ROOT = fileURLToPath(new URL("../../../", import.meta.url));

/** sqlite-wasm 浏览器构建所在的目录（index.mjs、sqlite3.wasm）。 */
const WASM = dirname(createRequire(import.meta.url).resolve("@sqlite.org/sqlite-wasm/sqlite3.wasm"));

/** 浏览器里没有包解析：把 JS 里的包名换成服务器上的路径。Worker 里不能用 import map，所以在服务端改写。 */
const PACKAGES: Readonly<Record<string, string>> = {
  "@zerodep-dev/query-core": "/packages/core/dist/index.js",
  "@zerodep-dev/query-sqlite": "/packages/sqlite/dist/index.js",
  "@sqlite.org/sqlite-wasm": "/sqlite-wasm/index.mjs",
};

const TYPES: Readonly<Record<string, string>> = {
  ".js": "text/javascript",
  ".mjs": "text/javascript",
  ".wasm": "application/wasm",
  ".html": "text/html",
};

/**
 * 测试用的静态服务器：`/sqlite-wasm/...` 指向 sqlite-wasm 的构建，其余路径指向工作区；`/` 返回 page。
 * 只监听 127.0.0.1（localhost 是安全上下文，OPFS 和 Web Crypto 可用）。返回服务器和它的地址。
 */
export async function serve(page: string): Promise<{ server: Server; url: string }> {
  const server = createServer(async (req, res) => {
    const path = decodeURIComponent(new URL(req.url ?? "/", "http://localhost").pathname);
    if (path === "/") {
      res.writeHead(200, { "content-type": "text/html" }).end(page);
      return;
    }
    const file = path.startsWith("/sqlite-wasm/") ? join(WASM, path.slice("/sqlite-wasm/".length)) : join(ROOT, normalize(path));
    if (!file.startsWith(WASM) && !file.startsWith(ROOT)) {
      res.writeHead(403).end();
      return;
    }
    try {
      const ext = extname(file);
      let body: string | Buffer = await readFile(file);
      if (ext === ".js" || ext === ".mjs") body = body.toString("utf8").replace(/(from\s*|import\s*\(\s*)"([^"]+)"/g, (m, head: string, name: string) => (name in PACKAGES ? `${head}"${PACKAGES[name]}"` : m));
      res.writeHead(200, { "content-type": TYPES[ext] ?? "application/octet-stream" }).end(body);
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("the test server has no port");
  return { server, url: `http://127.0.0.1:${address.port}/` };
}

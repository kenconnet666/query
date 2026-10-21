// Starts one server exactly as .lsp.json declares it, then checks hover and definition at a position.
//   node tools/lsp/verify.mjs <server> <file> <line> <column>   (1-based position, file relative to the repo)
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const [name, file, line, column] = process.argv.slice(2);
const configs = JSON.parse(readFileSync(resolve(projectDir, ".claude/marketplace/query-lsp/.lsp.json"), "utf8"));
const config = configs[name];
if (!config || !file || !line || !column) {
  console.error(`usage: node tools/lsp/verify.mjs <${Object.keys(configs).join("|")}> <file> <line> <column>`);
  process.exit(2);
}
const expand = (value) => value.replaceAll("${CLAUDE_PROJECT_DIR}", projectDir);
const child = spawn(config.command, config.args.map(expand), { stdio: ["pipe", "pipe", "inherit"] });
let stopping = false;
child.on("exit", (code) => {
  if (stopping) return;
  console.error(`${name}: server exited with ${code} before the check finished`);
  process.exit(1);
});

const pending = new Map();
let nextId = 1;
let buffer = Buffer.alloc(0);
child.stdout.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd < 0) return;
    const length = Number(/Content-Length: (\d+)/i.exec(buffer.subarray(0, headerEnd).toString())[1]);
    if (buffer.length < headerEnd + 4 + length) return;
    const message = JSON.parse(buffer.subarray(headerEnd + 4, headerEnd + 4 + length).toString());
    buffer = buffer.subarray(headerEnd + 4 + length);
    if (message.method && message.id !== undefined) send({ id: message.id, result: null });
    else if (pending.has(message.id)) pending.get(message.id)(message);
  }
});

function send(message) {
  const body = JSON.stringify({ jsonrpc: "2.0", ...message });
  child.stdin.write(`Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
}
function request(method, params) {
  const id = nextId++;
  send({ id, method, params });
  return new Promise((resolveResponse, reject) => {
    const timer = setTimeout(() => reject(new Error(`${method} timed out`)), config.startupTimeout ?? 60000);
    pending.set(id, (message) => {
      clearTimeout(timer);
      pending.delete(id);
      message.error ? reject(new Error(`${method}: ${message.error.message}`)) : resolveResponse(message.result);
    });
  });
}

const rootUri = pathToFileURL(expand(config.workspaceFolder ?? projectDir)).href;
const path = resolve(projectDir, file);
const uri = pathToFileURL(path).href;
const extension = path.slice(path.lastIndexOf("."));
const started = Date.now();
const initialized = await request("initialize", {
  processId: process.pid,
  rootUri,
  workspaceFolders: [{ uri: rootUri, name }],
  capabilities: { textDocument: { hover: { contentFormat: ["plaintext"] } } },
  initializationOptions: config.initializationOptions,
});
console.log(`${name}: ${JSON.stringify(initialized.serverInfo ?? "server")} initialized in ${Date.now() - started} ms`);
send({ method: "initialized", params: {} });
send({
  method: "textDocument/didOpen",
  params: { textDocument: { uri, languageId: config.extensionToLanguage[extension], version: 1, text: readFileSync(path, "utf8") } },
});

// A server may answer an empty hover while it is still importing the project, so retry for a while.
const position = { line: Number(line) - 1, character: Number(column) - 1 };
let hover = null;
for (let attempt = 0; attempt < 40 && !hover?.contents; attempt++) {
  hover = await request("textDocument/hover", { textDocument: { uri }, position });
  if (!hover?.contents) await new Promise((wait) => setTimeout(wait, 3000));
}
const definition = await request("textDocument/definition", { textDocument: { uri }, position });
const text = (contents) => (Array.isArray(contents) ? contents.map(text).join("\n") : contents?.value ?? contents ?? "");
console.log(`hover after ${Date.now() - started} ms: ${JSON.stringify(text(hover?.contents).trim())}`);
console.log(`definition: ${JSON.stringify(definition)}`);
stopping = true;
await request("shutdown");
send({ method: "exit" });
const passed = Boolean(hover?.contents) && Boolean(definition && (!Array.isArray(definition) || definition.length));
setTimeout(() => process.exit(passed ? 0 : 1), 1000);

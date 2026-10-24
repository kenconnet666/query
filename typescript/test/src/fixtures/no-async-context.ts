/**
 * 在没有 AsyncLocalStorage 和 node:crypto 的环境（浏览器、Worker）里跑一遍事务、追踪、多行写入和迁移：由 `unit/browser-runtime.test.ts`
 * 在子进程里执行，先去掉 `process.getBuiltinModule`，再加载本库，结果以一行 JSON 写到标准输出。
 */
(process as { getBuiltinModule?: unknown }).getBuiltinModule = undefined;

const { default: sqlite3InitModule } = await import("@sqlite.org/sqlite-wasm");
const { count, from, insertInto, migration, wasmDriver, SqliteDatabase } = await import("@zerodep-dev/query-sqlite");
const { CREATE_TASK } = await import("./schemas.js");
const { Doc, Note } = await import("./writes.js");

const sqlite3 = await sqlite3InitModule();
const raw = new sqlite3.oo1.DB(":memory:", "c");
raw.exec("ATTACH DATABASE ':memory:' AS QUERY_IT");
const db = new SqliteDatabase(wasmDriver(raw), { warn: () => undefined });
await (await db.schema().plan(Note, Doc)).apply();

const note = (id: string) => ({ id, tenant: "T1", title: null, qty: 1, status: "OPEN" });
const notes = async () => Number(await db.one(from(Note).select(() => count())));

// 回滚：经由 tx 的写入在异常后撤销；tx 是同一种数据库。
let sameClass = false;
await db
  .transaction(async (tx) => {
    sameClass = tx instanceof SqliteDatabase;
    await tx.execute(insertInto(Note).values(note("N1")));
    throw new Error("rollback");
  })
  .catch(() => undefined);
const afterRollback = await notes();

// 提交，嵌套的 tx.transaction 加入外层；多行插入自己开事务。
await db.transaction(async (tx) => {
  await tx.execute(insertInto(Note).values(note("N1")));
  await tx.transaction(async (inner) => inner.execute(insertInto(Note).values(note("N2"))));
});
await db.execute(insertInto(Note).values(note("N3"), note("N4")));
const afterCommit = await notes();

// 追踪：范围结束时写回变了的行，版本加一。
await db.execute(insertInto(Doc).values({ id: "D1", title: "old", version: 1, source: null }));
const docRow = () => from(Doc).where((x) => x.id.eq("D1")).select((x) => ({ id: x.id, title: x.title, version: x.version }));
const tracked = await db.tracking(async (t) => {
  const d = await t.one(Doc, docRow());
  d.title = "new";
  return d;
});
const doc = await db.one(docRow());

// capture 需要异步上下文，这里报错。
const capture = await db.capture(async () => undefined).then(
  () => "",
  (e: unknown) => (e as Error).message,
);

// 迁移：校验和改用 Web Crypto，verify 重新计算后一致。
const migrator = db.migrator([CREATE_TASK, migration("2", "seed", (steps) => steps.sql("INSERT INTO QUERY_IT.M_TASK (ID, TITLE) VALUES ('T1', 'x')"))]);
const migrated = await migrator.migrateToLatest();
await migrator.verify();
const checksums = (await migrator.history()).map((a) => a.checksum);

process.stdout.write(JSON.stringify({ sameClass, afterRollback, afterCommit, tracked: [tracked.title, tracked.version], doc: [doc.title, doc.version], capture, migrated, checksums }) + "\n");

# API 使用说明（IndexedDB 本地存储）

`@zerodep-dev/idb` 是给前端用的轻量本地存储：用装饰器声明对象仓库和索引，只按索引查询，事务在回调里 await 了别的工作时给出明确的报错。它不依赖 SQL 端的 `@zerodep-dev/query-*`，也不做离线写入后的同步。设计与取舍见 [0010](decisions/0010-indexeddb-local-store.md)。数据量大、要连接或聚合时，改用 [SQLite WASM](api-typescript.md#19-sqlite-与浏览器)。

要求：TypeScript 7（标准装饰器，不开 `experimentalDecorators`），当前的 Chrome、Edge、Firefox、Safari。示例在测试里都有可运行的版本：`typescript/test/src/fixtures/local.ts`、`test/src/unit/idb.test.ts`、`test/src/browser/idb-page.ts`。

## 速查

| 要做的事 | 写法 |
| --- | --- |
| 声明仓库 | `@store("notes") class Note { @key id!: string; @index owner!: string; title!: string }` |
| 打开 / 升级 | `const db = await openLocal("app", { version: 2, stores: [Note, User], upgrade: { 2: async (tx) => {...} } })` |
| 读写一行 | `db.get(Note, "n1")`、`db.put(Note, n)`、`db.add(Note, n)`、`db.delete(Note, "n1")` |
| 批量写 | `db.putAll(Note, rows)`、`db.addAll(Note, rows)`：一个事务，任何一行失败都不写 |
| 按索引查 | `db.from(Note).where("owner").eq("u1").reverse().limit(20).list()` |
| 复合索引 | `@store("notes", { indexes: [["owner", "createdAt"]] })`，`where(["owner", "createdAt"]).between(["u1", a], ["u1", b])` |
| 全表、内存筛选与排序 | `db.from(Note).all().scan((n) => n.title.includes("会议")).sortBy((a, b) => ...).list()` |
| 按查询删、改 | `.delete()`、`.modify({ pinned: false })`、`.modify((n) => n.tags.push("x"))` |
| 关系 | `@one(() => User, "owner") user?: User`、`@many(() => Comment, "noteId") comments?: Comment[]`，`.include("user", "comments")` |
| 事务 | `await db.transaction([Note, Tag], "readwrite", async (tx) => { ... })` |
| 实时查询 | `const stop = db.from(Note).where("owner").eq("u1").live((rows) => render(rows))` |
| 存储空间 | `await db.persist()`、`await db.estimate()`；配额用尽报 `QuotaError` |

## 1. 声明

```ts
import { boolean, index, key, lowercase, many, one, store } from "@zerodep-dev/idb";

@store("notes", { indexes: [["owner", "createdAt"]] })   // 复合索引写在这里
class Note {
  @key id!: string;                                      // 主键；多个 @key 时按声明顺序组成复合主键
  @index owner!: string;
  @index({ unique: true }) slug!: string;
  @index({ multiEntry: true }) tags!: string[];          // 数组的每个元素各进一次索引
  @index({ codec: boolean }) pinned!: boolean;           // 布尔不能当键，进索引时写成 1 / 0
  @index({ codec: lowercase }) title!: string;           // 大小写不敏感的影子索引，字段本身保留原样
  @index({ optional: true }) dueOn!: string | null;      // 允许为空；为空的行不在这个索引里
  createdAt!: Date;                                      // 不进单字段索引的字段不用声明
  @one(() => User, "owner") user?: User;                 // 关系字段不存进记录
  @many(() => Comment, "noteId") comments?: Comment[];
}
```

- **读回的行是声明的类的实例**：方法可以用。值按结构化克隆保存（`Date`、`bigint`、`Map`、二进制都可以），类的字段初始值不会重新运行。
- **键的规则**：IndexedDB 的键只能是数字、字符串、`Date`、二进制和由它们组成的数组。主键或索引字段为空、或不是合法的键时，写入报 `InvalidRowError` 并指出字段。IndexedDB 本身会让这样的记录悄悄地不进索引，这里改为报错。字段确实可以为空时声明 `@index({ optional: true })`。
- **编码器**：`boolean`（1 / 0）、`bigint`（保持大小顺序的文本，主键也可以用：`@key({ codec: bigint })`）、`lowercase`（不能用于主键）。编码器只作用于键和索引，编码后的值放在记录的影子属性 `$idx` 里，读回时去掉。查询时传原来的值，库会先编码。
- 声明的问题在 `openLocal` 时报错：没有 `@store` 或 `@key`、给主键加 `@index`、关系字段加索引、复合索引不到两个字段、两个类用同一个仓库名。

## 2. 打开与升级

```ts
const db = await openLocal("app", {
  version: 3,
  stores: [Note, User, Comment],
  upgrade: {
    3: async (tx) => {
      for (const n of await tx.from(Note).all().list()) await tx.put(Note, { ...n, slug: slugify(n.title) });
    },
  },
  dropStores: ["drafts"],
  onVersionChange: () => showReloadBanner(),
  onBlocked: () => console.warn("close the other tabs to upgrade"),
  durability: "default",
});
```

- **版本号显式写**。升级时库会比较声明与浏览器里的结构：缺的仓库和索引自动建，多出的或变了的索引自动删掉再建。库里多出的**仓库**保留不动，要删就写进 `dropStores`，因为删仓库会丢数据。
- **主键不能就地改**：主键变了时报错，要给仓库换个名字，并把旧名字写进 `dropStores`。
- **声明变了但版本号没变**时报错，并提示加大到哪个版本。库不会用旧结构悄悄地跑。
- `upgrade` 的各步骤在同一个 versionchange 事务里，按版本号执行大于旧版本、不大于 `version` 的那些，第二个参数是 `{ oldVersion }`。步骤失败时整个升级回滚，库保持原来的版本。步骤里只能用 `tx`，不能 await 别的工作（见第 4 节）。
- 别的标签页要升级时，本页的连接自动关闭并调用 `onVersionChange`，通常提示用户刷新。之后再读写会报"数据库已关闭"。本页要升级、但别的标签页还开着旧连接时，调用 `onBlocked`。

## 3. 查询

```ts
await db.from(Note).where("owner").eq("u1").reverse().limit(20).list();
await db.from(Note).where(["owner", "createdAt"]).between(["u1", from], ["u1", until]).list();
await db.from(Note).where("tags").anyOf(["work", "home"]).count();
await db.from(Note).where("slug").startsWith("2026-").keys();
await db.from(Note).where("title").startsWith("meet").list();          // lowercase 索引：不分大小写
await db.from(Note).all().scan((n) => n.title.includes("会议")).list(); // 显式的全表扫描
```

| 条件 | 说明 |
| --- | --- |
| `eq(v)`、`anyOf([...])` | 等于、等于其中任一个；`anyOf` 的结果按索引顺序，同一行只出现一次 |
| `between(a, b, { lowerOpen, upperOpen })` | 缺省两端都包含（与 SQL 的 BETWEEN 一致） |
| `gt`、`ge`、`lt`、`le` | 大小比较 |
| `startsWith(prefix)` | 单字段的文本索引 |

- **只按索引查**：`where` 的参数是索引名（字段名，复合索引是字段名的数组）或主键。不是索引时，构造查询就报错，并列出已有的索引。参数的类型来自类的字段，数组字段按元素类型查。
- 结果按这个索引的顺序：`reverse()` 倒过来，`offset(n)`、`limit(n)` 分页。
- **内存里的工作写在明处**：不在索引上的筛选写 `scan(predicate)`，不按索引的排序写 `sortBy(compare)`。两者都先读出索引范围内的行再在内存里做，`offset` / `limit` 在它们之后算。名字本身就说明了代价。
- 终结操作：`list()`、`first()`、`count()`、`keys()`（主键，复合主键是数组）；写操作 `delete()`、`modify(patch)`，返回改了几行，不能改主键。查询也可以 `for await` 遍历：先读完再交出，遍历中可以 await 别的工作。
- 只有一个范围、正序、不跳行、没有 scan 或 sortBy 时，一次 `getAll` 读完；`count` 在没有 scan 时由索引直接计数。

**关系**：`include("user", "comments")` 在父行读完后，每个关系用一批请求装回字段：多对一按目标的主键，一对多按目标上指回来的索引（那个字段必须有 `@index`）。读取和装载在同一个只读事务里。不做连接，也不做级联写；关系字段不存进记录。

## 4. 写入与事务

```ts
await db.put(Note, note);                 // 插入或覆盖
await db.add(Note, note);                 // 主键已存在或违反唯一索引时报错
await db.delete(Note, "n1");
await db.transaction([Note, Tag], "readwrite", async (tx) => {
  const n = await tx.get(Note, "n1");
  if (n) await tx.put(Note, { ...n, title: "x" });
  await tx.from(Tag).where("noteId").eq("n1").delete();
});
```

- 回调正常结束时提交（调用 `commit()` 并等到完成），抛出异常时回滚。
- **回调里只用 `tx` 上的操作**。IndexedDB 的事务在没有待处理的请求时会自动提交。回调里 await 了 fetch、定时器或别的数据库后，事务已经提交了，再用 `tx` 会报 `TransactionClosedError`，错误信息会说明原因，而不是浏览器的 `TransactionInactiveError`。那些工作要放到事务之前或之后。本库不像 Dexie 那样改写 Promise，也不提供靠空查询续命的 `waitFor`。
- 嵌套用 `tx.transaction(stores, mode, work)`：加入外层事务。仓库必须在外层里，外层是只读时不能要求读写，否则报错。
- `putAll` / `addAll` 总在一个事务里：先检查全部行，再逐行写。任何一行失败（键不合法、主键重复、违反唯一索引）时整个事务回滚，报 `BatchWriteError`，`failures` 列出全部失败的行的序号和原因。没有"部分成功"的模式。
- `durability` 缺省 `"default"`，`openLocal` 的选项里可以改成 `"strict"`（提交前落盘）。

## 5. 实时查询与多标签页

```ts
const stop = db.from(Note).where("owner").eq("u1").include("user").live(
  (rows) => render(rows),
  (error) => report(error),          // 缺省 console.error
);
// 不再需要时
stop();
```

- 先执行一次，之后本页提交的写入、以及同源其他标签页提交的写入（经由 `BroadcastChannel`）涉及查询读过的仓库时，重新执行查询，包括 `include` 的仓库。
- **按仓库失效**：写到同一个仓库的别的行也会触发。只通知已提交的写入，回滚的不通知。一次只执行一个查询；执行期间又有写入时，结束后再执行一次，回调总是拿到最新的结果。
- 只能在数据库上用，不能在事务里用。`db.close()` 之后不再更新。

## 6. 存储与配额

- `await db.persist()`：请求持久存储，返回是否获准。浏览器多半要求在用户手势（点击）里调用。
- `await db.estimate()`：已用和可用空间的估计（字节），浏览器不支持时为 `undefined`。
- 写入超出配额时报 `QuotaError`，带 `usage`、`quota` 估计值，`cause` 是原来的 `QuotaExceededError`；所在的事务回滚。
- **数据可能被清除**：没有获准持久存储时，空间紧张时浏览器会清除数据；Safari 在 ITP 下七天没有交互也会清除。重要的数据不能只存在这里。

## 7. 测试

- 单元测试可以用 `fake-indexeddb`：测试文件开头 `import "fake-indexeddb/auto"`。它模拟不了真实的自动提交时机和标签页之间的行为。
- 本仓库的浏览器实测用 `playwright-core` 驱动本机（或 CI runner 预装的）Chrome：`corepack pnpm test:browser`。它覆盖 await `fetch` 后的 `TransactionClosedError`、两个标签页之间的实时查询，以及升级时旧标签页关闭连接。Firefox 与 Safari 发版前手动核对。

## 8. 不做

同步、冲突解决、离线队列；SQL、连接、聚合与分组（用 SQLite WASM）；全文检索、按任意字段排序、查询规划器；旧浏览器和 IndexedDB 不可用时的降级。

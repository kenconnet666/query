# 0010 面向前端的 IndexedDB 本地存储

- 状态：已采纳并实现第一、二阶段（2026-10-24，`feat/sqlite-idb`）；用法见 [api-idb.md](../api-idb.md)
- 目标版本：2.1（与 0009 的 SQLite 方言分开发布）
- 范围：只有 TypeScript；不涉及 Java，也不进入现有的 SQL 核心包

## 背景

用户要一个"完全不同的"库：在浏览器里用 IndexedDB 做轻量的本地存储，给前端用。2026-10-24 用户确定：

- 定位是轻量的本地存储，不是完整的本地数据库（数据量大、要复杂查询时用 0009 的 SQLite WASM）；
- 离线写入后同步回服务端**完全不做**；
- 只支持新版浏览器（当前的 Chrome、Edge、Firefox、Safari）。

IndexedDB 没有 SQL：只有对象仓库、索引上的键范围和游标、以及会自动提交的事务。现有核心包的价值在"把查询渲染成 SQL"，这里用不上，所以另起一个包。

调研（2026-10-24，版本以 `npm view` 为准）：

- **Dexie 4.4.7**：字符串 DSL 声明仓库（`'id, name, &email, [a+b], *tags'`）；按版本号升级，不比较结构；`where(index).equals/between/anyOf/startsWith` 加 `filter`、`sortBy`；`liveQuery` 记录查询读过的键范围，写入提交后比对，跨标签页用 BroadcastChannel；用 Promise 区域（PSD）让 `async` 事务回调能 await Dexie 自己的 Promise，await 别的 Promise 时事务会自动提交，要用 `Dexie.waitFor`（靠空查询保持事务，耗 CPU）。压缩后约 25 到 31 KB（gzip）。
- **idb 8.0.4**：只是 Promise 包装，约 1.2 KB；文档明说"事务开始到结束之间不要 await 别的东西"。
- **RxDB 17.6、TinyBase 10**：响应式文档库、内存表格模型，带同步与复杂的模式机制，RxDB 的 IndexedDB 存储还是付费插件；对"轻量、不同步"来说太重。
- **平台事实**：键只能是数字、字符串、Date、二进制和数组（不能是 bigint、boolean、null）；索引字段的值不是合法的键时，这条记录**悄悄地不进这个索引**；值按结构化克隆存储（bigint、Date、Map 可以，类的原型会丢）；结果只能按索引顺序排序；复合索引只能按前缀查范围；`commit()`、`durability` 三家都有，`getAllRecords` 只有 Chromium 可靠，要按特性检测；Safari 在 ITP 下 7 天无交互会清掉数据，`navigator.storage.persist()` 要在用户手势里调用。
- **测试**：`fake-indexeddb 6.2.6`（WPT 通过率 82.8%，没有真实的自动提交时机和跨标签页行为）做单元测试；Vitest 5 的浏览器模式加 Playwright 1.64（chromium、firefox、webkit）做实测。

## 决定

### 1. 新包，不依赖核心包

- 包名 `@zerodep-dev/idb`，放在 `typescript/packages/idb`，与 `@zerodep-dev/query-*` 同一个工作区、同一套 TS 7 和发布流程。
- **不依赖 `@zerodep-dev/query-core`**：核心包带着 SQL 渲染、执行和结构变更，前端用不到；体积目标是压缩后 8 KB（gzip）以内。
- **不与 SQL 端共用实体类**：IndexedDB 按属性名存取，没有列名、没有 SQL 类型；索引是它的核心，而在 SQL 端只是结构变更的附属信息。共用会把 SQL 的概念带进前端，也会让 core 为了拆出公共的元数据包而大改。写法保持同一风格（TC39 标准装饰器、`Symbol.metadata`），用户上手不需要重新学。

### 2. 声明

```ts
@store("notes", { indexes: [["owner", "createdAt"]] })   // 复合索引写在这里
class Note {
  @key id!: string;                                      // 主键；多个 @key 时按声明顺序组成复合主键
  @index owner!: string;
  @index({ unique: true }) slug!: string;
  @index({ multiEntry: true }) tags!: string[];
  @index({ codec: boolean }) pinned!: boolean;           // 布尔、bigint 这类不能当键的值，进索引时由编码器转换
  title!: string;                                        // 不进索引的字段不用声明
  createdAt!: Date;
}
```

- 读回的行是这个类的实例（按原型还原），结构化克隆本身会丢掉原型。
- **编码器只用于主键和索引**：`boolean`（0/1）、`bigint`（定长的文本，保持排序）、`lowercase`（大小写不敏感的影子索引）。值本身照常结构化克隆。
- 写入时检查每个索引字段：值为 null、undefined 或不是合法的键时报错，并指出是哪个字段——避免记录悄悄地从索引里消失。字段确实可以为空时声明 `@index({ optional: true })`，这时缺失是允许的，查询文档里写明"为空的行不在这个索引里"。

### 3. 打开与升级

```ts
const db = await openLocal("app", {
  version: 3,
  stores: [Note, Tag],
  upgrade: { 3: async (tx) => { for await (const n of tx.from(Note).all()) await tx.put(Note, { ...n, slug: slugify(n.title) }); } },
});
```

- 版本号显式写；升级时比较声明与库里现有的仓库和索引：缺的仓库和索引自动建，库里多出的**索引**自动删，库里多出的**仓库**只在 `dropStores: ["old"]` 里写明时才删（删仓库会丢数据）。
- 声明变了但版本号没变时报错，提示"加大 version"，不悄悄地用旧结构跑。
- 数据迁移写在 `upgrade` 的对应版本里，在同一个 versionchange 事务中执行。
- 别的标签页要升级时（`versionchange`），默认关闭本页的连接并调用 `onVersionChange` 回调（通常提示用户刷新）；本页被别的标签页挡住时（`blocked`）调用 `onBlocked`。

### 4. 查询：只按索引查，内存里的工作写在明处

```ts
await db.from(Note).where("owner").eq("u1").reverse().limit(20).list();
await db.from(Note).where(["owner", "createdAt"]).between(["u1", from], ["u1", until]).list();
await db.from(Note).where("tags").anyOf(["work", "home"]).count();
await db.from(Note).where("slug").startsWith("2026-").keys();
await db.from(Note).all().scan((n) => n.title.includes("会议")).list();   // 显式的全表扫描
```

- `where(index)` 后面是 `eq`、`anyOf`、`between`、`gt` / `ge` / `lt` / `le`、`startsWith`；结果按这个索引的顺序，`reverse()` 倒序，`limit`、`offset`。
- 不写查询规划器。`where` 的名字不是索引时**构造查询时就报错**，列出已有的索引（与核心包"名字类错误立刻报错"一致）。
- 不在索引上的筛选必须写成 `scan(predicate)`，排序不按索引时写成 `sortBy(fn)`：都在内存里做，名字就告诉用户代价。Dexie 的 `filter` 悄悄退化成扫描，是它最常被抱怨的地方。
- 结果：`list()`、`first()`、`count()`、`keys()`；写操作 `delete()`、`modify(patch)` 也挂在同一个查询上。
- 有 `getAllRecords` 时用它一次取键和值，没有时退回 `getAll` 加 `getAllKeys`。

### 5. 关系：按键批量装载

```ts
@store("notes") class Note { @key id!: string; @index ownerId!: string; @one(() => User, "ownerId") owner?: User; @many(() => Comment, "noteId") comments?: Comment[]; }

await db.from(Note).where("ownerId").eq("u1").include("owner", "comments").list();
```

- 与 SQL 端的 `many.list(...)` 同一个思路：父行读完后，每个关系用一次批量读取（主键用 `getAll` 的多个范围，外键走它的索引）装回，不按行逐条读。
- 外键字段必须有索引，没有时声明时就报错。
- 不做连接、不做级联写。

### 6. 写入与事务

```ts
await db.put(Note, note);                 // 插入或覆盖
await db.add(Note, note);                 // 主键已存在时报错
await db.delete(Note, "n1");
await db.transaction([Note, Tag], "readwrite", async (tx) => {
  const n = await tx.get(Note, "n1");
  await tx.put(Note, { ...n, title: "x" });
});
```

- 事务的回调里只能用 `tx` 上的操作。回调里 await 了别的 Promise 导致事务提前提交时，下一次用 `tx` 会抛出明确的错误（"the transaction committed because the callback awaited non-IndexedDB work; do that work before or after the transaction"），而不是浏览器的 `TransactionInactiveError`。不像 Dexie 那样改写 Promise，也不提供靠空查询续命的 `waitFor`。
- 回调正常结束时调用 `commit()`，抛出异常时 `abort()`；嵌套调用加入外层（仓库集合必须是外层的子集，否则报错）。
- 批量写入（`putAll`、`addAll`）总在一个事务里：任何一行失败整批回滚，错误里列出失败的行和原因。不提供"部分成功"的模式。
- 默认 `durability: "default"`，选项里可以改成 `"strict"`。

### 7. 实时查询与多标签页

```ts
const stop = db.from(Note).where("owner").eq("u1").live((rows) => render(rows));
```

- 第一版按**仓库**失效：写入提交后，本页凡是读过这个仓库的实时查询重新执行；通过 BroadcastChannel 通知同源的其他标签页。按键范围的精细失效以后再看是否需要。
- 只通知已提交的写入。

### 8. 存储与配额

- `db.persist()` 封装 `navigator.storage.persist()`，文档说明要在用户手势里调用；`db.estimate()` 封装 `navigator.storage.estimate()`。
- 写入遇到 `QuotaExceededError` 时包成本库的 `QuotaError`，带上已用和可用的估计值。
- 文档写明 Safari 在 ITP 下 7 天无交互会清除数据，重要数据不能只存在这里。

### 9. 测试

- 单元测试：`fake-indexeddb` 6.2.5（6.2.6 发布不足两周，暂不用），跑在现有的 `node:test` 里；配额错误通过替换 `IDBObjectStore.prototype.put` 模拟。
- 浏览器实测：实现时改用 `playwright-core` 直接驱动本机或 CI runner 预装的 Chrome，不用 Vitest、也不下载浏览器；放在 TS job 里，覆盖事务的自动提交（await `fetch`）、升级与 `versionchange`、两个标签页之间的实时查询。Firefox 与 WebKit 暂不在 CI 里，发版前手动核对。
- WebKit 不是真正的 Safari，发版前在真 Safari 上手动过一遍核心场景。

### 10. 不做

- 同步、冲突解决、离线队列（用户明确不做）。
- SQL、连接、聚合与分组（需要时用 0009 的 SQLite WASM）；内存里的 `scan` / `sortBy` 之外不做查询计算。
- 全文检索、按任意字段排序、查询规划器。
- 旧浏览器和 IndexedDB 不可用时的降级（例如 localStorage）。

## 阶段

1. 声明、打开与升级、增删改查、按索引查询、事务；单元测试。
2. 关系的批量装载、实时查询与多标签页、配额封装；浏览器实测的 CI job。
3. 文档（新开 `docs/api-idb.md`）、示例、发布。

## 已确认（2026-10-24，用户"就按照你的规划去做"）

- 包名 `@zerodep-dev/idb`，不依赖核心包、不共用实体类。
- 用装饰器声明（与核心包一致）。`where` 的参数类型取类的字段名，是不是索引在构造查询时检查；字段的值类型（数组字段取元素类型、复合索引取元组）在编译期检查。
- 实现时的补充：复合索引的字段不必单独声明；`include` 与实时查询在第二阶段一起做了；读取一次取键和值没有用 `getAllRecords`，因为主键可以从记录的 keyPath 取得。

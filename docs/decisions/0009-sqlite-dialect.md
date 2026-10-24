# 0009 SQLite 方言（兼容 SQLite WASM）

- 状态：已采纳并实现（2026-10-24，`feat/sqlite-idb`），两端同步；用法见 [api.md 第 18 节](../api.md#18-sqlite) 和 [api-typescript.md 第 19 节](../api-typescript.md#19-sqlite-与浏览器)
- 目标版本：2.1
- 修订对象：CLAUDE.md 的"首批方言"（三库之外加 SQLite）；0001 的方言范围

## 背景

用户要求加 SQLite，并且要能在浏览器里用 SQLite WASM。2026-10-24 用户确定：

- **两端都做**（Java 与 TypeScript），与三库同样带金样例和实库场景。
- **能力缺口的处理**：只有带扩展的构建才支持的函数照常渲染，并在日志里记一条警告；完全不支持的，能在类型上拦住的就拦住（方言包不提供），拦不住的在渲染时报错。

调研与实测（2026-10-24）：

| 运行环境 | 版本 | SQLite | 相关的编译选项 |
| --- | --- | --- | --- |
| `org.xerial:sqlite-jdbc` | 3.53.4.0 | 3.53.4 | MATH_FUNCTIONS、PERCENTILE、FTS5、JSON（内置）；另带 `stdev`、`variance` 扩展 |
| Node 内置 `node:sqlite` | Node 24.18（稳定性 1.2，候选发布） | 3.53.1 | MATH_FUNCTIONS、PERCENTILE、FTS5 |
| `@sqlite.org/sqlite-wasm` | 3.54.1-build1 | 3.54.1 | MATH_FUNCTIONS、PERCENTILE、FTS5；`THREADSAFE=0` |
| `better-sqlite3` | 13.0.3 | 3.53.4 | 同上（本库不用） |
| `wa-sqlite` | 1.1.2（npm 上停在 1.0.0） | 3.53.0 | 带 `SQLITE_OMIT_DECLTYPE`（本库暂不支持） |

- **sqlite-wasm 的持久化**：`opfs` 要求 COOP/COEP 响应头且只能在 Worker 里；`opfs-sahpool` 不要求响应头，也只能在 Worker 里，同一个库不能多个连接同时打开；`kvvfs` 在主线程，约 5 MB。官方的 Worker1 / Promiser 消息接口已于 2026-04 标为不再扩展，推荐在 Worker 里直接用 oo1 接口。在 Node 里只支持内存库。
- **语言特性**（版本见各版发布记录，本机 3.53 / 3.54 实测）：窗口函数与 RANGE 框架、`FILTER`、`NULLS FIRST/LAST`、UPSERT、`RETURNING`、`UPDATE ... FROM`、RIGHT 与 FULL JOIN、`IS [NOT] DISTINCT FROM`、递归 CTE、带排序的 `string_agg`、`unixepoch(..., 'subsec')`、日期修饰符 `floor` 都有。没有 ROLLUP、GROUPING SETS、`INTERSECT ALL` / `EXCEPT ALL`、`WITHIN GROUP`、`FOR UPDATE`、`ALTER COLUMN`、表和列的注释，也没有 `stddev` / `variance`。
- **实测出的陷阱**：
  - `DECIMAL(13,3)` 列是 NUMERIC 亲和，`'1.10'` 存成 REAL 的 1.1，`SUM` 也是浮点；改成 TEXT 存储能原样读回，但与参数比较时按文本比（`'10' < '9'`），不能用。
  - `'Abc' LIKE 'abc'` 为真：LIKE 不区分 ASCII 大小写，与 MySQL 默认排序规则的行为相同，与 HANA、PostgreSQL 不同。
  - `date('2026-01-31', '+1 month')` 得到 3 月 3 日；加 `'floor'` 才得到 2 月 28 日。
  - `unixepoch` 舍去秒的小数后再相减，与三库"差值舍去小数"不同；要用 `'subsec'`。
  - `CAST('2026-01-31' AS DATE)` 得到整数 2026。
  - 小数列里的整数值存成 INTEGER，`7 / 2` 得到 3。
  - `CURRENT_TIMESTAMP` 是 UTC，三库是会话时区。
  - sqlite-jdbc 的 `setBigDecimal` 存成 REAL；超过 2^53 的整数在 `node:sqlite` 里要开 `setReadBigInts`。

## 决定

### 1. 模块与驱动

- Java：`java/query-sqlite`（`Sqlite` 门面、`SqliteDialect`、`SqliteDatabase`、`SqliteSchemaSyntax`），驱动 `org.xerial:sqlite-jdbc`（测试依赖，使用方自己引入）。
- TypeScript：`@zerodep-dev/query-sqlite`，两个驱动：
  - `nodeSqliteDriver(db)`：包装 `node:sqlite` 的 `DatabaseSync`，用于 Node 服务端和测试；
  - `wasmDriver(db)`：包装 `@sqlite.org/sqlite-wasm` 的 oo1 `DB`。浏览器里推荐把整个数据层（本库加 sqlite-wasm）放进一个 Worker、用 `opfs-sahpool` 持久化；页面与 Worker 之间的消息由使用方定，本库不包装已经不再扩展的 Worker1 接口。
- 最低 SQLite 版本 3.46（`floor` 日期修饰符）；用到 3.51 起的 percentile 时见第 3 条。
- 实库测试不需要容器：Java 用临时文件库，TS 用内存库加文件库，CI 里随单元测试一起跑。

### 2. 类型与存储

| 声明 | SQLite 列类型 | 存什么 | 读回 |
| --- | --- | --- | --- |
| 文本 | `VARCHAR(n)` / `TEXT` | TEXT | 原样 |
| 整数、长整数 | `INTEGER` / `BIGINT` | INTEGER | Java `Long`；TS 超出安全整数时 `bigint` |
| 小数 | `DECIMAL(p,s)` | NUMERIC 亲和（REAL 或 INTEGER） | 见下 |
| 浮点 | `DOUBLE` | REAL | 原样 |
| 布尔 | `BOOLEAN` | INTEGER 0 / 1 | 布尔 |
| 日期 | `DATE` | TEXT `YYYY-MM-DD` | 日期 |
| 时间戳 | `TIMESTAMP` | TEXT `YYYY-MM-DD HH:MM:SS[.fff]` | 时间戳 |
| 字节 | `BLOB` | BLOB | 原样 |

- 列类型照常写成 `DECIMAL(13,3)`、`DATE` 这类名字：SQLite 按名字决定亲和，`pragma table_info` 读回的也是这个名字，所以结构比较能还原声明。
- **小数**：按 NUMERIC 亲和存储，比较和排序是数值的；绑定时传文本，读回时按值转成 `BigDecimal` / 十进制文本。有效数字超过 15 位、或要精确求和（金额）时不精确，文档写明，建议金额用整数的最小单位。不做"按小数位放大成整数"的透明换算：算术、聚合、字面量和原生 SQL 都要跟着换算，容易错。
- **日期与时间戳**：统一的文本格式，所以文本比较即时间比较；写入前规整成上面的格式（不带 `T`、不带时区）。

### 3. 写法的差异

| 能力 | SQLite 上的写法 |
| --- | --- |
| 分页 | `LIMIT n OFFSET m`；只有 offset 时 `LIMIT -1 OFFSET m` |
| `year` / `month` / `day` / `hour` / … | `CAST(strftime('%Y', x) AS INTEGER)` 等 |
| `addDays` / `addMonths` / `addYears`、`addHours` 等 | `date(x, '+N months', 'floor')`；时间戳用 `strftime('%Y-%m-%d %H:%M:%f', x, ...)` |
| `daysBetween` / `secondsBetween` | `CAST(julianday(b) - julianday(a) AS INTEGER)`；`CAST(unixepoch(b, 'subsec') - unixepoch(a, 'subsec') AS INTEGER)` |
| `monthStart` | `date(x, 'start of month')` |
| `locate` / `substring` / `lpad` / `rpad` | `instr`、`substr`；补齐用 `substr(replace(printf('%*s', n, ''), ' ', pad) || x, -n)` 一类的组合 |
| 除法 | 被除数先 `CAST(x AS REAL)`，避免整数除法 |
| `cast(x, DATE)` 等 | `date(x)`、`strftime(...)`；数值类型照常 `CAST` |
| `stddevSamp` / `varSamp` 等 | 按公式渲染：`(SUM(x*x) - SUM(x)*SUM(x)/COUNT(x)) / (COUNT(x) - 1)`，标准差再开方；数值稳定性不如原生函数，文档写明 |
| `stringAgg` | `string_agg(x, sep ORDER BY ...)` |
| NULL 安全比较 | `a IS b` / `a IS NOT b` |
| 当前时间 | `strftime('%Y-%m-%d %H:%M:%f', 'now', 'localtime')`（本地时区，与三库的会话时区一致）；当前日期 `date('now', 'localtime')` |
| 插入或更新 | `ON CONFLICT (...) DO UPDATE SET c = excluded.c` |
| 取回生成的主键 | `RETURNING` |
| 联表更新 / 删除 | `UPDATE ... FROM`；删除用 `WHERE EXISTS` |
| LIKE | 原样的 `LIKE ... ESCAPE`，不区分 ASCII 大小写（与 MySQL 默认相同），文档写明；方言包另提供区分大小写的 `glob` |

**需要扩展的函数**（构建时打开 `SQLITE_ENABLE_MATH_FUNCTIONS`、`SQLITE_ENABLE_PERCENTILE` 才有）：`sqrt`、`power`、`ceil`、`floor`、`mod` 等数学函数，以及方言包的 `median`、`percentileCont`、`percentileDisc`。照常渲染，**每个数据库实例对每个函数第一次渲染时记一条警告**（Java `System.Logger`，TS 数据库选项 `warn`，缺省 `console.warn`），说明需要哪个编译选项；上表的四个运行环境都打开了。

**完全不支持的**：

- 只在方言包里的（`intersectAll`、`exceptAll`、`FOR SHARE` 等）：SQLite 包不提供，写不出来。
- 在核心里的：ROLLUP / GROUPING SETS 与 `grouping()`、`forUpdate()`、HANA 式的层级等。渲染时抛出 `UnsupportedOperationException`（TS `Error`），信息写明 SQLite 不支持和替代写法。TS 的 SQLite 包重新导出核心时去掉 `grouping` 和 `forUpdate`，从 SQLite 包导入时写不出来。

### 4. schema 与结构变更

- SQLite 的 schema 是 ATTACH 进来的库名，默认 `main`。表声明里的 schema 照常渲染成 `schema.表名`：要么在连接上 ATTACH 同名的库（测试里把 `QUERY_IT` ATTACH 成一个文件），要么用 `Config.schema("APP", "main")` 映射。
- 系统目录：`pragma_table_info`、`pragma_index_list` / `pragma_index_xinfo`、`pragma_foreign_key_list`，按声明的类型名还原列类型。
- 自增主键写成 `INTEGER PRIMARY KEY AUTOINCREMENT`（SQLite 只认这种写法，`BIGINT` 不行），读回时按此识别。
- **外键只能在建表时写**：建表语句里内联外键；给已有的表加外键归入"只报告"（要重建表）。
- `ALTER TABLE` 只能加列、改名、删列：改类型、改可空、改默认值都归入"只报告"，提示"新建表、搬数据、改名"的重建步骤。
- 没有表和列的注释：建表时略过注释；Java 每个实例第一次略过时记一条警告，TS 的结构变更没有写入器，只略过。
- DDL 在事务里（与 PostgreSQL 相同），整个计划、每个迁移都在一个事务里执行，失败整体回滚。

### 5. 事务与并发

- SQLite 只有一个写者：TS 的驱动写事务用 `BEGIN IMMEDIATE`，避免读锁升级时的死锁；Java 由使用方在 `SQLiteConfig` 上设 `busy_timeout`（见 api.md）。
- `forUpdate()` 报错（见第 3 条）；迁移的锁表照常可用。
- `node:sqlite` 和 sqlite-wasm 都是单连接：驱动把语句排队，事务期间别的调用等待。

### 6. 核心包的浏览器适配（WASM 的前提）

- **事务、实体追踪、`capture` 依赖 AsyncLocalStorage**，浏览器里没有。`db.transaction(work)` 的回调改为收到一个绑定在事务上的数据库（`work(tx)`，原来不用参数的写法在 Node 上照旧）；浏览器里必须用这个 `tx`。`tracking` 的 `t` 同理。`capture` 在浏览器里报错。
  - 实现：`tx` 用 `Reflect.construct(Database, ..., 子类)` 构造，只运行 Database 的构造函数、复制状态，原型仍是方言子类，所以方言专属的方法照常可用；各子类的构造参数不同，也不需要再运行。
  - 库内部的多语句写入、取回生成键、结构变更、追踪写回和迁移都改为经由 `tx` 执行，没有异步上下文时也在同一个事务里。
- **迁移的校验和与锁的编号**：一律用 Web Crypto（`crypto.subtle.digest`、`crypto.randomUUID`，Node 19 起都有），结果与原来的 `node:crypto` 相同，已有的历史表不受影响。
- 打包检查：核心包不能静态引入 Node 模块（现在都是按需 `getBuiltinModule`）。

验证：`unit/browser-runtime.test.ts` 在去掉 `process.getBuiltinModule` 的子进程里，用 SQLite WASM 跑事务、追踪、多行写入和迁移；`browser/sqlite-wasm.browser.test.ts` 用 `playwright-core` 驱动本机或 CI runner 预装的 Chrome，不下载浏览器。它在 Worker 里用 `opfs-sahpool` 写入，再由新的 Worker 读回。

### 7. 不做

- `wa-sqlite`（它的构建去掉了声明的类型名，结构读取要另写一套；需要 IndexedDB 后端的 VFS 时再看）。
- 透明的定点小数换算、ROLLUP 的 UNION ALL 改写、`FOR UPDATE` 的静默忽略。

## 验证

- 共享的金样例都要有 `.sqlite.sql`；SQLite 不支持的场景在 Java 的 `SqliteSpecTest` 和 TS 的清单里显式排除，并有单独的测试断言它报错。
- `LiveScenarios` 与 TS 的 `live.ts` 在 SQLite 上全部运行；需要跳过的场景写明原因。

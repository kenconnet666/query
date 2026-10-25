# 交接（2026-10-25）

长期约定见 [CLAUDE.md](../CLAUDE.md)，本文件只记录当前进度。换电脑继续时先看[换电脑后的准备](#换电脑后的准备)。

1.0.0 已于 2026-10-22 发布（Maven 中央仓库 `org.dpdns.zerodep:*:1.0.0`，npm `@zerodep-dev/query-*@1.0.0`，标签 `v1.0.0` 指向 `966a236`）。2.0 的全部七期、阶段 A（对照 EasyQuery 补全）、阶段 B（流程引擎读查询）、写入能力、A/B/C 三批补全和 [0008](../docs/decisions/0008-schema-tracking-sharding.md)（结构变更与迁移、`query:pull`、实体追踪、分区与分表），以及 [0009](../docs/decisions/0009-sqlite-dialect.md) 的 SQLite 方言和 [0010](../docs/decisions/0010-indexeddb-local-store.md) 的 IndexedDB 本地存储包，都已经经 PR 合并进 `master`（`feat/v2`、`feat/sqlite-idb`），尚未发布。之后为流程引擎（flow 仓库）做的下面"为流程引擎做的准备"一节，2026-10-25 经 PR #15 合并进 `master`，同样尚未发布。再之后的"通用门面与实库测试的组织"在分支 `test/live-by-topic` 上，还没有合并。

## 当前状态

### 通用门面与实库测试的组织（[0013](../docs/decisions/0013-portable-facade-and-live-tests.md)，分支 `test/live-by-topic`）

用户 2026-10-25 要求：把几个库通用的可执行门面暴露出来（以前只给方言门面），测试类上加注解或用启动变量选库，测试要好读、不跳来跳去、带中文注释；方案按推荐执行（按连接识别方言、实库测试集中到 query-it、建表改用声明生成）。

- **通用门面**：Java `Sql.database(DataSource | ConnectionProvider, configure)` 借连接读产品名，经 `dialect.DialectProvider`（各方言包的 `META-INF/services`）选方言；TS 方言包的驱动适配器带 `dialect`，核心包 `database(driver, options)`。PolarDB 得到 MySQL 方言。
- **实库测试**：Java 全部搬进 `java/query-it`（`@LiveTest` 是 JUnit 6 `@ParameterizedClass` 的组合注解，`only` / `except`），TS 在 `test/src/live` 按同样的主题拆成 23 个 `liveSuite`；`QUERY_DATABASES` 选库；各库的差别只在 `support/Db`、`support/targets.ts`；样例数据在 `SampleData`、`sample-data.ts`，表一律按声明建。旧的 `LiveScenarios`、各方言 `*LiveTest`、TS 的 `live.ts` 与 `*.live.test.ts` 已删除。
- **CI**：实库 job 只设 `QUERY_DATABASES`，Java 跑 query-it，TS 跑全部实库套件。
- **本机实测**：Java 五个库 466 个测试、TS 六个目标 548 个测试全部通过，每个库的数量与拆分前相同或多出通用门面的一个；全量 `verify`（含 `-P release`）通过。

### 为流程引擎做的准备（PR #15，2026-10-25 合并）

流程引擎在 `C:UserslionheartIdeaProjectslow`（Java 接 Spring、TS 接 Nest / Nuxt），第一版只用 MySQL 8，生产是阿里云 PolarDB MySQL 8.0；用户要求先完善本库并订立契约。

- **Spring 的事务管理器**：`SpringConnections.of(dataSource, transactionManager)` 后 `db.transaction` 由它执行（REQUIRED）。用户的 Spring 应用都是多数据源，事务要指定数据源，跨库用编程式事务，所以每个数据源各传自己的事务管理器。
- **迁移历史表可以改名**：`historyTable("flow_migration")`，两端。
- **PolarDB 方言**（[0012](../docs/decisions/0012-polardb-dialect.md)）：继承 MySQL 方言，闪回查询 `fromAsOf`、库存提示 `inventory`；`infra/polardb` 是开源 PolarDB-X 引擎的单节点实例（端口 53307）。核心加了写语句的提示 `update/deleteFrom(...).with(...)`。
- **流程引擎写法的实库场景**：六个用例（0013 起在 query-it 的 `workflow/EngineTest` 和 TS 的 `workflow/engine.live.test.ts`），五个库两端都通过；发现 InnoDB 的 `SKIP LOCKED` 会锁住扫描到的所有行，引擎领取作业改用带条件的 UPDATE 抢占。
- **本机环境**：WSL 空闲会关机（见 CLAUDE.md），跑实库测试前挂一个 `wsl -d Debian -- sleep 14400`。


### 2.0 完成了什么（`feat/v2`，设计见 [0006](../docs/decisions/0006-query-model-direction.md)）

- **第一期 骨架与删减**：Java 的表改为继承 `Entity` 的接口（每次表出现一个动态代理），投影只留单个表达式、`cols`、`row`、`selectAs`、`into`；`skipEmpty()` 代替 `IfPresent` 系列；作用域 16 张表、`row` 22 个参数由 `tools/generate-java-scopes.mjs` 生成。TS 同步删除重复写法。
- **整理**：Java 核心包按职责分子包（`table`、`expr`、`scope`、`select`、`exec` 等），TS 源码分 `schema`、`sql`、`query`、`exec` 文件夹；全部注释改为中文。
- **打印与测试 SQL**：`debugSql()`、`printSql`、`db.capture(...)`、空跑连接与驱动。
- **第二期 关系与导航**：`one`/`many`（Java 的 default 方法与 `@ManyToOne`/`@OneToMany`，TS 的 `@one`/`@many`）；多对一按需 LEFT JOIN，一对多的 EXISTS、相关子查询和 `first`。
- **第三期 动态列与排序**：`exclude`/`excludeIf`/`only`（列写成 NULL，相同的分组键和连接一起去掉）、`sortBy`。
- **第四期 对象树**：`many.list(...)` 按键批量加载子列表，Java 的 `selectAs` 自动映射嵌套 VO 和 `List<X>`。
- **第五期 条件对象**：`where(search)` 加 `@Where`/`@where`；`contains`、`startsWith`、`endsWith`。
- **第六期 原生 SQL**：`sql`、`sqlCond` 混用参数、`.on(方言类, ...)`、`sqlTable`、`identifier`。
- **第七期 文档**：两份 API 说明、[迁移指南](../docs/migration.md)（含从 1.x 升级）、[CHANGELOG](../CHANGELOG.md) 的 2.0.0、0006 改为已采纳、场景对照、README。
- 1.x 已有场景的金样例全部不变；新增 `navigation`、`one-to-many`、`dynamic-columns`、`filter-object`、`native-sql` 五个场景。

### 阶段 A：对照 EasyQuery 简化与补全（已完成）

四个子代理读完 EasyQuery 的测试（`sql-test`）和官方文档后结论一致，按三批推进：

- **第 1 批 可移植函数（已完成）**：聚合的 `.filter(cond)`（CASE WHEN，取代 PG 专属的 FILTER）、核心 `stringAgg`（取代 MySQL `groupConcat` 和两个方言版本）、`year`/`month`/`day`/`addDays`/`addMonths`/`addYears`/`daysBetween`（取代 HANA、MySQL 的同类函数）、`e.range(from, until)` 半开区间；金样例 `date-functions`、`conditional-aggregates`。
- **第 2 批 查询结构（已完成）**：`qualify(窗口条件)` 自动包派生表（写入器新增节点替换，窗口函数与不在结果里的排序项作为派生表的列）；`Many` 的 `countDistinct`、`stringAgg`；`firstValue`/`lastValue`/`ntile`、`lag`/`lead` 默认值；`db.page(q, page, size, total)`；金样例 `qualify`、`window-values`、`many-text`。
- **第 3 批 性能（已完成）**：`groupJoin(m -> m.deliveries())` 把同一关系的多个 count/sum/any 合成一个分组派生表（渲染时用节点替换，`Many` 的集合运算返回可替换的节点）；`many.orderBy(...).limit(n).list(...)` 每个父行限数（复用 qualify）；`@Where`/`@where` 走一对多路径时生成 EXISTS，同一关系的字段合并；金样例 `group-join`、`filter-many`。
- **决定不做**：类型化元组（Java 局部 record 加 `row` 已够）、跨方言日期格式串翻译（易错，保留方言函数）、递归 CTE（HANA 不支持）、动态表名、多对多中间表、JSON。

### 阶段 B：流程引擎的读查询（已完成）

- 子代理读了 Flowable、Camunda 7、Activiti 7、jBPM 7、Warm-Flow（经 RuoYi-Vue-Plus）、SnakerFlow 的映射文件和查询代码，整理出共同模式；结论写进[场景对照](../docs/scenarios.md#流程引擎的读查询)。
- 简化后的六张表（定义、实例、任务、身份链接、变量、历史任务）和查询放在测试夹具 `Workflow`（Java）与 `fixtures/workflow.ts`（TS）：待办（候选人或候选组，条件对象，分页计数）、变量过滤三种写法（EXISTS、groupJoin、多别名 JOIN）、最新版本两种写法（自关联的 `versions().max`、qualify）、已办、定义统计（groupJoin）、工作量（条件聚合）、读取时算耗时。金样例 `workflow-*` 共 9 个场景，三库实库全部通过。
- 由此补上的能力：`secondsBetween(from, to)`（三库统一为整秒、舍去小数）。其余写法用现有能力即可，没有发现别扭到需要新 API 的地方。

### 写入能力（已完成，设计见 [0007](../docs/decisions/0007-writes.md)；用户 2026-10-24 确认：插入时全部列都写、逻辑删除和审计填充这一轮做、只拦没有 WHERE 的 DELETE）

- **第 1 步（已完成）**：`@Id`/`@Version`/`@Generated`/`@LogicDelete` 注解和 TS 装饰器（元数据就绪，后几步用）；`insertInto(T).values(rows)`、`.from(query)`，`update(T).set(...).where(...)`，`deleteFrom(T).where(...)`；没有 WHERE 报错（`.all()` 放行），全局过滤器进 WHERE，`expect(n)`，`skipEmpty` 省略空值的 SET；`db.execute`、`db.render(write)`、`db.transaction(...)`（Java 线程绑定连接，TS 用 AsyncLocalStorage；Spring 用 @Transactional）；多行写入自动在一个事务里，Java 用 JDBC 批量。金样例 `write-*`，三库实库通过。
- **第 2 步（已完成）**：`update(T).byId(rows)`、`deleteFrom(T).byId(rows)`：按 `@Id` 定位，有 `@Version` 时 `SET version = version + 1 ... AND version = ?`，期望每行一行（乐观锁冲突抛 UnexpectedRowCount）；`skipEmpty().byId(row)` 只更新非空属性；`db.insertKeys(insert)` 取回 `@Generated` 的值（方言钩子：PG 由驱动或 RETURNING，MySQL 生成键或 insertId，HANA 在同一连接上查 CURRENT_IDENTITY_VALUE()）。
- **第 3 步（已完成）**：`insertInto(T).values(row).onConflict(keys?).update(cols?)` / `.doNothing()`（方言钩子：HANA `MERGE ... USING (SELECT ? AS ... FROM DUMMY)`，PG `ON CONFLICT ... DO UPDATE SET c = EXCLUDED.c`，MySQL `... AS new ON DUPLICATE KEY UPDATE c = new.c`，什么也不做时 `k = k`）；加锁读 `.with(forUpdate(Lock))`（HANA 的 SKIP LOCKED 写作 IGNORE LOCKED）。
- **第 4 步（已完成）**：`@LogicDelete` 并入表的过滤条件（Java `SourceFilters`，TS `conditionsFor`），查询、连接、关系、UPDATE/DELETE 都带"未删除"条件，`unfiltered()` 一并关闭；删除改写成 UPDATE（`byId` 时版本加一），`.physically()` 物理删除；插入时补上未删除的值；时间戳列用 `IS NULL` / `CURRENT_TIMESTAMP`。审计填充：Java `Config.onInsert/onUpdate`（`write/Fills`），TS `DatabaseOptions.fills` 与 `onInsert`/`onUpdate`，作用于插入、更新、`byId`、插入或更新的更新部分和逻辑删除，总是覆盖。金样例 `write-logic-delete`、`logic-delete-query`。
- **文档**：两份 API 说明的第 15 节、[0007](../docs/decisions/0007-writes.md)、场景对照的写入行、0001 第 4 条、CHANGELOG。

### 测试（2026-10-25 本机全部通过）

- **Java 单元、渲染与金样例**：core 186，HANA 93，PostgreSQL 96，MySQL 94，PolarDB 88，SQLite 82，Spring 10，Maven 插件 3。
- **Java 实库（query-it）**：HANA 93、PostgreSQL 100、MySQL 93、PolarDB 97、SQLite 83；没有连接变量时只跑 SQLite。
- **TS 实库**：HANA 94、PostgreSQL 98、MySQL 93、PolarDB 97，node:sqlite 与 WASM 各 83（各跳过 LPAD 片段与超时两项）。
- 本机实库：WSL Debian 的 Docker 里按 `infra/` 启动三库，连接信息在已忽略的 `.local/connections.md`。

## 待用户决定或执行

- **发布 2.0.0**：版本号目前仍是 1.x（根 POM、各模块 parent、TS 包、`docs/api.md` 的依赖示例要一起改），CHANGELOG 的 2.0.0 写的是"未发布"；按 [docs/release.md](../docs/release.md) 手动发布。
- **前身仓库是否冻结**：0001 里唯一的待定项。
- **PolarDB MySQL 兼容**：2026-10-24 调研完，结论在 [0011](../docs/decisions/0011-polardb-mysql.md)。用户说先不动代码、以后再说；以后要做时先在 PolarDB 测试集群上跑 MySQL 实库场景。
- **流程引擎本身**：2026-10-24 开始在 flow 仓库做，本库按它的需要补能力（上面"为流程引擎做的准备"）；方向与表设计在 flow 的 `docs/decisions/`。
- **PolarDB 旧小版本的兼容选项**：用户的小版本未知；插入或更新的行别名要 8.0.2.2.31 起，需要时再加兼容选项（0011、0012）。

## 下一步

阶段 A、阶段 B、写入能力、下面的四批、A/B/C 三批和 0008 都已完成并推送到 `feat/v2`；0009 与 0010 的第一、二阶段完成并推送到 `feat/sqlite-idb`。合并与发布 2.0 / 2.1、前身仓库是否冻结、何时开始流程引擎仍等用户决定。

2026-10-24 对照 EasyQuery 讨论后规划并完成了四批（用户确认"暂不做代码生成"后要求四批都做；每批两端、三方言同步，带金样例与实库场景，各批的提交与 CI 见 git log）：

- **代码生成**：当时暂不做，2026-10-24 随数据库变更改为 `query:pull` 生成只有列的父接口（见上一节和 0008）。
- **第 1 批（已完成）**：
  - 修正：UPDATE/DELETE 的 WHERE 经过多对一导航时渲染出没有连接的列（两端都有）。改为 `EXISTS (SELECT 1 FROM (SELECT 1) seed LEFT JOIN 关联表 ON ... WHERE 条件)`，HANA 的种子是 `SELECT 1 FROM DUMMY`；查询与写语句共用 `ImplicitJoins`（TS `implicitJoins`）；SET 经过导航报错。
  - `update(T).byId(edited).diffFrom(loaded)`、`@ReadOnly` / `@WriteOnce`、`db.exists(q)`。
- **第 2 批（已完成）**：`Config.schema(...)` / TS `schemas`（渲染统一走 `SqlWriter.table`）、具名过滤器 `filter("tenant", ...)` 与 `unfiltered("tenant")`、`withDeleted()`、写语句的 `withoutFills()`（查询与写语句共用 `Filtering` 开关）、Java `CodeEnum<C>`、`@LogicDelete(epochMillis = true)`。
- **第 3 批（已完成）**：`lpad`、`rpad`、`mod`、`power`、`sqrt`、`sign`、`hour`、`minute`、`second`、`addHours`/`addMinutes`/`addSeconds`（都换算成秒）、`monthStart`；先用临时探针在三库实测再定写法，`length` 留在方言包，周相关函数推后。
- **第 4 批（已完成）**：方言包的 `updateJoin` / `deleteJoin`（核心只有 `JoinUpdate` / `JoinDelete` 和方言钩子）：PG `UPDATE ... FROM` / `DELETE ... USING`，MySQL `UPDATE ... JOIN ... SET` / `DELETE 别名 FROM ... JOIN`，HANA 按主键的 MERGE（一行匹配多行报错、没有行被改，实库验证）/ `WHERE EXISTS`。实测确认 MySQL 驱动默认返回匹配行数（值没变的行也计入），与另两库一致。
- **之后（随流程引擎）**：多对多（经过中间表）、显式的一层子行同步与中间表同步（Jimmer 式）、内存装树。

### A、B、C 三批与 0008（已完成；用户 2026-10-24 确认"按照推荐执行所有步骤"）

每批两端、三方言，带金样例与实库场景，各自提交推送（见 git log）。

- **A 批（核心）**：`intersect` / `except`（HANA 从左到右求值，按需加括号）、`rightJoin`（左侧的过滤条件移进 RIGHT JOIN 的 ON）、多列 `keys(a, b).in(q)`、`anyOf` / `allOf`、`isNotDistinctFrom`、`bool(cond)`、`sumDistinct` / `avgDistinct` / `stddev*` / `var*`、`percentRank` / `cumeDist` / `nthValue`、`db.optional`。
- **B 批（定制）**：执行前钩子 `intercept`、逐行生成值 `generate`、`@Where(using = ...)`、`db.attach` / `attachMany`、`EmptyResultException` / `TooManyRowsException`。
- **C 批（方言包）**：核心只加 SPI（`combine`、`Window.frame`、`fullJoin`、`Cte.recursive`、提示钩子）；HANA 参数化视图、层级、全文、JSON、MEDIAN、窗口 PERCENTILE；PG `INTERSECT/EXCEPT ALL`、RANGE 框架、递归 CTE、`FOR SHARE` / `FOR UPDATE OF`、RETURNING、数组、JSONB、全文；MySQL 提示类、JSON、全文。
- **0008 结构变更**：表结构模型、三库读系统目录（默认值统一成 SQL 写法、带注释）、`check` / `plan` / `createTable`、安全 / 有风险 / 只报告三类差异、外部表；版本化迁移 `db.migrator(...)`（历史表加校验和、锁表、`verify` / `baseline` / `repair` / `migrateTo`）。
- **0008 代码生成**：`query-maven-plugin` 的 `query:pull` 与 TS 方言包的 `query-pull-*` bin，生成只有列的父接口 / 基类；TS 增加 `smallint`。
- **0008 实体追踪**：Java `db.tracking` / `tracked`、TS `db.tracking`，写成功后才写回版本、刷新快照；`Update.changedRows()`。
- **0008 分区与分表**：`@TableOption`（TS `options`）写原生分区；`Config.shard` / 选项 `shards` 注册同库物理分表，按 WHERE 选表、`UNION ALL`、写入按行路由、`allShards()` / `onShards(...)`；三库执行计划都把外层条件推进各分支。

### 0009 SQLite 与 0010 IndexedDB（已完成，`feat/sqlite-idb`；用户 2026-10-24 确认"就按照你的规划去做"）

- **用户的决定**：SQLite 两端都做；只有带扩展的构建才支持的函数照常渲染并警告，完全不支持的在类型上拦住或渲染时报错；小数用 NUMERIC 亲和。IndexedDB 是给前端用的轻量本地存储，离线同步完全不做，只支持新浏览器；独立的包 `@zerodep-dev/idb`，自己的装饰器，不依赖核心包。
- **SQLite（Java）**：`query-sqlite`（门面 `Sqlite`、`SqliteDialect`、`SqliteSchemaSyntax`），sqlite-jdbc 3.53.4.0 是测试依赖。核心加了方言钩子：按名字覆盖函数、窗口、CAST、算术、量化子查询、集合运算分组、写语句别名、`honoursQueryTimeout`（sqlite-jdbc 不理会 setQueryTimeout，由 `exec/Watchdog` 调 cancel）。`SchemaSyntax` 加了能力声明（`altersColumns`、`inlineForeignKeys`、`storesComments`）。警告按数据库实例去重（`SqlWriter.WARNED`）。实库场景用临时文件库加 `ATTACH ... AS QUERY_IT`。
- **SQLite（TS）**：`@zerodep-dev/query-sqlite`，驱动 `nodeSqliteDriver`（node:sqlite）与 `wasmDriver`（sqlite-wasm 3.54.1-build1 的 oo1），共用串行队列和 `BEGIN IMMEDIATE`；`grouping`、`forUpdate` 用 `never` 类型遮住。金样例与 Java 逐字节相同；共享实库场景按 `supports(...)` 把 ROLLUP、加锁读改为断言报错，LPAD、超时跳过。
- **核心的浏览器适配**：`db.transaction(async (tx) => ...)`，`tx` 由 `Reflect.construct(Database, ..., 子类)` 构造（只运行 Database 的构造函数、复制状态）；内部的多语句写入、取回生成键、结构变更、追踪写回和迁移都经由 tx；没有 AsyncLocalStorage 时 tracking 各自写回，capture 报错；迁移的校验和与锁编号改用 Web Crypto。
- **IndexedDB**：`packages/idb`（`metadata`、`rows`、`transaction`、`query`、`database`）。第一阶段做了声明、打开与升级、读写、按索引查询、事务；第二阶段做了 include、实时查询（按仓库失效，BroadcastChannel 跨标签页）、QuotaError、persist / estimate。不压缩、只去掉注释时约 8.9 KB（gzip），压缩后在 8 KB 目标以内。
- **浏览器测试**：`test/src/browser`，`playwright-core` 1.64.0 驱动本机或 runner 预装的 Chrome（不下载浏览器），`harness.ts` 是静态服务器，会把包名改写成路径，因为 Worker 里不能用 import map。覆盖 Worker 里 SQLite WASM 加 opfs-sahpool 的写入与读回、IDB 的真实自动提交、两个标签页之间的实时查询与升级。CI 的 TS job 加了 SQLite 实库和浏览器测试两步。
- **文档**：[api.md 第 18 节](../docs/api.md#18-sqlite)、[api-typescript.md 第 19 节](../docs/api-typescript.md#19-sqlite-与浏览器)、新的 [api-idb.md](../docs/api-idb.md)，以及 0009、0010、CHANGELOG 的 2.1.0、README、release.md、CLAUDE.md。
- **还没做**：Firefox / WebKit 的浏览器实测，发版前手动核对；IDB 的发布与示例（0010 第三阶段只做了文档）。

## 换电脑后的准备

仓库里的东西 `git clone` 后就齐了。下面这些只存在于旧电脑上，新电脑需要重新准备或手动拷贝：

| 东西 | 在旧电脑上的位置 | 新电脑上怎么办 |
| --- | --- | --- |
| 本仓库 | `C:\Users\lionheart\IdeaProjects\query` | `git clone https://github.com/kenconnet666/query`，在 `master` 上新开分支 |
| 参考仓库（只读） | `IdeaProjects\easy_query_pro`、`IdeaProjects\easy-query`、`IdeaProjects\orm-references\*` | 按 CLAUDE.md 的说明重新克隆；不影响构建，只用于设计参考 |
| 使用方代码（只读） | 旧电脑的本地目录 | 只在 Claude 项目记忆里有路径和说明，不进公开仓库 |
| Claude 项目记忆 | `C:\Users\Administrator\.claude\projects\C--Users-Administrator-IdeaProjects-query\memory\` | 含使用方说明和设计证据，按需手动拷贝到新电脑对应目录；没有它也能继续，只是缺少使用方背景 |
| 开发数据库 | WSL Debian 里的 Docker，由前身仓库的 `tools/*.ps1` 启动 | 见下文；没有本机库也能开发，实库测试交给 CI |
| IDEA 的 MCP 与数据源 | 用户级配置 `idea` 与四个数据源 | 在新电脑的 IDEA 中重新配置，连接 ID 会变 |
| 对照程序 | 旧电脑的会话临时目录，含使用方的业务常量，不进仓库 | 不需要；结论在 0002 与 0003 里 |

**最小可用环境**：

1. **JDK 17**：
   - CLAUDE.md 写的是旧电脑的 `C:\Users\Administrator\.jdks\jbr-17.0.14`；
   - 新电脑路径不同时，把 `JAVA_HOME` 指向任一 JDK 17 即可；
   - Maven 3.9.16 由 wrapper 自动下载。
2. **Node 24 与 corepack**：在 `typescript/` 里运行 `corepack pnpm install` 和 `corepack pnpm test`，pnpm 11.22.0 由 `packageManager` 固定。
3. **先跑一遍不需要数据库的测试**：

   ```powershell
   .\mvnw.cmd --batch-mode --threads 1C verify
   cd typescript; corepack pnpm install; corepack pnpm test
   ```

   实库场景缺少环境变量时会自动跳过。

**需要本机实库时**：

- 启动本仓库 `infra/` 下对应数据库目录里的 `compose.yaml`（命令见文件开头的注释，CI 用的也是它们，内存限制相同）；前身仓库 `tools/*.ps1` 启动的是库名 `eqp` 的旧实例，端口相同，不要同时运行。
- 然后按 CLAUDE.md 的"实库测试的变量"设置 `QUERY_*`。
- CLAUDE.md 里的 WSL 地址是旧电脑的，在新电脑上要换成实际值；数据库密码在各数据库目录的 `.env`，按 `compose.yaml` 开头的注释生成。
- 用本仓库的 compose 文件启动时，密码来自自己设置的 `QUERY_DB_PASSWORD` / `QUERY_HANA_PASSWORD`。

**新电脑上要核对的 CLAUDE.md 内容**：

- 本机 JDK 路径；
- WSL、Docker 的网络与地址；
- IDEA 数据源的连接 ID。

这些是旧电脑的事实，确认后直接改成新电脑的值。

## 容易踩的坑

- **LSP 路径**：会话从别的目录启动后再切到本仓库时，`${CLAUDE_PROJECT_DIR}` 仍指向原目录，TS LSP 会报 ENOENT。从本仓库目录新开会话即可。LSP 需要 `typescript/` 已安装依赖。
- **WSL 地址**：WSL 的 `eth0` 地址在 WSL 重启后会变。连不上数据库时，先用 `wsl -d Debian hostname -I` 查，或者直接用 `localhost`。
- **corepack**：非交互运行 corepack 时要设置 `COREPACK_ENABLE_DOWNLOAD_PROMPT=0`。
- **pnpm 11**：依赖的安装脚本要在 `pnpm-workspace.yaml` 的 `allowBuilds` 里放行，`@sap/hana-client` 已放行。
- **PowerShell 传 Maven 参数**：带点的 `-D` 参数要加引号，例如 `'-Dsurefire.failIfNoSpecifiedTests=false'`。
- **TS 测试的编译残留**：删掉或改名测试文件后，`test/dist` 里的旧结果还会被 `test:live` 跑到；只删 `test/dist` 时 `tsc -b` 看 `test/tsconfig.tsbuildinfo` 认为已是最新、不再输出，两个都删掉再编译。
- **金样例**：改了渲染以后，用 `$env:QUERY_SPEC_WRITE = '1'` 运行 `*SpecTest` 重写 `spec/render/`，审查 diff，再跑 TS 的 `spec.test.js` 确认两端一致。
- **Java 泛型**：行类来源的 `col(Getter)` 公共 API 要写成泛型 `<E> Getter<? super E, ?>`，通配符参数接收不了方法引用；同类型的多参数运算要做成实例方法（理由见 0002 第 4 条）。
- **TS 类型**：
  - `instanceof` 取反会把 `w.dialect` 收窄成 `never`，先存到 `const dialect: Dialect` 再判断；
  - getter 在类型上像只读字段，`into` 的字段检查已排除只读属性；
  - 只为编译期检查写的错误代码要放进不会被调用的函数里，否则测试运行时会真的执行它。
- **Bash 工具里的转义**：在 heredoc 或 `python -` 里写入文本时，反斜杠转义（例如 Java 字符串里的换行符转义）会被提前展开成真正的换行，字符串因此断开。含反斜杠的改动用编辑工具写。
- **Bash 工具里的大段 heredoc**：内容很长且引号很多时会报 "unexpected EOF"。先用写文件工具把脚本写到临时目录，再执行它。
- **HANA 内存**：HANA 会把 cgroup 内存限制当作物理内存。8 GiB 是 SAP 公布的最低值，测试变慢或内存不足时先查看占用，不要调大限制。
- **javac**：Java 17 上 switch 模式匹配仍是预览特性，渲染器用节点自己的 `render` 方法。
- **Java 条件表达式**：`?:` 的两支分别是 `Integer` 和 `Long` 时会按数值提升成 `Long`，写回 `Integer` 字段时报错；分开写 if。
- **Java 重载与 lambda**：隐式类型的单参数 lambda 同时匹配 `Consumer` 和 `Function` 时报歧义（只抛异常的块就是这样），所以追踪分成 `tracking` 和 `tracked` 两个名字。
- **时间精度**：Linux 上 `LocalDateTime.now()` 带纳秒，HANA 的 TIMESTAMP 只到 100 纳秒，绑定时拒绝截断；本机 Windows 的时钟只到 100 纳秒，复现不了，只在 CI 上出现。本库写入的时间戳先截到毫秒。
- **PostgreSQL 手写 DDL**：没加引号的 `QUERY_IT` 会折成小写，测试里手写的分区语句要写 `"QUERY_IT"."PART_LOG"`。
- **事务视图**：TS 的 `tx` 不能用 `new this.constructor(...)` 构造，因为测试的 `TestDatabase` 构造参数是 `(options, driver)`，与方言子类不同；用 `Reflect.construct` 只运行 Database 的构造函数。按实例比较的地方（事务、捕获、追踪范围的查找）要比较 `#root`。
- **单连接驱动与事务**：浏览器里没有 AsyncLocalStorage，事务回调里直接用 `db` 的语句会排在事务后面，于是一直等下去；库内部一律经由 `tx`，文档要求使用方也这样写。
- **Node 的 BroadcastChannel**：会让进程不退出，IDB 包创建频道后调用 `unref()`。
- **sqlite-wasm 在 Node 里**：只支持内存库；QUERY_IT 也 ATTACH 成 `:memory:`。浏览器里 `ATTACH 'file:/x.db?vfs=opfs-sahpool'` 可用。
- **fake-indexeddb**：用 6.2.5；它模拟不了真实的自动提交时机，相关的断言放在浏览器测试里。IDB 的 `anyOf` 结果按索引顺序，同一个值里的行再按主键排序。
- **npm 依赖的版本**：新加的依赖选发布满两周的版本（fake-indexeddb 6.2.6 只有 8 天，所以用 6.2.5）。
- **TypeScript 7.1**：开发编译器是 7.1.0-dev.20261023.1（跟随 vue-tsx-pro 用 dev 构建；pnpm 11 拒绝发布不满一天的包，升级时选满一天的那一版，不要往 `pnpm-workspace.yaml` 加豁免）。7.1 不再把两处写法相同的内联映射类型当成同一个类型（`exclude` 与 `excludeIf` 报 TS2719），要抽成具名类型；用户代码推断出的类型引用到它时，具名类型还要从包里导出（否则 TS2883）。
- **只在编辑器里出现的类型错误**：`tsc -b` 通过，IDE 或 `tsc --lsp` 打开单个文件时却报错。原因是泛型类在逆变位置用到映射类型（`Many<T>` 的 `#on: (t: Ref<T>) => Cond`，而 `Ref<any>` 不是 any），能否赋给 `X<any>` 取决于编译器是否已算出变型。约束里不要写 `X<any>`，改用品牌类型（`AnyMany`）。排查时让 `tsc --lsp` 逐个文件拉取诊断（`textDocument/diagnostic`），整项目编译发现不了。
- **IndexedDB 的事务结束时机**：CI 的 Chrome 上，回调 await 了 fetch 之后事务已经结束，但 `complete` 事件还没派发，`objectStore()` 报 `InvalidStateError`。按规范它只在事务已结束时报这个错，所以不看 `#done`，一律换成 `TransactionClosedError`。本机复现不了，只在 CI 上出现。

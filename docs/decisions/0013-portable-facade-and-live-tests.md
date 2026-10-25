# 0013 通用门面与实库测试的组织

- 状态：已采纳（2026-10-25），两端实现，本机五个库（TS 另有 SQLite WASM）实测；CI 的实库 job 改为按库选择
- 起因：
  - 流程引擎（flow 仓库）只用核心写法，接到 Spring 或 Nest 里时由使用方给连接，不该依赖具体的方言包；而 [0002](0002-api-architecture.md) 要求"用户必须选定方言包并创建实例"。
  - 实库测试只能借各方言模块继承一个 1341 行的抽象类（Java 的 `LiveScenarios`，TS 的 1463 行 `live.ts`）。读一个测试要在基类、子类的钩子（引号、文本类型、时间戳类型、自增列、日期字面量、`supports("rollup")`）、共享的查询函数和种子数据之间来回翻。
  - 用户要求：暴露几个库通用的可执行门面；在测试类上加注解或用启动变量选择测试哪些库；测试要好读，不跳来跳去，带中文注释。
- 修订：0002 的"用户必须选定方言包"；[0012](0012-polardb-dialect.md) 的"实库场景继承 MySQL"。

## 决定

### 1. 通用门面

| | Java | TypeScript |
| --- | --- | --- |
| 入口 | `Sql.database(DataSource)`、`Sql.database(DataSource, configure)`、`Sql.database(ConnectionProvider, configure)` | 核心包的 `database(driver, options?)` |
| 方言从哪来 | 借一个连接读 `DatabaseMetaData.getDatabaseProductName()`，用 `ServiceLoader` 找认识它的 `DialectProvider`：方言包在 `META-INF/services` 里登记，HANA 的驱动报 `HDB`，其余是 `PostgreSQL`、`MySQL`、`SQLite` | 方言包的驱动适配器（`hanaDriver`、`pgDriver`、`mysqlDriver`、`nodeSqliteDriver`、`wasmDriver`）带上自己的方言（`Driver.dialect`） |
| 返回 | 方言门面会给的同一种实例（例如 `PostgresDatabase`），类型写成 `Database` | 核心包里不属于哪个方言包的 `Database`；要用 `returning` 这类方言实例上的方法时用方言包建实例 |
| 认不出时 | 报错，列出类路径上登记过的方言 | 手写的驱动没有方言时报错 |

- 方言专属的能力仍只在方言门面里，核心函数仍只放各库语义一致的。通用门面的用处是：只导入 `Sql.*`（TS 只从核心包导入）的代码由编译器保证没用方言专属写法，在哪个库上跑由连接决定。
- PolarDB 报告的产品名就是 MySQL，PolarDB-X 引擎的版本号带 `X-Cluster`、云上的却是普通的 `8.0.x`，靠驱动分不出来。所以通用门面在 PolarDB 上给 MySQL 方言：通用写法在两者上渲染相同（0012 的实测）；要闪回查询、库存提示时用 `Polardb.database(...)`。
- 没有选用"传方言名"的写法：名字会把选择推到配置文件里，还要和方言包的版本对上；驱动报告的产品名是现成且稳定的。

### 2. 实库测试的组织

- **位置**：Java 的全部实库测试搬进新模块 `java/query-it`。它只有测试，不发布：`central-publishing-maven-plugin` 的 `excludeArtifacts` 排除它，jar 插件 `skipIfEmpty`。方言模块只留单元、渲染和金样例测试，JDBC 驱动也不再是它们的测试依赖。TS 仍在私有测试包里，`test/src/live` 按主题分目录。
- **选库**：
  - Java：测试类上标 `@LiveTest`，它是 JUnit 6 `@ParameterizedClass` 的组合注解（该 API 在 6.0 仍是 EXPERIMENTAL，只用在测试里）。整个类在每个选中的库上各跑一遍，报告里显示成 `[MySQL] 测试名`；`@LiveTest(only = {MYSQL, POLARDB})`、`@LiveTest(except = SQLITE)` 限定适用的库。
  - TS：`liveSuite(标题, { only, except }, (live) => { ... })`，规则相同。
  - 环境变量 `QUERY_DATABASES`（Java 也认 `-Dquery.databases`）是逗号分隔的库名，只跑列出的库，不设时全部都跑。TS 另有 `sqlite-wasm`；`pg` 是 `postgres` 的别名。
  - 选中的库没有连接变量时记为跳过，设了 `QUERY_REQUIRE_LIVE` 时改为失败。Java 在每个测试方法开始前就跳过：如果等测试里第一次用到 `live` 时才跳过，写在 `assertThrows` 里的调用会把跳过的异常当成预期的异常接住。
- **各库的差别只写一处**：连接、方言门面、建 schema 的语句、HANA 的慢语句存储过程，Java 写在 `support/Db`，TS 写在 `support/targets.ts`。测试里库之间结果不同的地方写成 `if (live.is(SQLITE)) { ... }`，不再用 `supports("rollup")` 这样的字符串。
- **建表和数据**：
  - 建表一律按声明生成（`db.schema().plan(...)`），种子数据用插入语句写，删掉了手写 DDL 的类型钩子和日期字面量的改写。只有专门测手写的表（数组列、JSON 列、全文索引、HANA 的层级表和表函数、PostgreSQL 的分区）才写 SQL，写在用它的测试里。
  - 为此 `Erp` 的数量列声明了 `DECIMAL(13,3)`，`Writes` 的 `W_DOC.SOURCE` 声明了默认值、`W_CODE` 声明了唯一索引（两端）。
  - 读查询共用的 SAP 样例数据在 `support/SampleData`（TS 是 `sample-data.ts`），类注释画出每张表的每一行，测试的注释直接引用"客户端 100 有 D1 到 D5"。Java 每个库在整轮测试里只建一次；node:test 让每个测试文件跑在自己的进程里，TS 在每个套件开始时重建。
  - 测试类自己的表（写入、追踪、流程引擎）在类里建。只有一个测试类用的表声明（引擎的五张表、各方言的分区表）搬进测试类本身。
- **读起来不跳**：
  - 查询写回测试方法里，期望值紧跟其后，不再调 `Scenarios`、`SpecCases`、`Workflow` 里的查询函数；那些函数只留给渲染和金样例测试。同一个查询因此在金样例和实库测试里各有一份，代价是改写法时两处都要改。
  - 每个类一个主题，类注释写测什么、用哪些数据、各库的差异，行内注释只写不直观的原因。
- **主题**（Java 类与 TS 文件一一对应）：

  | 包 / 目录 | 主题 |
  | --- | --- |
  | 根 | `PortableFacadeTest` 通用门面 |
  | `query` | `SelectTest` 查询与结果映射、`JoinAndSetTest` 连接子查询与集合运算、`FunctionTest` 文本与数值函数、`DateTimeTest` 日期与时间、`AggregateTest` 分组与聚合、`WindowTest` 窗口函数、`RelationTest` 导航关系与对象树、`FilterObjectTest` 条件对象、`NativeSqlTest` 原生 SQL 字面量与参数 |
  | `exec` | `ExecutionTest` 分页、流式、缓存与超时 |
  | `write` | `WriteTest` 插入更新与删除、`TrackingTest` 追踪范围 |
  | `schema` | `SchemaTest` 结构变更、`MigrationTest` 迁移、`ShardingTest` 分表 |
  | `workflow` | `WorkflowQueryTest` 流程引擎的读查询、`EngineTest` 流程引擎的并发写法 |
  | `dialect` | `HanaTest`、`PostgresTest`、`MysqlTest`（MySQL 与 PolarDB）、`PolardbTest`、`SqliteTest` |

- **PolarDB 不再继承 MySQL 的测试类**：MySQL 的专属场景标 `@LiveTest(only = {MYSQL, POLARDB})`（TS 的 `{ only: ["mysql", "polardb"] }`），PolarDB 照样跑通用场景、MySQL 的专属场景和自己的专属场景。query-mysql 的 test-jar 仍保留，给 PolarDB 的金样例测试继承 `MysqlSpecTest`。

### 3. CI

实库 job 只设 `QUERY_DATABASES=<库>`。Java 运行 `query-it`：`-pl java/query-it -am` 加 `-Dtest=org/dpdns/zerodep/query/it/**`，上游模块的单元测试被过滤掉。TS 运行 `test/dist/live` 下的全部套件。TS 单元 job 用 `QUERY_DATABASES=sqlite,sqlite-wasm` 跑两个 SQLite 驱动。不再按方言模块和测试类名逐个配置。

## 验证（2026-10-25，本机）

- Java 五个库共 466 个测试通过：HANA 93、PostgreSQL 100、MySQL 93、PolarDB 97、SQLite 83，与拆分前相同。拆分时"first 取第一行"与"零行或一行的查询多于一行时报错"合成了一个测试，又多了通用门面的一个。
- TS 六个目标共 548 个测试通过：HANA 94、PostgreSQL 98、MySQL 93、PolarDB 97，两个 SQLite 驱动各 83。四个服务端库都是原来的数加上通用门面的一个；SQLite WASM 现在也跑 SQLite 的专属场景。两个 SQLite 驱动各跳过 LPAD 片段与超时两项，原因同前。
- 通用门面在五个库上都识别出了对应的方言（PolarDB 是 MySQL 方言）；类路径上没有方言包时的报错由核心包的单元测试验证。

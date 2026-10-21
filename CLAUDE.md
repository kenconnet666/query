# query 项目约定

开工前先读 [.claude/HANDOFF.md](.claude/HANDOFF.md)（当前进度、待定事项和下一步），再读 [0001 方向决定](docs/decisions/0001-standalone-analytic-query-library.md)。

## 定位与基线

- **定位**：面向分析型复杂查询的类型安全 SQL 构建库，Java 与 TypeScript 各一份实现。不依赖 EasyQuery，也不做完整 ORM：不做实体跟踪、导航、DDL 和迁移。
- **Java**：17 及以上，`maven.compiler.release=17`。本机 JDK 为 `C:\Users\Administrator\.jdks\jbr-17.0.14`，IDEA 中的 SDK 名为 `jbr-17`。不使用注解处理器（APT）。
- **TypeScript**：最低支持 6.0；开发编译器固定为 7.0.2（原生编译器），工程在 `typescript/`。
- **首批方言**：SAP HANA 和 DuckDB，然后是 PostgreSQL 和 MySQL。
- **API 尚未稳定**：0001 里的代码只是方向示意。原型验证之前，不写详细的 API 文档或使用手册；文档只记录决定、取舍和验证证据。

## 必须参考的本地仓库

设计任何能力之前，先看下面这些仓库怎么做：核对版本和适用性，再结合本项目的规模转化，不照抄。它们都只读，不在其中提交改动。

### 前身：EasyQuery Pro

路径 `C:\Users\Administrator\IdeaProjects\easy_query_pro`（GitHub `kenconnet666/easy_query_pro`，分支 `codex/bootstrap-easyquery-pro`）。它是基于 EasyQuery 3.2.17 的 Java 8 增强包，外加一个 TS ORM 子项目。可以借鉴的部分：

- **HANA 的实测知识**：`docs/hana-*.md`，包括类型、函数和函数审计、字符类型、LOB、JSON、层级、全文检索、参数化视图、生成键、写入、HDI 与迁移。
- **三库差异清单与报表能力**：`docs/dsl-design.md` 列出三库的类型和操作清单；`docs/reporting-*.md`、`dsl-windows.md`、`dsl-statistics.md`、`dsl-groups.md`、`dsl-temporal.md`、`dsl-comparison.md` 覆盖报表、窗口、统计、分组、时间与比较；`native-numeric.md` 和 `mysql-*.md`、`pgsql-*.md` 覆盖原生数值与各库专属类型。
- **验证证据**：`docs/execution.md`（执行记录）和 `docs/compatibility.md` 记录了哪些行为已经在真实数据库上证实。
- **TS 起点**：`ts-orm/`。设计说明在 `ts-orm/docs/`（子查询、报表、ROLLUP、统计、时间、JSON、事务、批量参数上限、PG 数组）；可复用其不可变查询、typed codec 和结构化编译的思路。它的方言模型是写死的联合类型，正是要重构的部分。
- **基础设施**：`infra/`（Compose）、`tools/*.ps1`（数据库脚本），以及 `.github/workflows/ci.yml` 和 `ts-orm.yml`（GitHub Actions 中运行 MySQL、PostgreSQL 和 HANA 的方式）。
- **不要沿用**：与 EasyQuery 耦合的代码（继承上游内部类、`ProDsl.of(...)` 包装、多步安装）是这次重做要摆脱的东西；思路可以借鉴，代码重写。

### 上游 EasyQuery

路径 `C:\Users\Administrator\IdeaProjects\easy-query`（`dromara/easy-query`，基线 tag `3.2.17`，工作区比 tag 多几个提交，比较时以 tag 为准）。可以参考：

- 函数目录：`sql-core/.../func/SQLFunc.java` 及其子接口；
- 各数据库的差异实现：`sql-db-support/`（含 `sql-duckdb`）；
- CTE、分页和窗口的生成方式。

本项目不依赖它。

### 其他 ORM

路径 `C:\Users\Administrator\IdeaProjects\orm-references\`，都是 `--depth 1` 的浅克隆，用 `git pull --depth 1` 更新；需要时可以继续克隆其他 ORM 或驱动仓库到这里。

| 仓库 | 主要参考点 |
| --- | --- |
| `jOOQ` | Java 类型安全 SQL 构建器的标杆：`Field<T>`、`Record` 映射、`mapping(Record::new)`、各方言渲染、CTE。它的 HANA 方言和 Java 8 构建都属于商业版 |
| `jimmer` | 由 APT/KSP 生成的强类型 DSL 的易用性：值为 null 时自动忽略的动态谓词、对象抓取器。本项目不用 APT，只借鉴使用体验 |
| `hibernate-orm` | 方言体系、JDBC 类型绑定与读取、各库函数注册 |
| `kysely` | TS 类型层面的查询构建与方言插件（编译器、方言、驱动三者分离） |
| `drizzle-orm` | 无代码生成的 TS schema 与查询推导、SQL 模板片段 |
| `prisma`、`typeorm`、`mikro-orm` | 对照用：了解完整 ORM 的取舍，本项目不走这条路 |

## 构建与验证

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.jdks\jbr-17.0.14'
.\mvnw.cmd --batch-mode verify          # Maven 3.9.16 由 wrapper 固定

cd typescript
$env:COREPACK_ENABLE_DOWNLOAD_PROMPT = '0'
corepack pnpm install                    # pnpm 11.22.0 由 packageManager 固定
corepack pnpm typecheck
```

目前还没有 CI。加 CI 时参考前身仓库的 workflow：`setup-java` 用 Temurin，并在 Actions 里起数据库服务和 HANA 容器。

## 开发数据库（WSL Debian 上的 Docker）

WSL 和宿主机之间的网络约定见用户级 CLAUDE.md 的"WSL 与 Docker"一节：NAT 模式，双方用各自的地址直接访问，不配 portproxy。本项目的具体情况如下：

- **定义位置**：数据库目前仍由前身仓库定义并启动，在 `C:\Users\Administrator\IdeaProjects\easy_query_pro` 下用 PowerShell 7 运行：
  - `tools/databases.ps1 -Action start|stop|status`：MySQL 和 PostgreSQL，Compose project `eqp-dev`；
  - `tools/hana.ps1 -Action start|test|stop|status`：HANA，Compose project `eqp-hana`；
  - `stop` 保留数据。
- **端口与内存**：

  | 数据库 | 镜像 | 端口 | 库 / 用户 | 内存上限 |
  | --- | --- | --- | --- | --- |
  | MySQL | `mysql:8.4.11` | 53306 | `eqp` / `eqp`，管理员 `root` | 1 GiB |
  | PostgreSQL | `postgres:17.11-bookworm` | 55432 | `eqp` / `eqp`（超级用户） | 512 MiB |
  | HANA Express | `saplabs/hanaexpress:2.00.088.00.20251110.1` | 租户 HXE `39041`，SYSTEMDB `39017` | `SYSTEM` | 8 GiB |

- **访问地址**：宿主机访问 WSL 用 WSL 的 `eth0` 地址（目前是 `172.19.1.143`，WSL 重启后可能变化，用 `wsl -d Debian hostname -I` 查）或 `localhost`；WSL 内访问宿主机用 `172.19.0.1`。
- **密码**：首次启动时随机生成，只保存在容器环境变量中。需要时用 `tools/database-password.ps1 -Database mysql|postgres|hana` 复制到剪贴板。用户要连接信息时，可以在对话中明文给出，但绝不写进仓库文件或提交。
- **HANA**：
  - 业务数据只放在租户 HXE。SYSTEMDB 只用于管理，SAP 不支持在系统库存放业务数据或部署 HDI，所以"只用系统库"的方案不可行。
  - web dispatcher 已关闭，以节省内存。
  - `global_allocation_limit` 设为 80% 时，查询编译阶段会内存不足，所以保持默认值。
  - IDEA 连接 HANA 必须用 ngdbc 2.30.11。
- **内存限制**：只在 Compose 文件里写一处，`mem_limit` 和 `memswap_limit` 相同，脚本通过 `docker compose config` 读回并核对。不要用调大限制来掩盖失败。
- **迁移到本仓库**：等本仓库需要实库测试时，再把基础设施有计划地迁过来，保持相同的限制和规则；不要同时跑两套占用相同端口的实例。
- **IDEA 数据源**：IDEA 已为 MySQL、PostgreSQL、HANA SYSTEMDB 和 HANA HXE 配好四个数据源，可以用 `idea` MCP 的 SQL 工具查询。

## IDE 与 Claude 工具

- **IDEA MCP**：服务名 `idea`，用户级配置，地址 `http://127.0.0.1:64343/stream`，需要 IDEA 开着。Java 的语义问题都走它：符号查找、调用链、重命名、文件问题，以及运行配置、数据库和 DebugTools。保持用户级配置，不在仓库里加 `.mcp.json`。
- **TypeScript LSP**：仓库内插件 `query-lsp@query`，由 `.claude/settings.json` 启用，源文件在 `.claude/marketplace`。它运行 `typescript/` 固定的 TS 7 编译器自带的 LSP。要求 `typescript/` 已经安装依赖，且会话从本仓库目录启动，否则 `${CLAUDE_PROJECT_DIR}` 指向别处。不启动 Claude 也可以验证：`node tools/lsp/verify.mjs typescript typescript/src/index.ts 2 14`。
- **Java LSP**：
  - 暂不配置，也不要用 Eclipse JDT LS（用户已明确拒绝）。
  - JetBrains 的 Java 语言服务器目前只随 VS Code / Cursor 扩展 "Java and Kotlin by IntelliJ IDEA" 提供，许可证限定由人使用。
  - 等 JetBrains 发布面向代理的用法和许可后，再在 `.claude/marketplace/query-lsp/.lsp.json` 里加入 `java`。
- **DebugTools**（IDEA 插件，agent 5.3.1）：
  - JBR 17.0.14 正是官方推荐的热重载 JDK，已实测方法调用和热重载（包括新增方法）。
  - 流程：
    1. `execute_debug_tools_run_configuration` 启动；
    2. IDEA 没有开自动附着，需要 `list_attachable_jvms` 后再 `attach_local_jvm`；
    3. 第一次调用前，从 `GET http://127.0.0.1:<httpPort>/allClassLoader` 取 `defaultIdentity`，作为 `classLoaderIdentity` 传入；
    4. 改代码后用 `run_and_invoke`（`compileBeforeReload: true`，非 Spring 程序设 `springReadiness: SKIP`）；
    5. 用 `manage_run_instance STOP` 结束。
  - 目标程序要常驻，才能附着。
  - 官方建议关闭 IDEA 的 Async Stack Traces（Settings → Build, Execution, Deployment → Debugger），目前仍是开着的。

## Git 与公开仓库

- **仓库**：GitHub `kenconnet666/query`，公开，默认分支 `master`。
- **提交与推送**：完成的工作直接提交并推送，不必询问：先建分支，再推这个分支。force-push、改写历史、推 `master`、合并 PR 都需要用户明确要求。
- **公开仓库的限制**：仓库是公开的，绝不写入凭据，也不写入实际使用方的信息（公司、项目名、内部路径、业务常量）。使用方的说明只保存在本机 Claude 项目记忆中，文档里统称"内部 ERP 分析大屏"。
- **提交格式**：commit message 用英文，结尾加 `Co-Authored-By` trailer；文档用中文。
- **行尾**：由 `.gitattributes` 统一为 LF，`*.cmd` 为 CRLF。

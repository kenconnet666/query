# query 项目约定

开工前先读 [.claude/HANDOFF.md](.claude/HANDOFF.md)（当前进度、待定事项和下一步），再读 [0001 方向决定](docs/decisions/0001-standalone-analytic-query-library.md)、[0002 API 与架构](docs/decisions/0002-api-architecture.md)、[0003 通往 1.0 的蓝图](docs/decisions/0003-road-to-1.0.md)、[0004 生产就绪](docs/decisions/0004-production-readiness.md)、[0005 API 简化审查](docs/decisions/0005-api-simplification.md) 和[场景对照](docs/scenarios.md)。

## 定位与基线

- **定位**：面向分析型复杂查询的 SQL 构建库，Java 与 TypeScript 各一份实现。不依赖 EasyQuery，也不做完整 ORM：不做实体跟踪、导航、DDL 和迁移。
- **取舍顺序**：简洁灵活、功能强大、方便扩展优先，其次是类型安全，再次是性能；元数据、映射和渲染必须缓存或惰性计算。
- **Java**：
  - 17 及以上，`maven.compiler.release=17`。本机 JDK 为 `C:\Users\lionheart\.jdks\jbr-17.0.14`，IDEA 中的 SDK 名为 `jbr-17`。
  - 实体用注解声明，可以要求用户使用 Lombok。
  - 不使用注解处理器（APT），也不在使用方生成代码。
- **TypeScript**：以 7 为唯一基准，开发编译器固定为 7.0.2（原生编译器），不再兼顾 6.0。使用标准装饰器，不用 `experimentalDecorators`。工程是 `typescript/` 下的 pnpm 工作区：`packages/core|hana|postgres|mysql`（包名 `@zerodep-dev/query-*`）和私有测试包 `test`。
- **首批方言**：SAP HANA、PostgreSQL、MySQL 三者同步推进，任何能力都要在三个方言上一起实现和验证；DuckDB 延后。
- **方言不抹平**：用户必须选定方言包并创建对应的实例。核心包只包含三库语义一致的能力，方言专属能力只出现在对应的方言包里。
- **文档**：
  - API 方向以 0002 为准；
  - 用法写在 [docs/api.md](docs/api.md)（Java）和 [docs/api-typescript.md](docs/api-typescript.md)（TS），API 变化时同步更新；
  - 其余文档只记录决定、取舍和验证证据，过时的内容直接删改，不堆叠。

## 必须参考的本地仓库

设计任何能力之前，先看下面这些仓库怎么做：核对版本和适用性，再结合本项目的规模转化，不照抄。它们都只读，不在其中提交改动。

### 前身：EasyQuery Pro

路径 `C:\Users\lionheart\IdeaProjects\easy_query_pro`（GitHub `kenconnet666/easy_query_pro`，分支 `codex/bootstrap-easyquery-pro`）。它是基于 EasyQuery 3.2.17 的 Java 8 增强包，外加一个 TS ORM 子项目。可以借鉴的部分：

- **HANA 的实测知识**：`docs/hana-*.md`，包括类型、函数和函数审计、字符类型、LOB、JSON、层级、全文检索、参数化视图、生成键、写入、HDI 与迁移。
- **三库差异清单与报表能力**：`docs/dsl-design.md` 列出三库的类型和操作清单；`docs/reporting-*.md`、`dsl-windows.md`、`dsl-statistics.md`、`dsl-groups.md`、`dsl-temporal.md`、`dsl-comparison.md` 覆盖报表、窗口、统计、分组、时间与比较；`native-numeric.md` 和 `mysql-*.md`、`pgsql-*.md` 覆盖原生数值与各库专属类型。
- **验证证据**：`docs/execution.md`（执行记录）和 `docs/compatibility.md` 记录了哪些行为已经在真实数据库上证实。
- **TS 起点**：`ts-orm/`。设计说明在 `ts-orm/docs/`（子查询、报表、ROLLUP、统计、时间、JSON、事务、批量参数上限、PG 数组）；可复用其不可变查询、typed codec 和结构化编译的思路。它的方言模型是写死的联合类型，正是要重构的部分。
- **基础设施**：`infra/`（Compose）、`tools/*.ps1`（数据库脚本），以及 `.github/workflows/ci.yml` 和 `ts-orm.yml`（GitHub Actions 中运行 MySQL、PostgreSQL 和 HANA 的方式）。
- **不要沿用**：与 EasyQuery 耦合的代码（继承上游内部类、`ProDsl.of(...)` 包装、多步安装）是这次重做要摆脱的东西；思路可以借鉴，代码重写。

### 上游 EasyQuery

路径 `C:\Users\lionheart\IdeaProjects\easy-query`（`dromara/easy-query`，基线为提交 `2b25e9510 build: 3.2.17`，本机没有 `3.2.17` tag）。它是重要参考，Claude 会话需要把这个目录加入工作目录才能读取。可以参考：

- 函数目录：`sql-core/.../func/SQLFunc.java` 及其子接口；
- 各数据库的差异实现：`sql-db-support/`（含 `sql-duckdb`）；
- CTE、分页和窗口的生成方式；
- 导航属性（`@Navigate`）与隐式连接、隐式子查询；
- 按 DTO 生成条件（`whereObject`、`@EasyWhereCondition`）；
- VO 对象树映射（`selectAutoInclude`、`@NavigateFlat`）。

本项目不依赖它。

### 其他 ORM

路径 `C:\Users\lionheart\IdeaProjects\orm-references\`（本机尚未克隆，按需创建），都是 `--depth 1` 的浅克隆，用 `git pull --depth 1` 更新；需要时可以继续克隆其他 ORM 或驱动仓库到这里。

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
$env:JAVA_HOME = 'C:\Users\lionheart\.jdks\jbr-17.0.14'
.\mvnw.cmd --batch-mode verify          # Maven 3.9.16 由 wrapper 固定

cd typescript
$env:COREPACK_ENABLE_DOWNLOAD_PROMPT = '0'
corepack pnpm install                    # pnpm 11.22.0 由 packageManager 固定
corepack pnpm test                       # tsc -b，再跑单元、编译期与金样例测试
corepack pnpm test:live                  # 三库实库场景，变量见下文
```

- **模块**：根 `pom.xml` 是聚合父工程，Java 模块在 `java/query-core|hana|postgres|mysql|spring`。`query-spring` 的 spring-jdbc 为 `provided`，默认按 5.3 编译，用 `-Dspring.version=` 换版本测试。TS 用 `tsc -b` 项目引用构建，测试是 `node:test`，编译后在 `typescript/test/dist` 里运行。
- **生成代码**：`From1..From8` 和 `Fn` 由 `node tools/generate-java-scopes.mjs` 生成，改生成器后重新运行并提交生成结果，不要手改。
- **发布**：发布到 Maven 中央仓库和 npm 的步骤见 [docs/release.md](docs/release.md)。版本号同时改根 POM、各模块的 parent、TS 包和 `docs/api.md` 的依赖示例，变化记入 `CHANGELOG.md`。
- **完整测试交给 CI**：`.github/workflows/ci.yml` 并行运行以下 job，job 名和步骤名用中文：
  - Java 单元、渲染与金样例测试（Maven `--threads 1C` 并行构建模块；带 `release` profile、跳过签名，顺带检查 javadoc）；
  - JDK 21 与 25 上的 Java 单元测试（字节码仍按 release 17 生成）；
  - PostgreSQL、MySQL 实库：按"库 × 语言"拆成 4 个 job；
  - HANA 实库：一个 job 先跑 Java 再跑 TS，以免启动两个 8 GiB 的实例；
  - Spring 适配在 6.2 与 7.0 下的事务测试（5.3 由 Java 单元测试 job 覆盖）；
  - TypeScript 编译、编译期检查、单元与金样例测试。

  容器定义在 `infra/`。
- **CI 的提速规则**：
  - 实库 job 先在后台启动数据库，安装、编译、构建都放在数据库启动的等待时间里，最后才等待就绪；
  - 托管 runner 用完即销毁，不写清理步骤；
  - Node 用 runner 预装的 24.x，TS 编译器与 pnpm 仍由锁文件和 `packageManager` 固定；
  - 关键路径是 HANA 的拉取与启动（约 26 秒加 200 秒），调整前先用 `gh api repos/kenconnet666/query/actions/runs/<id>/jobs` 看各步骤耗时。
- **本地只跑与改动相关的测试**：
  - 用 `-pl <模块>`，必要时加 `-Dtest=...` 和 `-Dsurefire.failIfNoSpecifiedTests=false`；
  - PowerShell 里带点的 `-D` 参数要加引号；
  - TS 编译后可以只跑一个文件：`node --test test/dist/unit/render.test.js`。
- **金样例**（`spec/render/<场景>.<方言>.sql`）：
  - 两端的方言测试都读取它，渲染出的 SQL 和参数必须完全相同；Java 的样例清单在 `testing/SpecCases` 和各方言的 `*SpecTest`，TS 的在 `test/src/spec-cases.ts`；
  - 改了渲染或新增能力时，两端同时改，再用 Java 重写文件：`$env:QUERY_SPEC_WRITE = '1'` 后运行 `*SpecTest`，然后审查 diff；
  - 投影的列名要两端一致：Java 的函数式投影用 `.as("key")` 对上 TS 的对象键。
- **提交前排查 CI 风险**：
  - 文件的可执行位（`mvnw`、`infra/hana/*.sh` 要求 100755）；
  - 行尾；
  - 新模块是否已加进 workflow；
  - 实库变量名是否与 workflow 一致；
  - `typescript/pnpm-lock.yaml` 是否与各 `package.json` 一致（CI 用 `--frozen-lockfile` 安装）。
- **实库测试的变量**：
  - 共享场景：Java 在 `query-core` 的 test-jar 里（`testing/LiveScenarios`），各方言的 `*LiveTest` 继承它；TS 在 `test/src/live/live.ts`，各方言一个 `*.live.test.ts`；
  - 两端读取同一组 `QUERY_{HANA|PG|MYSQL}_{URL|USER|PASSWORD}`（TS 从 JDBC URL 里解析主机、端口和库名），缺少时跳过；
  - CI 额外设置 `QUERY_REQUIRE_LIVE=1`，缺少变量时直接失败。
  - 本机的密码在各数据库目录自己的 `.env`（`infra/mysql/.env`、`infra/postgres/.env`、`infra/hana/.env`，不入库），按需读进环境变量，不打印也不落盘：

    ```powershell
    $ip = ((wsl -d Debian hostname -I).Trim() -split ' ')[0]   # WSL 的地址，WSL 重启后会变
    $mysql = (Get-Content infra/mysql/.env | ConvertFrom-StringData).QUERY_DB_PASSWORD
    $pg = (Get-Content infra/postgres/.env | ConvertFrom-StringData).QUERY_DB_PASSWORD
    $hana = (Get-Content infra/hana/.env | ConvertFrom-StringData).QUERY_HANA_PASSWORD
    $env:QUERY_PG_URL = "jdbc:postgresql://${ip}:55432/query"; $env:QUERY_PG_USER = 'query'; $env:QUERY_PG_PASSWORD = $pg
    $env:QUERY_MYSQL_URL = "jdbc:mysql://${ip}:53306/query?sslMode=DISABLED&allowPublicKeyRetrieval=true"; $env:QUERY_MYSQL_USER = 'root'; $env:QUERY_MYSQL_PASSWORD = $mysql
    $env:QUERY_HANA_URL = "jdbc:sap://${ip}:39041"; $env:QUERY_HANA_USER = 'SYSTEM'; $env:QUERY_HANA_PASSWORD = $hana
    ```

  - 测试会在各库的 `QUERY_IT` schema 里重建测试表。

## 开发数据库（WSL Debian 上的 Docker）

WSL 和宿主机之间的网络约定见用户级 CLAUDE.md 的"WSL 与 Docker"一节：NAT 模式，双方用各自的地址直接访问，不配 portproxy。本项目的具体情况如下：

- **定义位置**：`infra/mysql/`、`infra/postgres/`、`infra/hana/` 各是一个自包含的目录（`compose.yaml`、`Dockerfile`、`.env.example`），开发和 CI 共用，可以单独复制到别的机器部署。每个 `compose.yaml` 开头的注释写了完整的部署和使用步骤，在对应目录下执行 `docker compose up -d --build` 即可。
- **镜像**：
  - PostgreSQL 用官方 Debian（bookworm）镜像，MySQL 8.4 官方没有 Debian 变体，用官方 Oracle Linux 镜像；两者都由 `infra/Dockerfile` 加装 ping、curl、ss、nano 等运维工具，并把时区设为 `Asia/Singapore`。
  - HANA 是 SAP 的 SUSE 镜像，没有软件源，不能加装工具；它的 `Dockerfile` 只把启动、健康检查和配置脚本打进镜像，目录因此不依赖绑定挂载。
  - PostgreSQL 目录的 `.env` 里可设 `APT_MIRROR=mirrors.aliyun.com`，国内网络慢时用。
- **端口与内存**：

  | 数据库 | 镜像 | 端口 | 库 / 用户 | 内存上限 |
  | --- | --- | --- | --- | --- |
  | MySQL | `mysql:8.4.11` | 53306 | `query` / `query`，管理员 `root` | 1 GiB |
  | PostgreSQL | `postgres:17.11-bookworm` | 55432 | `query` / `query` | 512 MiB |
  | HANA Express | `saplabs/hanaexpress:2.00.088.00.20251110.1` | 租户 HXE `39041`，SYSTEMDB `39017` | `SYSTEM` | 8 GiB |

- **访问地址**：宿主机访问数据库统一用 WSL 的 `172.x` 地址（`wsl -d Debian hostname -I` 的第一项，目前是 `172.19.1.143`，WSL 重启后可能变化），不用 `localhost`；WSL 内访问宿主机用 `172.19.0.1`。
- **密码**：写在各目录自己的 `.env`（已加入 `.gitignore`），首次部署时用 `compose.yaml` 开头注释里的命令随机生成，模板是同目录的 `.env.example`。MySQL 和 PostgreSQL 用 `QUERY_DB_PASSWORD`，HANA 用 `QUERY_HANA_PASSWORD`。用户要连接信息时，可以在对话中明文给出，但绝不写进仓库文件或提交。
- **数据**：用命名卷保存，`stop` 和 `down` 保留数据，`down -v` 才删除。
- **HANA**：
  - 业务数据只放在租户 HXE。SYSTEMDB 只用于管理，SAP 不支持在系统库存放业务数据或部署 HDI，所以"只用系统库"的方案不可行。
  - 首次启动后要在 `infra/hana/` 里执行一次 `docker compose exec hana /bin/bash /query/configure.sh`（关闭 web dispatcher，以节省内存）。
  - `global_allocation_limit` 设为 80% 时，查询编译阶段会内存不足，所以保持默认值。
  - IDEA 连接 HANA 必须用 ngdbc 2.30.11。
- **内存限制**：只在各自的 `compose.yaml` 里写一处，`mem_limit` 和 `memswap_limit` 相同，CI 通过 `docker compose config` 读回并核对容器实际生效。不要用调大限制来掩盖失败。
- **前身仓库的旧实例**：`easy_query_pro` 里的脚本启动的是库名 `eqp` 的旧容器（项目 `eqp-dev`、`eqp-hana`），端口与本仓库相同，不要同时运行；切换时先 stop 旧容器。
- **IDEA 数据源**：IDEA 已为 MySQL、PostgreSQL、HANA SYSTEMDB 和 HANA HXE 配好四个数据源，可以用 `idea` MCP 的数据库工具查询；它们仍指向前身仓库的旧实例（库名 `eqp`），切到本仓库的容器后要把库名和用户改成 `query`。2026-10-21 的实测结论如下：
  - **连接 ID**：
    - PostgreSQL `7d39150c-eb7e-44db-b0e5-2aa784f1714f`（databaseName `eqp`，schema `public`）；
    - HANA HXE `30a1b705-a891-4d16-a137-d57e09706aab`（databaseName 留空，schema `SYSTEM`）；
    - MySQL `6c21da65-0c0a-47ca-b636-6644156e9150`。
  - **可用的工具**：`test_database_connection`（能看到版本和大小写规则）、`list_database_schemas`、`list_schema_objects`、`get_database_object_description`、`preview_table_data`。
  - **`execute_sql_query`**：能执行 SELECT、DDL 和 DML，一次调用中的多条语句也能执行，返回最后一条的结果。
  - **限制**：语句出错时只返回 "Unknown error"，看不到错误码，需要错误详情时改用 JDBC（例如写一个测试）。

## IDE 与 Claude 工具

- **IDEA MCP**：服务名 `idea`，用户级配置，地址 `http://127.0.0.1:64343/stream`，需要 IDEA 开着。Java 的语义问题都走它：符号查找、调用链、重命名、文件问题，以及运行配置、数据库和 DebugTools。保持用户级配置，不在仓库里加 `.mcp.json`。
- **TypeScript LSP**：仓库内插件 `query-lsp@query`，由 `.claude/settings.json` 启用，源文件在 `.claude/marketplace`。它运行 `typescript/` 固定的 TS 7 编译器自带的 LSP。要求 `typescript/` 已经安装依赖，且会话从本仓库目录启动，否则 `${CLAUDE_PROJECT_DIR}` 指向别处。不启动 Claude 也可以验证：`node tools/lsp/verify.mjs typescript typescript/packages/core/src/index.ts 2 3`。
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
- **提交格式**：commit message 用中文，结尾加 `Co-Authored-By` trailer；文档用中文。
- **测试命名**：测试方法名和 `@DisplayName` 用中文，写明被验证的行为。
- **注释**：每个类型和方法都写中文注释（Java 用 Javadoc，TS 用 TSDoc），说明用途和用法；代码标识符、日志和异常信息仍用英文。
- **行尾**：由 `.gitattributes` 统一为 LF，`*.cmd` 为 CRLF。

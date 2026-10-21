# 交接（2026-10-21）

长期约定见 [CLAUDE.md](../CLAUDE.md)，本文件只记录当前进度。下一次会在另一台电脑上继续，先看[换电脑后的准备](#换电脑后的准备)。

## 当前状态

### 分支与 PR

- 三个分支依次叠加，都已推送：
  1. `feat/java-core`：第一期 Java；
  2. `feat/typescript-core`：第二期 TypeScript；
  3. `feat/real-query-parity`：真实查询对照、补齐的能力、Spring 适配、CI 提速、SQL 级列转换与 ROLLUP/分组集合。
- [PR #1](https://github.com/kenconnet666/query/pull/1) 把 `feat/real-query-parity` 合并进 `master`，三个分支的内容都在里面。
  - 按用户要求**不开自动合并**，由用户手动合并。
  - 新电脑上继续时，在 `feat/real-query-parity` 上开发即可。
  - 也可以等 PR 合并后从 `master` 新开分支。
- Codex 评审机器人会在 PR 上留评论。到 2026-10-21 为止，它只发了一条"评审进行中"的状态摘要，没有提出意见。
- `master` 目前只有方向文档、TS 工程骨架和 LSP 插件。

### 已完成

- **文档**：
  - [0001](../docs/decisions/0001-standalone-analytic-query-library.md)：方向决定；从 HANA 目录生成实体的工具暂时不做；
  - [0002](../docs/decisions/0002-api-architecture.md)：API 与架构；第 9 条是 TS，第 10 条是常量字面量规则，第 11 条是常量行，第 12 条是 SQL 级列转换，第 13 条是 ROLLUP 与分组集合；
  - 用法说明：[Java](../docs/api.md)、[TypeScript](../docs/api-typescript.md)；
  - [场景对照](../docs/scenarios.md)。
- **Java**：`query-core`、`query-hana`、`query-postgres`、`query-mysql`、`query-spring` 五个模块：
  - 注解实体、getter 引用、lambda 作用域（1 到 8 张表），以及函数式、按名、单列三种投影；
  - CTE 提升、UNION、窗口函数、子查询；
  - 全局过滤器、分页、流式读取、原生 SQL、结果缓存、映射计划缓存；
  - 三个方言的门面、专属函数与子句；
  - `SpringConnections` 通过 `DataSourceUtils` 取连接，查询加入外层的 `@Transactional`。
- **TypeScript**：`typescript/` 下的 pnpm 工作区，包括 `@query/core|hana|postgres|mysql`：
  - 标准装饰器实体；
  - 回调作用域按位置跟踪 LEFT JOIN 的可空性；
  - 对象投影即列标签；
  - 驱动适配 `@sap/hana-client`、`pg`、`mysql2`。
- **真实查询对照**：内部 ERP 分析大屏的三条查询在本机 HANA 上用合成数据对比，原 SQL 与 Java、TS 改写各 21 组参数结果一致。由此补上：
  - 常量行 `values`；
  - `substring`、`left`、`right`、`replace`、`negate`，以及接收列表的 `plus`；
  - HANA 的 `likeRegexpr`、`toNvarchar`；
  - SELECT 列表与分组表达式里的常量写成字面量。
- **SQL 级列转换**（0002 第 12 条）：`@ReadAs("…{0}…")` 与内置的 `@BlankAsNull`、`@Trimmed`（TS 为 `readAs`、`blankAsNull`、`trimmed`），列在语句的每个位置都按模板读取。对照程序换用 `@BlankAsNull` 后两端仍是 21 组全部一致。
- **ROLLUP 与分组集合**（0002 第 13 条）：核心包 `groupByRollup`、`grouping`，MySQL 渲染为 `WITH ROLLUP`；HANA、PostgreSQL 包里有 `cube`、`groupingSets`。
- **PR 评审意见**（Codex 机器人的两条 P1、两条 P2，均已修复、回复并标为已解决；机器人随后提示用量已到上限，暂时不会再评审）：
  - 右侧混用 UNION 与 UNION ALL 时加括号，保持原有分组（Java `Query.Combination`，TS `State.groups`）；
  - Java 函数式投影重放时按表达式结构核对（`Expr.shape()`），按行值在两个 `coalesce(...)` 之间切换也会报错；
  - 原生 SQL 的参数扫描改由方言钩子 `quotedEnd` 判断引用：PostgreSQL 认美元引号，MySQL 认反引号和反斜杠转义（TS 以前对所有方言都认反引号，Java 都不认，现在两端一致）；
  - PostgreSQL 的流式读取改由方言钩子 `Dialect.openCursor` 准备游标：自动提交的连接上临时开事务，外层事务不动，默认每批 1000 行。MySQL 仍默认缓冲，`fetchSize(Integer.MIN_VALUE)` 现在可以生效（以前只接受正数）。是否让 MySQL 默认逐行读取待定，见下文。
- **两端一致**：`spec/render/` 下 73 个金样例，Java 与 TS 的测试都读取，SQL 与参数逐字相同。
- **测试**（中文命名，本机三库全部通过）：
  - Java 229 个（含 76 个金样例、63 个实库场景、javac 编译期检查、3 个 Spring 事务测试）；
  - TS 210 个（含 76 个金样例、61 个实库场景、16 条 `@ts-expect-error` 编译期检查）。
- **CI**：
  - 并行的 job：Java 单元、TS 单元、Spring 6.2 与 7.0、PostgreSQL 和 MySQL 按"库 × 语言"拆成的 4 个 job、HANA（一个 job 跑两种语言）。
  - 提速规则见 CLAUDE.md 的"CI 的提速规则"。
  - 调整前，整次运行约 5 分钟，关键路径是 HANA：拉镜像约 26 秒，启动约 200 秒。
  - 之后改成：后台启动数据库，构建与数据库启动重叠，去掉清理步骤，HANA 健康检查改为 3 秒一次。改后整次运行约 4 分 20 秒，关键路径仍是 HANA job（约 260 秒）。

### 待用户决定

- 是否手动合并 PR #1。
- 前身仓库是否冻结（0001 里唯一的待定项）。
- Java 的 MySQL 流式读取是否默认改用 `Integer.MIN_VALUE`：好处是真正逐行读取，代价是读完之前同一连接不能执行别的语句（在 Spring 事务里边读边查会报错）。

## 下一步（建议）

1. 看 PR #1 上这次推送的 CI 是否全绿。
2. **第三期剩余**（见 0002 的分期）：
   - 一对多的批量组装；
   - 按请求对象生成条件；
   - HANA 计算视图与带参数的表函数、JSON。
3. 在使用方仓库挑一个服务试点迁移。需要用户同意，目前使用方代码只读。

## 换电脑后的准备

仓库里的东西 `git clone` 后就齐了。下面这些只存在于旧电脑上，新电脑需要重新准备或手动拷贝：

| 东西 | 在旧电脑上的位置 | 新电脑上怎么办 |
| --- | --- | --- |
| 本仓库 | `C:\Users\Administrator\IdeaProjects\query` | `git clone https://github.com/kenconnet666/query`，切到 `feat/real-query-parity` |
| 参考仓库（只读） | `IdeaProjects\easy_query_pro`、`IdeaProjects\easy-query`、`IdeaProjects\orm-references\*` | 按 CLAUDE.md 的说明重新克隆；不影响构建，只用于设计参考 |
| 使用方代码（只读） | 旧电脑的本地目录 | 只在 Claude 项目记忆里有路径和说明，不进公开仓库 |
| Claude 项目记忆 | `C:\Users\Administrator\.claude\projects\C--Users-Administrator-IdeaProjects-query\memory\` | 含使用方说明和设计证据，按需手动拷贝到新电脑对应目录；没有它也能继续，只是缺少使用方背景 |
| 开发数据库 | WSL Debian 里的 Docker，由前身仓库的 `tools/*.ps1` 启动 | 见下文；没有本机库也能开发，实库测试交给 CI |
| IDEA 的 MCP 与数据源 | 用户级配置 `idea` 与四个数据源 | 在新电脑的 IDEA 中重新配置，连接 ID 会变 |
| 对照程序 | 会话临时目录，已经不在了 | 不需要；结论在 0002 里 |

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

- 可以照前身仓库的 `tools/databases.ps1`、`tools/hana.ps1` 启动，也可以用本仓库的 `infra/compose.yaml`、`infra/hana.compose.yaml`（CI 用的就是它们，内存限制相同）。
- 然后按 CLAUDE.md 的"实库测试的变量"设置 `QUERY_*`。
- CLAUDE.md 里的容器名（`eqp-dev-*`、`eqp-hana-hana-1`）和 WSL 地址都是旧电脑的，在新电脑上要换成实际值。
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
- **PowerShell 传 Maven 参数**：带点的 `-D` 参数要加引号，例如 `'-Dtest=HanaLiveTest'`。
- **金样例**：改了渲染以后，用 `$env:QUERY_SPEC_WRITE = '1'` 运行 `*SpecTest` 重写 `spec/render/`，审查 diff，再跑 TS 的 `spec.test.js` 确认两端一致。
- **Java 泛型**：形如 `Getter<?, ?>` 的通配符参数接收不了方法引用，公共 API 要写成泛型 `<E> Getter<? super E, ?>`；同类型的多参数运算要做成实例方法（理由见 0002 第 4 条）。
- **TS 类型**：
  - `instanceof` 取反会把 `w.dialect` 收窄成 `never`，先存到 `const dialect: Dialect` 再判断；
  - getter 在类型上像只读字段，`mapTo` 的字段检查已排除只读属性；
  - 只为编译期检查写的错误代码要放进不会被调用的函数里，否则测试运行时会真的执行它。
- **Bash 工具里的转义**：在 heredoc 或 `python -` 里写入文本时，反斜杠转义（例如 Java 字符串里的换行符转义）会被提前展开成真正的换行，字符串因此断开。含反斜杠的改动用编辑工具写。
- **HANA 内存**：HANA 会把 cgroup 内存限制当作物理内存。8 GiB 是 SAP 公布的最低值，测试变慢或内存不足时先查看占用，不要调大限制。
- **javac**：Java 17 上 switch 模式匹配仍是预览特性，渲染器用节点自己的 `render` 方法。

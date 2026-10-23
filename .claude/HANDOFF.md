# 交接（2026-10-21）

长期约定见 [CLAUDE.md](../CLAUDE.md)，本文件只记录当前进度。换电脑继续时先看[换电脑后的准备](#换电脑后的准备)。

[0003](../docs/decisions/0003-road-to-1.0.md) 的 M1 到 M6、[0004 生产就绪](../docs/decisions/0004-production-readiness.md)和 [0005 API 简化审查](../docs/decisions/0005-api-simplification.md)都已完成，版本号是 1.0.0。只差维护者手动发布到 Maven 中央仓库。

## 当前状态

### 分支

- 第一到第三期在 [PR #1](https://github.com/kenconnet666/query/pull/1) 合并进了 `master`。
- M1 到 M6 在 `feat/v1` 上，已通过 [PR #2](https://github.com/kenconnet666/query/pull/2) 合并进 `master`。
- 生产就绪已通过 [PR #3](https://github.com/kenconnet666/query/pull/3) 合并进 `master`。
- API 简化在 `feat/api-simplify` 上，通过 PR #4 合并进 `master`。
- 用户已授权在 CI 全绿后自行合并 PR，不必再问。新的工作从 `master` 开分支。

### 1.0 完成了什么

- **M1 表达力审计**：读了使用方的 121 段 HANA SQL，列出 8 个缺口。不需要新 API 的写法整理进了[迁移指南](../docs/migration.md)。
- **M2 补缺口**：两端、三库同时补齐，都有金样例和实库测试：
  - `locate`；起止位置是表达式的 `substring`、`left`、`right`；
  - `floor`、`ceil`；
  - 简单 CASE `caseOf`；多参数 `coalesce`；
  - 方言包里的 `greatest`、`least`，HANA 的 `toInteger`；
  - Java 命名投影自动补齐没有列出的属性。
- **M3 结果分组**：`Nest.into`（TS 为 `nest`）。
- **M4 扩大对照**：8 条代表性查询、36 组参数，原 SQL、Java 改写、TS 改写三者的结果逐行一致。顺带修了 TS 的 `lit`/`val` 字面量类型问题。
- **M5 1.0 准备**：
  - 补齐公开 API 的测试；
  - 修了保留字作别名的 bug；
  - 连接泄漏与并发测试；
  - 性能粗测，并让函数式投影的结构核对每个查询只算一次；
  - 日志监听器示例。
- **M6 发布准备**：
  - 中央仓库要求的 POM 元数据、`LICENSE`、`release` profile（源码包、javadoc 包、签名、central-publishing）；
  - `Automatic-Module-Name`；
  - CI 带 release profile 构建；
  - [CHANGELOG](../CHANGELOG.md)、README 快速上手、[迁移指南](../docs/migration.md)、[手动发布步骤](../docs/release.md)。
- 各项的证据与取舍记在 0003 的"验证证据"一节。

### 生产就绪完成了什么（0004）

- **查询超时**：数据库实例的默认值，查询和原生 SQL 可以覆盖。三库都在服务端取消语句，异常能用 `isTimeout()` / `timedOut` 区分。
- **Spring 事务超时**：`ConnectionProvider.prepared` 钩子，行为与 JdbcTemplate 一致。
- **参数个数上限**：超过时在连接数据库之前报出可读的错误。
- **字面量加固**：
  - PostgreSQL 的值含反斜杠时写成 `E'...'`；
  - `NaN`、`Infinity` 不写成字面量；
  - 三库都做了恶意文本的往返测试。
- **CI**：增加 JDK 21 与 25。
- **文档**：API 说明第 10 节（安全、上线前检查、兼容性承诺）。
- **影子试点**：在使用方同款技术栈上验证通过（Spring Boot 2.7.11、Druid、dynamic-datasource、ngdbc 2.29.7）。
- **顺带修复**：Java 分页遇到 NULL 行时抛 NPE；TS 约 7 万个参数时渲染栈溢出。

### API 简化完成了什么（0005）

- **Java 去掉包装**：只有一个分组键、排序键或命名列时直接写表达式，裸表达式按升序；靠 sealed 标记接口 `Keys`、`OrderItem` 实现。
- **EXISTS**：`exists` / `notExists` 直接接收作用域，两端删除 `selectOne()`。
- **删除 `whereIf`**：两端都删，它与 `onlyIf` 重复。
- **比较简写**：Java 的 `gt`/`ge`/`lt`/`le` 也支持 `(表, getter)` 简写。
- **文档**：用法说明开头加了速查表，讲清 `val` 与 `lit` 的区别；示例全部换成新写法。金样例不变。

### 测试（本机三库全部通过）

- **Java**：`-P release -Dgpg.skip verify` 通过。
  - 各模块的测试数：core 88，HANA 67，PostgreSQL 71，MySQL 64，Spring 5；
  - 金样例 88 个文件，覆盖 38 个场景。
- **TS**：单元、编译期与金样例 169 个，实库 91 个。

## 待用户决定或执行

- **1.0.0 已发布**（2026-10-22）：Maven 中央仓库 `org.dpdns.zerodep:*:1.0.0`，npm `@zerodep-dev/query-*@1.0.0`，标签 `v1.0.0` 指向实际发布的提交 `966a236`。之后发版按 [docs/release.md](../docs/release.md)；命名空间的 TXT 记录保留，不要删。
- **前身仓库是否冻结**：0001 里唯一的待定项。
- **文档站**：用户说之后会用前端组件库另行安排，1.0 只提供仓库里的 Markdown。

## 下一步（建议）

1. 合并后看 `master` 上的 CI 是否全绿。
2. 用 `versions:set` 进入 `1.1.0-SNAPSHOT`，`CHANGELOG.md` 顶部加"未发布"，并决定要不要建 GitHub Release（正文取 CHANGELOG 的 1.0.0 一节）。
3. 在使用方仓库挑一个服务试点迁移，按迁移指南改写，并逐条对照结果。0003 把试点放在 1.0 之后，需要用户同意；使用方代码目前只读。影子试点已证明技术栈兼容。
4. 0003 列出的延后项，等有使用证据再做：
   - DuckDB；
   - HANA 计算视图与表函数、JSON、层级查询；
   - 两次查询的批量加载。

## 换电脑后的准备

仓库里的东西 `git clone` 后就齐了。下面这些只存在于旧电脑上，新电脑需要重新准备或手动拷贝：

| 东西 | 在旧电脑上的位置 | 新电脑上怎么办 |
| --- | --- | --- |
| 本仓库 | `C:\Users\Administrator\IdeaProjects\query` | `git clone https://github.com/kenconnet666/query`，在 `master` 上新开分支 |
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
- **Bash 工具里的大段 heredoc**：内容很长且引号很多时会报 "unexpected EOF"。先用写文件工具把脚本写到临时目录，再执行它。
- **HANA 内存**：HANA 会把 cgroup 内存限制当作物理内存。8 GiB 是 SAP 公布的最低值，测试变慢或内存不足时先查看占用，不要调大限制。
- **javac**：Java 17 上 switch 模式匹配仍是预览特性，渲染器用节点自己的 `render` 方法。

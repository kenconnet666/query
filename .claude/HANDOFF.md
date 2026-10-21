# 交接（2026-10-21）

新会话从本仓库目录 `C:\Users\Administrator\IdeaProjects\query` 启动。长期约定见 [CLAUDE.md](../CLAUDE.md)，本文件只记录当前进度。

## 当前状态

### 分支

- 全部工作都已快进合并到 `master` 并推送，两个功能分支（`docs/direction`、`tooling/claude-lsp`）已删除。
- `master` 现在包含：IDEA 生成的 Maven 骨架、README、[0001 方向决定](../docs/decisions/0001-standalone-analytic-query-library.md)、Maven wrapper、`.gitattributes`、`typescript/` 工程（TS 7.0.2）、TypeScript LSP 插件、`tools/lsp/verify.mjs`、项目 `CLAUDE.md` 与本交接文件。
- 新工作照常先建分支，再推送这个分支。
- 还没有 CI。

### 已完成

- **方向评估**：结论写在 0001。新仓库不依赖 EasyQuery，Java 17、TS 6 起步，不用 APT，定位为分析查询构建器。
- **编译验证**：JBR 17.0.14 + Maven 3.9.16 + `release 17` 构建通过（class 主版本 61）；`typescript/` 的 `typecheck` 通过。
- **DebugTools**：在 JBR 17 上实测方法调用和热重载（包括新增方法）均可用。
- **TypeScript LSP**：已按项目级插件配置并验证。Java 暂时不配置 LSP，原因见 CLAUDE.md。
- **占位文件**：`src/main/java/.../Main.java`（IDEA 模板）和 `typescript/src/index.ts` 只是占位，第一批正式代码进来时删掉。

### 待用户决定（来自 0001）

1. 是否允许一个独立的生成工具（CLI 或 Maven 插件，不是 APT）从 HANA 目录生成表声明源码？
2. TypeScript 与 Java 同步推进，还是 Java 先行？
3. 前身仓库 `easy_query_pro` 是否冻结？它停在 `8dd39a4`，CI 全绿，没有开 PR。
4. 方言顺序是否确认为 HANA、DuckDB 首发，PostgreSQL、MySQL 其次？

## 下一步（建议）

用实际使用方的三类真实查询写 Java API 原型。使用方的技术栈、查询写法和痛点记录在本机 Claude 项目记忆里，不在仓库中；读真实 SQL 时只读，不改动使用方的代码。三类查询是：

1. CTE + `EXISTS` + 动态条件；
2. 口径复用 + 可选过滤；
3. 窗口函数或 `QUALIFY`。

原型要验证四点：
- 用构造器引用做类型化投影，按参数个数生成重载；
- 用 record 方法引用取 CTE 的列（`SerializedLambda`），对比为每个 CTE 写显式形状类的方案；
- 口径能写成普通方法来复用，`whereIf` 能取代 `:flag = FALSE OR ...`；
- HANA 和 DuckDB 两种方言的渲染。

原型通过后，再定公共 API 和模块划分（暂定为 `java/`、`typescript/`、`spec/`）。在此之前不写详细的 API 文档。

设计每一项能力前，先查 CLAUDE.md 列出的参考仓库：jOOQ 的类型化投影与 CTE、kysely/drizzle 的方言插件，以及前身仓库 HANA 文档里的实测边界。

## 容易踩的坑

- **LSP 路径**：会话从别的目录启动后再切到本仓库时，`${CLAUDE_PROJECT_DIR}` 仍指向原目录，TS LSP 会报 ENOENT。从本仓库目录新开会话即可。
- **WSL 地址**：WSL 的 `eth0` 地址在 WSL 重启后会变。连不上数据库时，先用 `wsl -d Debian hostname -I` 查。
- **corepack**：非交互运行 corepack 时要设置 `COREPACK_ENABLE_DOWNLOAD_PROMPT=0`；前身仓库切换 pnpm 版本时还用过 `--config.confirmModulesPurge=false`。
- **Claude CLI**：`claude` 不在 PATH 上，桌面应用自带的 CLI 在 `%LOCALAPPDATA%\Claude-Data\…\claude-code\<版本>\…\claude.exe`，可用来跑 `claude plugin validate`。它安装插件时会把 `.claude/settings.json` 里 marketplace 的路径改写成绝对路径，要改回 `./.claude/marketplace`。
- **HANA 内存**：HANA 会把 cgroup 内存限制当作物理内存。8 GiB 是 SAP 公布的最低值，测试变慢或内存不足时先查看占用，不要调大限制。
- **javac**：Java 17 上 switch 模式匹配仍是预览特性，渲染器用 sealed 节点加 visitor 或 `instanceof`。

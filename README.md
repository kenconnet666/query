# query

面向分析型复杂查询的 SQL 构建库，同时提供 Java 与 TypeScript 两个实现。它不依赖 EasyQuery，也不做完整 ORM：只负责把查询写成可组合、可复用、可校验的值，再按方言渲染并执行。

当前进度：Java 第一期已实现，覆盖 SAP HANA、PostgreSQL、MySQL 三个方言；TypeScript 在第二期。

- 用法：[API 使用说明](docs/api.md)
- 决定与取舍：[0001 独立的分析查询库](docs/decisions/0001-standalone-analytic-query-library.md)、[0002 API 与架构](docs/decisions/0002-api-architecture.md)
- 场景覆盖计划：[场景对照](docs/scenarios.md)

## 基线

| 项 | 要求 |
| --- | --- |
| Java | 17 及以上，`maven.compiler.release=17`；实体用注解声明，支持 Lombok |
| TypeScript | 以 7 为基准，使用标准装饰器 |
| 代码生成 | 不使用注解处理器（APT），也不在使用方生成代码 |
| 方言 | SAP HANA、PostgreSQL、MySQL 同步推进；DuckDB 延后 |

## 模块

| 模块 | 内容 |
| --- | --- |
| `java/query-core` | 注解、元数据、类型与转换器、表达式、lambda 作用域查询、渲染、执行、映射、缓存；运行时无第三方依赖 |
| `java/query-hana`、`java/query-postgres`、`java/query-mysql` | 各方言的门面、数据库实例、专属函数与子句 |
| `typescript/` | TypeScript 实现（第二期） |
| `infra/` | CI 用的数据库容器定义 |
| `tools/` | 生成 `From1..From8` 的脚本，以及 LSP 验证脚本 |

## 开发环境

本机 JDK 使用 `C:\Users\Administrator\.jdks\jbr-17.0.14`（IDEA 中的 SDK 名为 `jbr-17`）。Maven 版本由 wrapper 固定为 3.9.16。

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.jdks\jbr-17.0.14'
.\mvnw.cmd --batch-mode verify
```

实库场景需要以下环境变量，缺少时会跳过：

- `QUERY_HANA_URL`、`QUERY_HANA_USER`、`QUERY_HANA_PASSWORD`
- `QUERY_PG_URL`、`QUERY_PG_USER`、`QUERY_PG_PASSWORD`
- `QUERY_MYSQL_URL`、`QUERY_MYSQL_USER`、`QUERY_MYSQL_PASSWORD`

GitHub Actions 会并行运行单元测试、三个数据库的实库场景，以及 TypeScript 类型检查。

TypeScript 部分在 `typescript/` 目录，开发编译器固定为 TypeScript 7.0.2：

```powershell
cd typescript
corepack pnpm install
corepack pnpm typecheck
```

## Claude Code 语言服务

`.claude/settings.json` 启用了仓库内插件 `query-lsp@query`（位于 `.claude/marketplace`）。Claude 通过它查定义、引用和悬停信息，并在编辑后收到诊断。插件在会话启动时加载，首次打开项目时 Claude Code 会询问是否信任并安装。

| 语言 | 来源 | 准备 |
| --- | --- | --- |
| TypeScript | LSP：`typescript/` 固定的 TypeScript 7 编译器自带的语言服务（`tsc --lsp --stdio`），结果与 `typecheck` 一致 | 在 `typescript/` 执行 `pnpm install` |
| Java | IntelliJ IDEA 内置的 MCP 服务器（用户级配置的 `idea`），提供符号查找、调用链、重命名和文件问题等能力 | 在 IDEA 中打开本项目 |

Java 暂时不配置 LSP。JetBrains 的 Java 语言服务器目前只随 VS Code / Cursor 扩展 "Java and Kotlin by IntelliJ IDEA" 提供，许可证限定由人使用；Claude Code 这类终端代理的用法，JetBrains 尚未发布。

不启动 Claude 也可以直接验证 TypeScript 的配置。下面的命令按 `.lsp.json` 的原样命令启动服务器，并在指定位置检查悬停和跳转定义（行号、列号从 1 开始）：

```powershell
node tools/lsp/verify.mjs typescript typescript/src/index.ts 2 14
```

# query

面向分析型复杂查询的 SQL 构建库，同时提供 Java 与 TypeScript 两个实现。它不依赖 EasyQuery，也不做完整 ORM：只负责把查询写成可组合、可复用、可校验的值，再按方言渲染并执行。

当前版本 1.0.0（尚未发布到 Maven 中央仓库）：Java 与 TypeScript 功能对齐，覆盖 SAP HANA、PostgreSQL、MySQL 三个方言；两端渲染出的 SQL 由 `spec/render/` 的金样例保证一致。

- 用法：[Java API 使用说明](docs/api.md)、[TypeScript API 使用说明](docs/api-typescript.md)、[从手写 SQL 迁移](docs/migration.md)
- 版本：[变更记录](CHANGELOG.md)、[手动发布步骤](docs/release.md)
- 决定与取舍：[0001 独立的分析查询库](docs/decisions/0001-standalone-analytic-query-library.md)、[0002 API 与架构](docs/decisions/0002-api-architecture.md)、[0003 通往 1.0 的蓝图](docs/decisions/0003-road-to-1.0.md)、[0004 生产就绪](docs/decisions/0004-production-readiness.md)、[0005 API 简化审查](docs/decisions/0005-api-simplification.md)
- 场景覆盖计划：[场景对照](docs/scenarios.md)

## 快速上手

**Java**：引入所用方言的包，JDBC 驱动由项目自己提供。发布到中央仓库之前，先在本仓库执行 `.\mvnw.cmd install -DskipTests` 装到本地仓库。

```xml
<dependency>
    <groupId>org.dpdns.zerodep</groupId>
    <artifactId>query-postgres</artifactId>   <!-- 或 query-hana、query-mysql；Spring 项目再加 query-spring -->
    <version>1.0.0</version>
</dependency>
```

```java
import static org.dpdns.zerodep.query.postgres.Postgres.*;   // 核心函数加 PostgreSQL 函数

@Data
@Table(schema = "erp", value = "lips")
public class Lips {
    @Column("matnr") private String material;
    @Column("erdat") private LocalDate createdOn;
    @Column("lfimg") private BigDecimal quantity;
}

public record MaterialTotal(String material, BigDecimal quantity) { }

PostgresDatabase db = Postgres.database(dataSource);              // 每个数据源一个，长期复用

List<MaterialTotal> rows = db.list(from(Lips.class)
    .where(l -> l.col(Lips::getCreatedOn).ge(start))
    .groupBy(l -> l.col(Lips::getMaterial))
    .orderBy(l -> l.col(Lips::getMaterial))
    .select((r, l) -> new MaterialTotal(r.get(l, Lips::getMaterial), r.get(sum(l.col(Lips::getQuantity))))));
```

**TypeScript**：按方言安装 `@zerodep-dev/query-postgres`、`@zerodep-dev/query-mysql` 或 `@zerodep-dev/query-hana`，核心包会随之安装，数据库驱动（`pg`、`mysql2`、`@sap/hana-client`）由项目自己提供：

```ts
import pg from "pg";
import { column, database, date, decimal, from, sum, table } from "@zerodep-dev/query-postgres";

@table("lips", { schema: "erp" })
class Lips {
  @column("matnr") material!: string;
  @column("erdat", date) createdOn!: string;
  @column("lfimg", decimal) quantity!: string;
}

const db = database(new pg.Pool({ connectionString: process.env.DATABASE_URL }));

const rows = await db.list(
  from(Lips)
    .where((l) => l.createdOn.ge("2026-01-01"))
    .groupBy((l) => l.material)
    .orderBy((l) => l.material)
    .select((l) => ({ material: l.material, quantity: sum(l.quantity) })),
);   // { material: string; quantity: string | null }[]
```

`db.render(q)` 可以查看将要执行的 SQL 和参数。完整用法见上面的 API 使用说明。

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
| `java/query-spring` | Spring 适配：通过 `DataSourceUtils` 取连接，查询加入外层的 `@Transactional` 事务；Spring 5.3 到 7.0 都可用 |
| `typescript/packages/core` | `@zerodep-dev/query-core`：装饰器实体、表达式、回调作用域查询、渲染、执行、缓存；运行时无第三方依赖 |
| `typescript/packages/hana`、`postgres`、`mysql` | `@zerodep-dev/query-hana`、`@zerodep-dev/query-postgres`、`@zerodep-dev/query-mysql`：方言、专属函数与子句、驱动适配（`@sap/hana-client`、`pg`、`mysql2`） |
| `typescript/test` | TS 的单元、编译期、金样例与实库测试 |
| `spec/render/` | Java 与 TS 共用的渲染金样例 |
| `infra/` | 开发和 CI 共用的数据库容器定义，`mysql/`、`postgres/`、`hana/` 各自独立，启动命令见各目录 `compose.yaml` 开头的注释 |
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

GitHub Actions 会并行运行 Java 与 TypeScript 的单元测试、Spring 6.2 和 7.0 下的适配测试，以及三个数据库的实库场景。PostgreSQL、MySQL 按语言各拆成两个 job；HANA 在一个 job 里先跑 Java、再跑 TypeScript。

TypeScript 部分在 `typescript/` 目录，是一个 pnpm 工作区，开发编译器固定为 TypeScript 7.0.2：

```powershell
cd typescript
corepack pnpm install
corepack pnpm test          # tsc -b 编译，再运行单元、编译期与金样例测试
corepack pnpm test:live     # 实库场景，需要上面的环境变量
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
node tools/lsp/verify.mjs typescript typescript/packages/core/src/index.ts 2 3
```

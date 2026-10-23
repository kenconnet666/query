# 0001 独立的分析查询库

- 状态：方向已采纳（2026-10-21）。API、TypeScript 基准和方言顺序由 [0002](0002-api-architecture.md) 修订
- 前身：`easy_query_pro`（EasyQuery 3.2.17 的 Java 8 增强包，以及仓库内的 TypeScript ORM 子项目）

## 背景

### 前身的问题

| 维度 | 问题 |
| --- | --- |
| 接入 | 每个数据库需要按固定顺序手动安装 4 步（服务、JDBC handler、应用组件、类型映射），顺序错误就会出错；Spring 示例配置约 100 行；HANA 只能借 starter 的 SQL92 入口接入。 |
| 查询写法 | 每段表达式都要先 `ProDsl.of(entity)` 包装，例如 `ProDsl.of(p).nullSafeEqualsValue(p.name(), v).eq(true)`；数据库专属类型要先 `view(column, type)` 才能使用。 |
| 扩展 | 上游 APT 的列类型映射是写死的，专属类型无法直接出现在列上；扩展依赖继承（protected `recreate`、SELF 泛型、继承后委托 super），并继承或实现了约 40 处上游内部类，上游一升级就容易出问题。 |
| 模块 | 两个基础模块没有共同依赖，工具类只能各复制一份；公共 facade 有 46 个方法，同一个函数要按类型族重载多次。 |
| TS 方言 | 方言是写死的三选一联合类型，编译器里有 28 处按方言名分支，函数 renderer 也按方言名作 key，新增或替换方言都要改核心代码。 |

### 实际使用方需要什么

首个使用方是一个内部 ERP 分析大屏：Spring Boot 2.7、Java 17，数据访问用 MyBatis-Plus 和多数据源。分析查询直接查 SAP HANA 的 ABAP 表，另有一部分用 DuckDB 读取 parquet 快照，这部分正逐步改为直连 HANA。分析 SQL 全部以文本块手写，按出现次数统计（含 `.sql` 探测脚本）：

| 写法 | 次数 |
| --- | --- |
| `COALESCE`/`IFNULL` | 899 |
| `LEFT JOIN` | 494 |
| `CASE WHEN` | 440 |
| `UNION ALL` | 245 |
| `WITH`（CTE） | 184 |
| 窗口 `OVER (...)` | 35 |
| `ROLLUP`/`GROUPING SETS` | 0 |

突出的痛点：

- 动态条件靠 `String.formatted` 和 `{{占位符}}` 替换拼接 SQL，有注入风险，也难以阅读。
- 可选过滤写成 `(:flag = FALSE OR col = :value)`，影响执行计划。
- 业务口径（如十六个期间金额字段求和、客户端和公司代码过滤、物料号归一化）在多个查询之间复制粘贴。
- 列名是 SAP 的缩写（`MATNR`、`MFRPN` 等），没有编译期检查。

使用方不需要实体跟踪、导航属性、级联保存这些 ORM 能力。另一个 Java 8 的使用方以 CRUD 和列表过滤为主，分析需求很少，不在本库的目标范围内。

## 决定

1. 新开仓库，不依赖 EasyQuery，也不沿用其扩展机制。
2. Java 最低 17；TypeScript 的基准见 0002（以 7 为准）。
3. 不使用注解处理器（APT）。
4. 定位为分析查询构建器，不是完整 ORM：
   - 首批要支持的能力：
     - 查询结构：JOIN、CTE、子查询、`EXISTS`/`IN`、`UNION`、分组和 `HAVING`、窗口函数；
     - 表达式：`CASE`、`COALESCE`、`NULLIF`，以及常用的字符串和日期函数；
     - 条件与参数：可选条件、参数绑定、`IN` 列表分批；
     - 结果映射到 record 和各种 DTO；
     - 带结果类型的原生 SQL 片段，作为兜底。
   - 暂不做：实体跟踪、级联、DDL 和迁移。导航由 [0006](0006-query-model-direction.md) 加入；写入（INSERT、UPDATE、DELETE、插入或更新、乐观锁、逻辑删除、审计填充）由 [0007](0007-writes.md) 加入。
5. 内置功能和扩展要写法一致：
   - 用户自定义的函数和内置函数写法相同；
   - 方言是开放对象，函数渲染按"函数 + 方言"注册，不使用写死的方言枚举分支；
   - 扩展采用组合，不依赖继承基类、再覆写受保护方法。
6. 执行层只依赖连接提供者接口，不依赖 Spring。
   - 另提供一个 Spring 适配模块，通过 `DataSourceUtils` 取连接，从而和 MyBatis-Plus 等框架加入同一个 `@Transactional` 事务。
   - 它只依赖 spring-jdbc，Spring Boot 2.7、3、4 都能使用。

API 与架构的具体决定见 [0002](0002-api-architecture.md)。

## 代价与风险

- 这实际上是一个新产品，工作量明显大于继续在前身仓库上修补。范围必须守住"查询"，不能逐步滑向完整 ORM。
- Java 没有映射类型和条件类型，按方言区分可用 API 只能靠方言门面加渲染时检查，做不到 TS 那样完全在编译期检查。
- 不用 APT，实体需要手写。SAP 宽表在单个查询里通常只用 5 到 15 列，只声明用到的列反而更清晰。

## 待定事项

已决定的：

- 方言顺序（HANA、PostgreSQL、MySQL 同步推进，DuckDB 延后）、TypeScript 的推进方式（Java 第一期先行，TS 第二期对齐），见 0002。
- 从 HANA 目录生成实体源码的独立工具暂时不做（2026-10-21）。实体继续手写，只声明查询用到的列；需要时再单独评估。

仍未决定：前身仓库是否冻结在当前状态。

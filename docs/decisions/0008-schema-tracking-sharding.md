# 0008 数据库变更、代码生成、实体追踪与分表

- 状态：已采纳（2026-10-24），在 `feat/v2` 上实现，两端、三方言同步
- 目标版本：2.0
- 修订对象：0001 第 3、4 条和"待定事项"里的代码生成，0007"不做"里的实体跟踪

## 背景

对照 EasyQuery 补齐四批之后，用户要求再看分库分表、线程绑定的实体追踪、代码生成、DDL 与迁移，并把"整个数据库变更能力"纳入。三路调研（EasyQuery 3.2.17 的分片与 code-first 源码；Flyway、Liquibase、jOOQ、Hibernate、Prisma、drizzle-kit、Atlas、Kysely、EF Core、Django、strong_migrations、pgroll、ShardingSphere；前身仓库在 HANA 上的实测记录）的结论：

- EasyQuery 的分片约 8 到 9 千行，渗透整个执行层：没有绑定表，同库多张分片表连接时取笛卡尔积；路由推不出来时静默扫描全部分表；跨库没有事务保证。
- EasyQuery 的 code-first 只比较列名，不比较类型和可空；把所有语句拼成一个字符串执行（HANA 的 JDBC 不支持）；没有 HANA。
- 成熟工具在生产上都不用自动 update，而是"生成差异草稿、人工审阅、版本化落盘、校验和防篡改"；只向前，不依赖回滚脚本。
- 三库的 DDL 事务不同：PostgreSQL 可以整批回滚，MySQL 每条 DDL 隐式提交，HANA 默认自动提交（会话可以 `SET TRANSACTION AUTOCOMMIT DDL OFF`）。
- 表接口通常只声明用到的列，SAP 表又不归我们管，所以"按声明同步"只能用于自己建的表。

用户在 2026-10-24 确认按下面的推荐全部执行。

## 决定

### 1. 表结构模型

核心包新增一个只读的表结构模型：表、列（类型、长度与精度、可空、默认值、生成方式、注释）、主键、唯一约束、索引、外键。它有两个来源：

- **声明**：实体元数据加上新补的列属性（`@Column` 的类型、长度、精度、可空、默认值、注释，表接口上的 `@Index`）。TS 是同名的装饰器选项。
- **实库**：方言包读系统目录（PostgreSQL 与 MySQL 的 `information_schema`，HANA 的 `SYS.TABLES`、`SYS.TABLE_COLUMNS`、`SYS.INDEXES`、`SYS.REFERENTIAL_CONSTRAINTS`），只读给定 schema 里给定的表。

模型的比较按类型归一化，放在方言包里（HANA 的默认值已经解码、布尔记为 0/1，裸 DECIMAL 的 scale 为 NULL 等）。

### 2. 外部表与自己管的表

`@Table(external = true)`（TS `@table({ external: true })`）标记不归本库管的表，例如 SAP 表。外部表只做检查：已声明的列存在、类型相容；任何时候都不生成 DDL。没有标记的表被视为自己管的表，可以建表和增量同步；同步也只处理声明里出现的对象，库里多出来的列、索引只报告。

### 3. 变更的四种用法

| 用法 | 入口 | 说明 |
| --- | --- | --- |
| 检查 | `db.schema().check(types)` | 声明与实库的差异报告，外部表也适用；可在启动时或 CI 里调用，有差异时抛异常或非零退出 |
| 计划 | `db.schema().plan(types)` | 不可变的 `SchemaPlan`：`statements()`、`risks()`、`toSql()`；`apply()` 只执行安全步骤，危险步骤要 `allowDestructive()` |
| 迁移 | `Migrator` | 版本化迁移：`migrateToLatest()`、`migrateTo(version)`、`pending()`、`baseline(version)`、`verify()` |
| 只要 DDL | `plan.toSql()`、`createTable(T)` 渲染 | 已经用 Flyway、Liquibase 的项目把脚本交给它们 |

- **自动生成的只有"加"**：建表、加列、加索引、加外键（外键最后建）、按显式旧名重命名、同族长度与整数放宽、注释和默认值。缩窄、改类型、换主键、删除只给风险提示，提示里附上安全的分步写法；要执行必须 `allowDestructive()`。这条边界沿用前身在 HANA 上实测过的结论。
- **一条语句一次 JDBC 执行**，不拼多语句。
- **迁移用代码写**：Java 实现 `Migration`，TS 导出 `up(change)`；`change` 提供与计划相同的 DDL 操作、`sql(dialect, text)` 写方言原生语句、`execute(write)` 写数据。一份迁移在三个方言上渲染。迁移里不引用实体类型，以免实体以后修改后改变历史迁移的含义。
- **历史表** `QUERY_MIGRATION`（版本、名称、校验和、状态、开始与结束时间）；校验和不符或已执行的版本缺失时报错。PostgreSQL 整批一个事务；MySQL 和 HANA 逐条记录状态，失败后停下，修正后从失败的版本继续。
- **并发锁**：PostgreSQL `pg_advisory_lock`，MySQL `GET_LOCK`，HANA 用锁表的行锁。
- **不做**：回滚迁移、生产环境的自动 update、重放全部迁移的影子库（HANA 一个实例要 8 GiB）、HDI 工件、影子表与触发器式的在线变更。PostgreSQL 的 `concurrently`、`NOT VALID`，MySQL 的 `ALGORITHM` / `LOCK` 作为方言包的选项。

### 4. 代码生成：从库生成列的父接口

撤销 0001 里"不生成实体源码"的决定，改为只在用户显式运行时生成：

- Java 由 Maven 插件 `query:pull` 连库读目录（复用第 1 条的实库读取），TS 由命令行 `query-pull` 生成。
- 生成的是只有列的父接口（TS 是只有列的基类），不带 `@Table`；用户的表接口继承它，自己写关系、过滤器和覆盖的注解。重新生成只覆盖父接口，用户文件从不改动。
- 生成目录默认 `src/generated/java`（TS `src/generated`），随源码提交，不放 `target`。
- 配置：要生成的表、每张表的列白名单（SAP 宽表）、schema、命名规则、是否标记为外部表；密码只用环境变量引用。
- 仍不使用 APT，编译期不生成代码。

### 5. 实体追踪

在 `diffFrom` 之上提供显式范围的工作单元：

```java
db.tracking(t -> {
    DocRow d = t.one(Doc.class, from(Doc.class).where(...));
    d.setTitle("x");
});
```

- 范围内读到的行登记快照（同一张表、同一主键只登记一份）；范围正常结束时在一个事务里对变了的行写 `update(T).byId(row).diffFrom(snapshot)`，异常时不写。
- 只有写成功后才刷新快照，版本号写回对象；EasyQuery 无论成败都刷新快照、也不写回版本，这两点是它的缺陷。
- 行必须是可变对象（Java POJO，可以用 Lombok；TS 普通对象），登记时给出表接口。范围绑定在线程上（TS 用 AsyncLocalStorage），嵌套调用加入外层。
- 不做级联、`save` 自动判断插入或更新、懒加载；插入和删除仍然显式写。

### 6. 分表

分三层，只承诺前两层：

1. **原生分区**：方言包的建表选项提供 `PARTITION BY RANGE` 等写法，由数据库裁剪分区，查询不变。时间分表优先用它。
2. **同库的物理分表**（如 `EVENT_202601`）：
   - 表声明上注册分表规则（按月或取模，或自定义函数）和物理表清单（配置或从目录读取）。
   - 查询把逻辑表渲染成所选物理表 `UNION ALL` 的派生表，聚合、排序、分页都交给数据库，没有结果归并层。
   - 路由只认分表键与常量或参数的比较、`IN`、`BETWEEN`（AND 取交，OR 取并）；推不出来时报错，`.allShards()` 显式扫描全部分表，`.onShards(...)` 显式指定。
   - 写入按行路由，批量按表分组；改分表键报错；同一语句只允许一张分表参与连接。
   - 所有分表在同一个库，事务不变。新分表的建表语句由第 3 条的计划生成。
3. **跨库分片**：交给 ShardingSphere-JDBC 之类的数据源，本库无感知。文档只承诺"按分片键过滤的简单查询"。

## 代价与风险

- 定位从"查询构建器"扩大到"带写入、结构变更和工作单元的数据访问库"，但仍不做级联、懒加载和对象图保存，范围要继续守住。
- 实库读取和类型比较是三库差异最集中的地方，必须每个方言在实库上验证。
- HANA 的 DDL 自动提交意味着迁移中途失败时库处于半完成状态，只能靠逐条记录状态和人工处理。
- 物理分表的 `UNION ALL` 依赖数据库把条件推进各分支；条件推不进去的库（需实测）要在每个分支里重复条件。

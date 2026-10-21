# 变更记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.0] - 未发布

第一个正式版本。Java 发布到 Maven 中央仓库（坐标 `org.dpdns.zerodep.query:query-*`），TypeScript 包暂不发布到 npm。

### 查询

- 注解（Java）和标准装饰器（TS）声明实体：列名、命名策略、转换器、组合注解、DTO 的 `@MapFrom`，以及 SQL 级列转换 `@ReadAs`、`@Trimmed`、`@BlankAsNull`。
- lambda / 回调作用域：`from` 加最多 8 张表的 `join`、`leftJoin`、`crossJoin`，参数类型随作用域增长，用错表或类型不符时编译失败。
- 条件：`all`、`any`、`not`，`exists(from(...).where(...))` 不需要 SELECT 列表，可选条件 `eqIfPresent` 等、`ifPresent`、`onlyIf`，空条件自动丢弃；比较另一张表的列可以简写为 `a.eq(l, Lips::getMaterial)`。
- 只有一个分组键、排序键或命名列时直接写表达式，多个时用 `keys(...)`、`orders(...)`；裸表达式按升序排序。
- 分组：`groupBy`、`having`、`groupByRollup`；HANA、PostgreSQL 的 `cube`、`groupingSets`；`grouping(e)`。
- CTE（任意深度引用只渲染一次）、派生表、`union` / `unionAll`（混用时保留分组括号）、窗口函数与 ROWS 框架、常量行 `values`。
- 函数：文本（含表达式起止位置的 `substring`、`locate`）、数值（`floor`、`ceil`、`round`、`divide`）、聚合、简单与搜索 CASE、多参数 `coalesce`、`cast`、标量子查询，以及模板兜底 `sql(...)`、`sqlCond(...)`。
- 方言专属能力只在方言包里提供，放到别的方言上渲染时报错：HANA 的日期函数、`toInteger`、`hint`；PostgreSQL 的 `distinctOn`、`ilike`、聚合的 `FILTER` 子句；MySQL 的 `groupConcat`、优化器提示；三库各自的 `greatest`、`least`。
- 全局过滤器按父类型或接口匹配，CTE 和子查询里的表同样生效。

### 投影与结果

- Java 的函数式投影 `select((r, ...) -> new X(...))`：每行重放 lambda，并按表达式结构核对读取顺序。TS 用对象字面量投影，结果类型由键推导。
- 命名投影 `selectAs`：按同名属性取列，显式列之外的属性自动补齐，列按形状的属性顺序输出。
- `mapTo`、`map` 把同一查询映射到不同的类；`Nest.into` / `nest` 把扁平结果分组成父项加子项列表。

### 执行

- `list`、`one`、`first`、`count`、`page`、`stream` / `forEach`（PostgreSQL 在自动提交的连接上自动用游标）。
- 原生 SQL：命名参数、集合展开，跳过引号、注释和方言引用里的 `:name`。
- 结果缓存、语句监听器、`render` 查看 SQL 与参数。
- 查询超时：数据库实例的默认值，查询与原生 SQL 的 `timeout(...)` 覆盖它，三库都在服务端取消语句。Java 在 Spring 事务里沿用事务剩余的时间。超时的异常可以用 `isTimeout()` / `timedOut` 区分。
- 参数个数超过数据库上限（HANA、PostgreSQL 65535）时，在连接数据库之前给出可读的错误。
- `query-spring`：通过 `DataSourceUtils` 取连接，查询加入外层事务；Spring 5.3、6.2、7.0 都在 CI 中验证。

### 保证

- Java 与 TS 渲染出的 SQL 和参数由 `spec/render/` 的 88 个金样例文件（38 个场景在各方言上的渲染）保证相同。
- 三个数据库上的实库场景逐项验证这些能力，另有连接泄漏与并发渲染的测试。
- 保留字作标识符时自动加引号。
- 字面量的转义经过恶意文本的三库往返测试；PostgreSQL 的值含反斜杠时写成 `E'...'`，不受 `standard_conforming_strings` 影响；`NaN`、`Infinity` 不会写成字面量。
- CI 在 JDK 17、21、25 上运行单元测试。

### 已知限制

- 一个作用域最多 8 张表；一个类里引用的不同 getter 超过约 500 个时 javac 报 "code too large"。
- 函数式投影读大结果集时比 `selectAs` 慢约一半。
- MySQL 的 `stream` 默认缓冲，逐行读取需要配置 `fetchSize(Integer.MIN_VALUE)`。
- 只做查询，不做写入、DDL 与迁移。

[1.0.0]: https://github.com/kenconnet666/query/releases/tag/v1.0.0

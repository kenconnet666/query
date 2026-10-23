# 场景对照

本表以 EasyQuery 3.2.17 的公开 API 和测试覆盖的场景为清单，列出本库在每个场景上的做法和计划分期。EasyQuery 增强包的报表类场景按需并入，不逐项复刻。设计依据见 [0002](decisions/0002-api-architecture.md)。

"期"的含义：1 为 1.x 已实现，2.0 为 2.0 新增（两端、三方言同步），3 为后续扩展，"—"为不做。表中写的是 Java 写法；TypeScript 的对应写法见 [TypeScript API 使用说明](api-typescript.md)，例如 `l.material()` 写作 `l.material`。

## 查询结构

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 单表、多表连接 | APT 生成代理，`queryable(A).leftJoin(B, (a, b) -> ...)`，最多 10 张表 | 注解接口表，`from(A.class).leftJoin(B.class, (a, b) -> ...)`，Java 最多 16 张表、TS 不限，使用方不需要代码生成 | 1 |
| 自连接 | 为每张表分别建代理 | 同一实体作为下一个 lambda 参数，别名自动生成 | 1 |
| 隐式连接（导航属性 `t.author().name()`） | `@Navigate` 声明关系 | 表接口的 default 方法 `one(...)`、`many(...)`，或 `@ManyToOne`、`@OneToMany`；`l.mara().partNumber()` 用到时才 LEFT JOIN，同一关系只连接一次 | 2.0 |
| 子查询：`EXISTS`、`IN`、标量 | `subQuery`，以及导航上的 `any()`、`none()` | `exists(...)`、`in(query)`、`scalar(query)`，lambda 直接捕获外层参数；一对多关系上的 `any()`、`none()`、`count()`、`sum(...)`、`first(order)` | 1 / 2.0 |
| CTE | `toCteAs`，相同查询自动去重 | `cte(name, query)`，渲染时统一提升到最外层的 `WITH`，以规避 HANA 嵌套 `WITH` 的问题 | 1 |
| 递归 CTE / 树 | `asTreeCTE` | HANA 2.0 不支持递归 CTE，改用层级函数；PostgreSQL 由方言包提供 | 3 |
| UNION / UNION ALL | `union`、`unionAll` | `Query.unionAll`，各段结果类型必须相同（编译期检查） | 1 |
| 常量行（码表 CTE） | 原生 SQL 片段 | `values(Shape.class, rows)`：每行一个字面量 SELECT，用 UNION ALL 连接，HANA 上加 `FROM DUMMY` | 1 |
| 分组与 `HAVING` | `groupBy(t -> GroupKeys.of(...))` | `groupBy(l -> l.material())`，多个键用 `keys(...)`；`having(...)` | 1 |
| ROLLUP / CUBE / GROUPING SETS | 增强包提供 | 核心包 `groupByRollup(...)` 与 `grouping(e)`，MySQL 渲染为 `WITH ROLLUP`；MySQL 不支持的 `cube`、`groupingSets` 由 HANA、PostgreSQL 方言包提供 | 1 |
| 窗口函数 | `rowNumberOver` 等，增强包补齐 | `rowNumber().over(w -> w.partitionBy(...).orderBy(...))`；`ROWS` 框架进核心包，`RANGE` 由方言包提供 | 1 |
| `QUALIFY` / 分区取第一行 | `partitionBy` | HANA 不支持 `QUALIFY`；用 CTE 加 `rowNumber` 再过滤 | 1 |
| 分页 | `toPageResult` | `db.page(q, page, size)`；计数查询在原查询外包一层 `COUNT(*)` | 1 |
| 分块遍历 | `OffsetChunk` | `db.stream(q)`、`db.forEach(q, ...)`，在一个游标上逐行读取 | 1 |
| `FOR UPDATE` | `forUpdate` | 属于写入场景 | — |
| 原生 SQL | `sqlNativeSegment`，以及原生查询 | 类型化模板 `sql(Type, "...{0}", args)`，参数混用表达式和值，`.on(方言类, 模板)` 按方言换写法；`sqlTable(...)` 当作数据源；整条原生 SQL 用 `db.sql("... :name").bind(...)` | 1 / 2.0 |

## 投影与映射

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 映射到 DTO | `select(VO.class, ...)`、`selectAutoInclude` | `selectAs(VO.class)` 按同名属性取列，嵌套 VO 与 `List<X>` 属性按同名关系映射；改名或计算列用 `cols(...)` 加 `.as("x")` 再 `.into(VO.class)` | 2.0 |
| 元组 / 匿名结果 | `Draft2..`、`selectColumn` | `row(X::new, a, b, ...)` 按位置构造任意类型，可以嵌套；`cols(...)` 得到 Map 行 | 2.0 |
| 嵌套 DTO（一对一） | `selectAutoInclude` 加导航属性 | 嵌套的 `row(...)`，或 `selectAs` 一个嵌套 VO 属性（取同名多对一关系） | 2.0 |
| 一对多组装（`include`、`includes`） | 导航属性加批量加载 | `many.list(...)` 或 `selectAs` 的 `List<X>` 属性：父查询只多取连接键，子行用一条按键批量的 `IN` 查询加载，可以逐层嵌套 | 2.0 |
| 单列 / 标量结果 | `selectColumn`、`count`、`sum` | `select(l -> 表达式)` 加 `db.one(...)`、`db.count(q)` | 1 |
| Lombok `@Data`、链式 setter、`@Builder`、record | 部分支持 | 均支持：映射计划用 `MethodHandle` 调用 | 1 |

## 条件与动态查询

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 按开关加条件 | `where(boolean, ...)` | `onlyIf(test, () -> cond)` 写在 `where` 里 | 1 |
| 值为 null 时忽略条件 | `NotNullOrEmptyValueFilter` 全局开关 | 作用域上的 `skipEmpty()`：与 null、空白字符串、空集合比较的条件省略；不开启时与 null 比较报错 | 2.0 |
| 根据查询对象生成条件 | `whereObject`、`@EasyWhereCondition` | `where(search)`：每个非空字段一个条件，`@Where` 指定列和比较方式，列可走关系路径，多列为 OR（见 0006） | 2.0 |
| 动态排序 | `orderByObject` | `sortBy("total desc, material")` 只认结果列；`orderBy` 也接收运行时生成的 `List<Order>` | 2.0 |
| 按条件追加连接表的过滤 | 动态 join | 写成可选的 EXISTS（`ifPresent(v, x -> exists(...))`），或多对一关系（没用到时不连接） | 1 / 2.0 |
| 按用户选择取列、选维度 | 动态 select | `only(columns)`、`exclude(...)`：没选的列写成 NULL，相同的分组键和只被它们用到的连接一起去掉 | 2.0 |
| 复用的 SQL 片段 | 字符串拼接 | 返回 CTE、条件或表达式的普通方法 | 1 |
| 分组表达式中的常量 | 由用户处理 | SELECT 列表与分组相关子句中的常量自动写成字面量，WHERE 与 ON 仍绑定参数（0002 第 10 条） | 1 |
| `IN` 大列表 | 由用户自行分批 | 超过方言上限时自动拆成多个 `IN` 并用 OR 连接；空列表渲染成恒假条件 | 1 |
| 多租户、逻辑删除（读） | 谓词拦截器 `PredicateFilterInterceptor` | 全局过滤器 `filter(Type.class, ref -> cond)`，按实体的父类型或接口匹配；单个查询可用 `unfiltered()` 关闭 | 1 |

## 类型与扩展

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 值转换 | `ValueConverter<T, P>`、`@Column(conversion)` | `Converter<J, D>`，配合组合注解 `@ConvertWith` 使用 | 1 |
| 枚举 | `@Enumerated` 等 | 默认按名称映射；按编码映射由转换器提供 | 1 |
| SQL 级列转换（读取） | `ColumnValueSQLConverter` | 字段上的 `@ReadAs("…{0}…")`，内置 `@BlankAsNull`、`@Trimmed`；列在语句的每个位置都按模板读取 | 1 |
| 加密列 | `@Encryption` | 读取一侧可用 `@ReadAs`；参数一侧的包装与写入一起评估 | 3 |
| JSON 列 | 支持 | 由方言包提供 | 3 |
| 自定义函数 | `SQLFunc` 加各方言的继承实现 | 静态方法加 `FunctionDef`，按方言注册渲染，与内置函数写法相同；例如 `concat` 在 MySQL 上渲染为 `CONCAT` | 1 |
| 自定义方言 | 继承上游的内部类 | 继承开放的 `Dialect` 类，覆盖其中的钩子 | 1 |

## 执行与基础设施

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 多数据源 | 动态数据源 | 每个数据源对应一个方言实例 | 1 |
| 事务 | 自带事务管理，或接入 Spring | 只通过 `ConnectionProvider` 取连接；`query-spring` 用 `DataSourceUtils` 加入外层的 `@Transactional`（Java）；TS 把事务中的连接交给驱动适配 | 1 |
| 结果缓存 | `sql-cache` 模块 | `QueryCache` SPI，自带进程内的 TTL 实现；缓存解码后的原始行 | 1 |
| SQL 日志与测试 | `SQLParameterPrintFormat`、监听器 | `QueryListener`；`printSql` 按 MyBatis 格式打印；`debugSql()` 写回参数；`db.capture(...)` 收集语句；空跑连接 | 1 / 2.0 |
| 分库分表 | sharding 模块 | — | — |
| 写入、追踪、乐观锁、主键生成 | insert、update、track、`@Version` | 以后单独评估 | — |
| DDL、迁移 | code first、migration | — | — |

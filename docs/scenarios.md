# 场景对照

本表以 EasyQuery 3.2.17 的公开 API 和测试覆盖的场景为清单，列出本库在每个场景上的做法和计划分期。EasyQuery 增强包的报表类场景按需并入，不逐项复刻。设计依据见 [0002](decisions/0002-api-architecture.md)。

"期"的含义：1 为已实现（Java 第一期、TypeScript 第二期，HANA、PostgreSQL、MySQL 三个方言同步），3 为后续扩展，"—"为不做。表中写的是 Java 写法；TypeScript 的对应写法见 [TypeScript API 使用说明](api-typescript.md)，例如 `l.col(Lips::getMaterial)` 写作 `l.material`。

## 查询结构

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 单表、多表连接 | APT 生成代理，`queryable(A).leftJoin(B, (a, b) -> ...)`，最多 10 张表 | 注解实体，`from(A).leftJoin(B, (a, b) -> ...)`，Java 最多 8 张表、TS 不限，不需要代码生成 | 1 |
| 自连接 | 为每张表分别建代理 | 同一实体作为下一个 lambda 参数，别名自动生成 | 1 |
| 隐式连接（导航属性 `t.author().name()`） | `@Navigate` 声明关系 | 不做导航。常用连接写成可复用的函数，`(l, ma) -> Joins.material(l, ma)` | — |
| 子查询：`EXISTS`、`IN`、标量 | `subQuery`，以及导航上的 `any()`、`none()` | `exists(...)`、`in(query)`、`scalar(query)`，lambda 直接捕获外层参数 | 1 |
| CTE | `toCteAs`，相同查询自动去重 | `cte(name, query)`，渲染时统一提升到最外层的 `WITH`，以规避 HANA 嵌套 `WITH` 的问题 | 1 |
| 递归 CTE / 树 | `asTreeCTE` | HANA 2.0 不支持递归 CTE，改用层级函数；PostgreSQL 由方言包提供 | 3 |
| UNION / UNION ALL | `union`、`unionAll` | `Query.unionAll`，各段结果类型必须相同（编译期检查） | 1 |
| 分组与 `HAVING` | `groupBy(t -> GroupKeys.of(...))` | `groupBy((a, b) -> keys(...))`、`having(...)` | 1 |
| ROLLUP / GROUPING SETS | 增强包提供 | MySQL 只支持 `WITH ROLLUP`，所以不进核心包；由 HANA、PostgreSQL 方言包提供 | 3 |
| 窗口函数 | `rowNumberOver` 等，增强包补齐 | `rowNumber().over(w -> w.partitionBy(...).orderBy(...))`；`ROWS` 框架进核心包，`RANGE` 由方言包提供 | 1 |
| `QUALIFY` / 分区取第一行 | `partitionBy` | HANA 不支持 `QUALIFY`；用 CTE 加 `rowNumber` 再过滤 | 1 |
| 分页 | `toPageResult` | `db.page(q, page, size)`；计数查询在原查询外包一层 `COUNT(*)` | 1 |
| 分块遍历 | `OffsetChunk` | `db.stream(q)`、`db.forEach(q, ...)`，在一个游标上逐行读取 | 1 |
| `FOR UPDATE` | `forUpdate` | 属于写入场景 | — |
| 原生 SQL | `sqlNativeSegment`，以及原生查询 | 类型化模板 `sql(Type, "...{0}", args)`；整条原生 SQL 用 `db.sql("... :name").bind(...)` | 1 |

## 投影与映射

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 映射到 DTO | `select(VO.class, ...)`、`selectAutoInclude` | `selectAs(VO.class)` 按同名属性取列；`@MapFrom` 处理改名的属性；`.as(VO::getX)` 指定计算列 | 1 |
| 元组 / 匿名结果 | `Draft2..`、`selectColumn` | 函数式投影 `select((r, a, b) -> new X(...))`，可以构造任意类型，包括嵌套对象 | 1 |
| 嵌套 DTO（一对一） | `selectAutoInclude` 加导航属性 | 在函数式投影中直接构造嵌套 record | 1 |
| 一对多组装（`include`、`includes`） | 导航属性加批量加载 | 父查询与子查询分别执行，再按键在内存中分组。API 为 `nest`（暂定名） | 3 |
| 单列 / 标量结果 | `selectColumn`、`count`、`sum` | `selectValue(...)` 加 `db.one(...)`、`db.count(q)` | 1 |
| Lombok `@Data`、链式 setter、`@Builder`、record | 部分支持 | 均支持：映射计划用 `MethodHandle` 调用 | 1 |

## 条件与动态查询

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 按开关加条件 | `where(boolean, ...)` | `whereIf(test, ...)`、`onlyIf(test, () -> cond)` | 1 |
| 值为 null 时忽略条件 | `NotNullOrEmptyValueFilter` 全局开关 | 显式写 `eqIfPresent`、`inIfPresent`、`ifPresent(v, x -> cond)`，不做全局隐式忽略 | 1 |
| 根据查询对象生成条件 | `whereObject`、`@EasyWhereCondition` | 在请求 DTO 上用注解声明条件，键用 Lombok 常量 | 3 |
| 动态排序 | `orderByObject` | `orderBy((a, b) -> ...)` 接收任意 `List<Order>`，可以由运行时数据生成 | 1 |
| `IN` 大列表 | 由用户自行分批 | 超过方言上限时自动拆成多个 `IN` 并用 OR 连接；空列表渲染成恒假条件 | 1 |
| 多租户、逻辑删除（读） | 谓词拦截器 `PredicateFilterInterceptor` | 全局过滤器 `filter(Type.class, ref -> cond)`，按实体的父类型或接口匹配；单个查询可用 `unfiltered()` 关闭 | 1 |

## 类型与扩展

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 值转换 | `ValueConverter<T, P>`、`@Column(conversion)` | `Converter<J, D>`，配合组合注解 `@ConvertWith` 使用 | 1 |
| 枚举 | `@Enumerated` 等 | 默认按名称映射；按编码映射由转换器提供 | 1 |
| SQL 级列转换（如加解密函数） | `ColumnValueSQLConverter` | 列读取和参数渲染都可以挂 SQL 包装，例如 `TRIM(col)` | 3 |
| 加密列 | `@Encryption` | 由 SQL 级列转换实现 | 3 |
| JSON 列 | 支持 | 由方言包提供 | 3 |
| 自定义函数 | `SQLFunc` 加各方言的继承实现 | 静态方法加 `FunctionDef`，按方言注册渲染，与内置函数写法相同；例如 `concat` 在 MySQL 上渲染为 `CONCAT` | 1 |
| 自定义方言 | 继承上游的内部类 | 继承开放的 `Dialect` 类，覆盖其中的钩子 | 1 |

## 执行与基础设施

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 多数据源 | 动态数据源 | 每个数据源对应一个方言实例 | 1 |
| 事务 | 自带事务管理，或接入 Spring | 只通过 `ConnectionProvider` 取连接；Spring 适配模块使用 `DataSourceUtils` | 3 |
| 结果缓存 | `sql-cache` 模块 | `QueryCache` SPI，自带进程内的 TTL 实现；缓存解码后的原始行 | 1 |
| SQL 日志 | `SQLParameterPrintFormat` | `QueryListener` | 1 |
| 分库分表 | sharding 模块 | — | — |
| 写入、追踪、乐观锁、主键生成 | insert、update、track、`@Version` | 以后单独评估 | — |
| DDL、迁移 | code first、migration | — | — |

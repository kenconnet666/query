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
| 多个关系聚合合成分组连接 | `subQueryToGroupJoin` | 作用域上的 `groupJoin(m -> m.deliveries())`：同一关系上的 count、sum、any 等合成一个按连接键分组的派生表 LEFT JOIN | 2.0 |
| CTE | `toCteAs`，相同查询自动去重 | `cte(name, query)`，渲染时统一提升到最外层的 `WITH`，以规避 HANA 嵌套 `WITH` 的问题 | 1 |
| 递归 CTE / 树 | `asTreeCTE` | PostgreSQL、MySQL 方言包的 `recursiveCte(name, anchor, self -> step)`，写成 `WITH RECURSIVE`；HANA 2.0 不支持递归 CTE，改用层级函数 | 2.0 |
| UNION / UNION ALL | `union`、`unionAll` | `Query.unionAll`，各段结果类型必须相同（编译期检查） | 1 |
| INTERSECT / EXCEPT | `intersect`、`except` | `q1.intersect(q2)`、`q1.except(q2)`，按调用顺序求值；HANA 的集合运算从左到右，PostgreSQL、MySQL 的 INTERSECT 优先（实测），所以读法可能不同处自动加括号；`ALL` 变体只在 PostgreSQL、MySQL | 2.0 |
| 参数化视图、表函数 | HANA 的 `PLACEHOLDER` 需手写 | HANA 方言包的 `fromView`、`fromFunction`：参数都绑定，值为空的参数不写（视图需 HDI 部署，只验证了渲染；表函数实库验证） | 2.0 |
| 层级 | — | HANA 方言包的 `hierarchy`、`descendants`、`ancestors`（HIERARCHY 生成函数与导航函数） | 2.0 |
| 全文检索 | — | HANA `contains` / `containsFuzzy` / `score`；PostgreSQL `textMatches` / `webMatches` / `textRank`；MySQL `match` / `matchBoolean` / `matchScore`，三库的分数不能互相比较 | 2.0 |
| JSON | 前身的 JSON DSL | HANA `jsonValue`（路径必须是字面量）；PostgreSQL `jsonText` / `jsonContains` / `jsonHasKey` / `jsonb`；MySQL `jsonText` / `jsonContains`（参数 `CONVERT(? USING utf8mb4)`） | 2.0 |
| PostgreSQL 数组 | — | `eqAny(e, values)` 一个数组参数代替长 IN 列表；数组列、`arrayContains`、`arrayOverlaps`、`cardinality`、`arrayAgg` | 2.0 |
| MySQL 索引提示 | — | `index(l, "idx")`、`noIndex(m)`，与 `maxExecutionTime` 合并进一个提示注释（实测 MySQL 只认第一个） | 2.0 |
| FULL JOIN | `fullJoin` | `fullJoin(T, on)`：HANA、PostgreSQL；有全局过滤条件的表不能参与（过滤条件放 ON 或 WHERE 都会改变语义）；MySQL 渲染时报错 | 2.0 |
| RIGHT JOIN | `rightJoin` | `rightJoin(T, on)`；左侧各表的全局过滤条件放进它的 ON；TS 中之前各参数的列变成可空 | 2.0 |
| 多列 IN、ANY / ALL 子查询 | `in(...)` 多列、`gtAll` 一类 | `keys(a, b).in(q)`；`x.gt(allOf(q))`、`x.eq(anyOf(q))` | 2.0 |
| NULL 安全比较 | `nullSafeEquals` | `a.isNotDistinctFrom(b)` / `isDistinctFrom`：PostgreSQL 原生写法，MySQL `<=>`，HANA 等价的 CASE | 2.0 |
| 条件作为列 | 投影里的 `predicate` | `bool(cond)`，写成 `CASE WHEN c THEN TRUE WHEN NOT (c) THEN FALSE END`（HANA 不能直接把条件放进 SELECT） | 2.0 |
| 去重聚合与统计 | `sumDistinct`、`avgDistinct`、`stddev` 等 | `sumDistinct`、`avgDistinct`、`stddevSamp`/`stddevPop`/`varSamp`/`varPop`（参数先转 DOUBLE，三库一致；MySQL 的 `STDDEV` 是总体标准差，所以不提供不带后缀的名字） | 2.0 |
| 常量行（码表 CTE） | 原生 SQL 片段 | `values(Shape.class, rows)`：每行一个字面量 SELECT，用 UNION ALL 连接，HANA 上加 `FROM DUMMY` | 1 |
| 分组与 `HAVING` | `groupBy(t -> GroupKeys.of(...))` | `groupBy(l -> l.material())`，多个键用 `keys(...)`；`having(...)` | 1 |
| ROLLUP / CUBE / GROUPING SETS | 增强包提供 | 核心包 `groupByRollup(...)` 与 `grouping(e)`，MySQL 渲染为 `WITH ROLLUP`；MySQL 不支持的 `cube`、`groupingSets` 由 HANA、PostgreSQL 方言包提供 | 1 |
| 条件聚合（行转列） | `count().filter(...)`、`sum().filter(...)` | 聚合加 `.filter(cond)`，三个库都写成 `SUM(CASE WHEN ... THEN x END)` | 2.0 |
| 文本聚合 | `joining(",")` | `stringAgg(e, sep, order...)`，MySQL 写成 `GROUP_CONCAT` | 2.0 |
| 窗口函数 | `rowNumberOver` 等，增强包补齐 | `rowNumber().over(w -> w.partitionBy(...).orderBy(...))`；`percentRank`、`cumeDist`、`nthValue` 也在核心包；`ROWS` 框架进核心包；`RANGE` 框架只在 PostgreSQL、MySQL 方言包（`w.frame(range(...))`，HANA 不支持） | 1 / 2.0 |
| `QUALIFY` / 每组取前 N 行 | `Select.PART` 加外层 `where` | `qualify(l -> rowNumber().over(...).le(n))`：HANA 不支持 `QUALIFY`，三个库都自动包成派生表；关系上取第一行用 `many.first(order)` | 2.0 |
| 分页 | `toPageResult`，可传入已知总数 | `db.page(q, page, size)`；计数查询在原查询外包一层 `COUNT(*)`；已知总数时 `page(q, page, size, total)` 不计数 | 1 / 2.0 |
| 分块遍历 | `OffsetChunk` | `db.stream(q)`、`db.forEach(q, ...)`，在一个游标上逐行读取 | 1 |
| `FOR UPDATE` | `forUpdate` | `.with(forUpdate(Lock.SKIP_LOCKED))`，等待、`NOWAIT`、`SKIP LOCKED` 三种；HANA 写作 `IGNORE LOCKED` | 2.0 |
| 原生 SQL | `sqlNativeSegment`，以及原生查询 | 类型化模板 `sql(Type, "...{0}", args)`，参数混用表达式和值，`.on(方言类, 模板)` 按方言换写法；`sqlTable(...)` 当作数据源；整条原生 SQL 用 `db.sql("... :name").bind(...)` | 1 / 2.0 |

## 投影与映射

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 映射到 DTO | `select(VO.class, ...)`、`selectAutoInclude` | `selectAs(VO.class)` 按同名属性取列，嵌套 VO 与 `List<X>` 属性按同名关系映射；改名或计算列用 `cols(...)` 加 `.as("x")` 再 `.into(VO.class)` | 2.0 |
| 元组 / 匿名结果 | `Draft2..`、`selectColumn` | `row(X::new, a, b, ...)` 按位置构造任意类型，可以嵌套；`cols(...)` 得到 Map 行 | 2.0 |
| 嵌套 DTO（一对一） | `selectAutoInclude` 加导航属性 | 嵌套的 `row(...)`，或 `selectAs` 一个嵌套 VO 属性（取同名多对一关系） | 2.0 |
| 一对多组装（`include`、`includes`） | 导航属性加批量加载 | `many.list(...)` 或 `selectAs` 的 `List<X>` 属性：父查询只多取连接键，子行用一条按键批量的 `IN` 查询加载，可以逐层嵌套；`many.orderBy(...).limit(n)` 每个父行只取前 n 行 | 2.0 |
| 单列 / 标量结果 | `selectColumn`、`count`、`sum` | `select(l -> 表达式)` 加 `db.one(...)`、`db.count(q)` | 1 |
| Lombok `@Data`、链式 setter、`@Builder`、record | 部分支持 | 均支持：映射计划用 `MethodHandle` 调用 | 1 |

## 条件与动态查询

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 半开区间 | `rangeClosedOpen(a, b)` | `e.range(from, until)`：`>= from AND < until`，skipEmpty 下缺少的一端被忽略 | 2.0 |
| 按开关加条件 | `where(boolean, ...)` | `onlyIf(test, () -> cond)` 写在 `where` 里 | 1 |
| 值为 null 时忽略条件 | `NotNullOrEmptyValueFilter` 全局开关 | 作用域上的 `skipEmpty()`：与 null、空白字符串、空集合比较的条件省略；不开启时与 null 比较报错 | 2.0 |
| 根据查询对象生成条件 | `whereObject`、`@EasyWhereCondition` | `where(search)`：每个非空字段一个条件，`@Where` 指定列和比较方式，列可走关系路径，多列为 OR；经过一对多关系时写成 EXISTS，同一关系的字段合并（见 0006） | 2.0 |
| 动态排序 | `orderByObject` | `sortBy("total desc, material")` 只认结果列；`orderBy` 也接收运行时生成的 `List<Order>` | 2.0 |
| 按条件追加连接表的过滤 | 动态 join | 写成可选的 EXISTS（`ifPresent(v, x -> exists(...))`），或多对一关系（没用到时不连接） | 1 / 2.0 |
| 按用户选择取列、选维度 | 动态 select | `only(columns)`、`exclude(...)`：没选的列写成 NULL，相同的分组键和只被它们用到的连接一起去掉 | 2.0 |
| 复用的 SQL 片段 | 字符串拼接 | 返回 CTE、条件或表达式的普通方法 | 1 |
| 分组表达式中的常量 | 由用户处理 | SELECT 列表与分组相关子句中的常量自动写成字面量，WHERE 与 ON 仍绑定参数（0002 第 10 条） | 1 |
| `IN` 大列表 | 由用户自行分批 | 超过方言上限时自动拆成多个 `IN` 并用 OR 连接；空列表渲染成恒假条件 | 1 |
| 多租户、逻辑删除（读） | 谓词拦截器 `PredicateFilterInterceptor`，`noInterceptor(name)`、`disableLogicDelete()` | 全局过滤器 `filter(Type.class, ref -> cond)`，按实体的父类型或接口匹配；具名的 `filter("tenant", ...)` 可以用 `unfiltered("tenant")` 单独关闭，`withDeleted()` 只关逻辑删除，`unfiltered()` 全部关闭 | 1 / 2.0 |
| 运行时 schema | `RuntimeSchemaProvider`、`asSchema(...)` | 数据库实例的 `schema("ERP", "SAPHANADB")` 或按规则换名；不做查询级的动态表名 | 2.0 |

## 类型与扩展

| 场景 | EasyQuery 的做法 | 本库的做法 | 期 |
| --- | --- | --- | --- |
| 值转换 | `ValueConverter<T, P>`、`@Column(conversion)` | `Converter<J, D>`，配合组合注解 `@ConvertWith` 使用 | 1 |
| 枚举 | `@Enumerated`、`ValueAutoConverterProvider` | 默认按名称映射；实现 `CodeEnum<C>` 的按编码映射（Java；TS 用字面量联合） | 1 / 2.0 |
| SQL 级列转换（读取） | `ColumnValueSQLConverter` | 字段上的 `@ReadAs("…{0}…")`，内置 `@BlankAsNull`、`@Trimmed`；列在语句的每个位置都按模板读取 | 1 |
| 加密列 | `@Encryption` | `Converter` 在写入、读取和比较参数三处都转换，应用侧加解密可直接实现，等值查询可用；不做可以 LIKE 的分段加密 | 1 |
| JSON 列 | 支持 | 由方言包提供 | 3 |
| 日期部分与日期加减 | `year()`、`plusDays(n)`、`duration(...).toDays()` | `year`、`month`、`day`、`hour`、`minute`、`second`、`addDays`、`addMonths`、`addYears`、`addHours`、`addMinutes`、`addSeconds`、`monthStart`、`daysBetween`、`secondsBetween`，各方言自动换写法；按格式转文本仍用方言函数（HANA `toVarchar`、MySQL `dateFormat`）；周相关函数三库规则不同，暂不做 | 2.0 |
| 文本补齐与数值函数 | `leftPad`、`rightPad`、`SQLMathFunc` | `lpad`、`rpad`、`mod`、`power`、`sqrt`、`sign`，三库实测一致；`length` 各库计法不同，留在方言包 | 2.0 |
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
| 插入、更新、删除 | `insertable`、`updatable`、`deletable` | `insertInto(T).values(rows)` / `.from(query)`、`update(T).set(...).where(...)`、`deleteFrom(T).where(...)`；没有 WHERE 报错，`.all()` 放行；`expect(n)` 校验行数；见 [0007](decisions/0007-writes.md) | 2.0 |
| 按主键写、乐观锁 | `@Version`、按实体更新 | `byId(rows)`：WHERE 主键加旧版本，SET 版本加一，冲突时抛异常；`skipEmpty().byId` 只更新非空属性 | 2.0 |
| 主键生成 | `@Column(generatedKey)`、主键生成器 | `@Generated` 加 `db.insertKeys(insert)` 取回数据库生成的值；不做应用侧的主键生成器 | 2.0 |
| 插入或更新 | `onConflictThen` | `.onConflict(keys).update(cols)` / `.doNothing()`：HANA `MERGE`、PostgreSQL `ON CONFLICT`、MySQL `ON DUPLICATE KEY UPDATE` | 2.0 |
| 逻辑删除 | `@LogicDelete` 加策略（含 `DELETE_LONG_TIMESTAMP`） | `@LogicDelete(active, deleted)`，数值、文本或时间戳列，`epochMillis = true` 时删除写当前毫秒数；查询自动过滤，删除改成 UPDATE，`.physically()` 物理删除 | 2.0 |
| 审计字段 | 拦截器 `EntityInterceptor` | 数据库实例的 `onInsert` / `onUpdate`，按父接口匹配，总是覆盖；单条语句 `.withoutFills()` 不填 | 2.0 |
| 列级写入策略 | `@InsertIgnore`、`@UpdateIgnore` | `@ReadOnly`（从不写）、`@WriteOnce`（只在插入时写） | 2.0 |
| 联表更新、删除 | 导航属性隐式 JOIN（`updatable(...).where(c -> c.user().name()...)`） | 写语句的 WHERE 经过导航时写成 `EXISTS (... LEFT JOIN ...)`，三库语义一致；显式联表写法只在方言包：`updateJoin` / `deleteJoin`，HANA 写成 MERGE 防止一行匹配多行时误写 | 2.0 |
| 是否存在 | `any()` | `db.exists(q)` | 2.0 |
| 零行或一行 | `singleOrNull`、`firstNotNull` | `db.optional(q)`（多于一行报错）；必须有值用 `db.first(q).orElseThrow(...)` 或 `db.one(q)`；没有行、多于一行分别抛 `EmptyResultException` / `TooManyRowsException`（TS 为 `...Error`），代替可替换的异常工厂 | 2.0 |
| 执行前拦截 | `JdbcExecutorListener`、`Interceptor` | `config.intercept(...)` / `interceptors`：改写 SQL 或抛异常拦下，作用于所有语句 | 2.0 |
| 装回关联数据 | `fillMany`、`fillOne` | `db.attach(rows, key, ids -> query, keyOf, setter)`、`attachMany` | 2.0 |
| 主键生成器 | `PrimaryKeyGenerator` | `config.generate(T.class, t -> t.id(), gen)` / TS `generate(...)`：只给为 null 的行生成，不写回对象 | 2.0 |
| 条件对象的自定义比较 | `@EasyWhereCondition(type = ...)` 的扩展 | `@Where(using = X.class)` / `@where(fn, ...)` | 2.0 |
| 拦截器的启用与顺序 | `enable()`、`order()`、`apply(entityClass)` | 过滤器、填充按添加顺序生效，按表接口的父类型匹配；动态开关在回调里返回 `Cond.EMPTY` | 2.0 |
| 事务 | 自带事务管理 | 不用 Spring 时 `db.transaction(work)`；用 Spring 时交给 `@Transactional` | 2.0 |
| 实体追踪与差异更新 | `track`、`asTracking()`，线程绑定的快照 | 不做线程绑定的跟踪；差异更新写成 `update(T).byId(edited).diffFrom(loaded)`，由调用方给出修改前的行 | 2.0 |
| 对象图级联保存 | `savable(root)` | 暂不做；随流程引擎再按"显式一层子行同步加中间表"评估 | 3 |
| DDL、迁移 | code first、migration | — | — |

## 流程引擎的读查询

为自研流程引擎做的准备：按 Flowable、Camunda 7、Activiti 7、jBPM 7、Warm-Flow、SnakerFlow 的映射文件和查询代码整理出最常见的读查询，用本库重写，表结构简化后放在测试夹具 `Workflow`（Java）和 `fixtures/workflow.ts`（TS）。每种写法都有金样例（`spec/render/workflow-*.sql`），并在三个库上实库核对结果。

| 场景 | 引擎里的写法 | 本库的写法 | 金样例 |
| --- | --- | --- | --- |
| 待办：办理人是自己，或候选人、候选组含自己 | Flowable `ASSIGNEE_ = ? OR (ASSIGNEE_ IS NULL AND EXISTS (身份链接 ... USER_ID_ = ? OR GROUP_ID_ IN (...)))`，可选条件由 MyBatis 拼接，列表与计数共用 | 任务表的 `candidates()` 一对多关系加 `any(...)`；可选条件写成条件对象（定义 key 走多对一关系路径）；`db.page` 同时给出总数 | `workflow-todo` |
| 按流程变量筛选实例（EAV 表） | Flowable 每个条件一个 EXISTS；Activiti 每个条件一个别名 JOIN | 一个三行的辅助方法 `variable(p, name, v -> 条件)` 包住 `p.variables().any(...)`；也可以加 `groupJoin` 合成一个分组派生表，或用两次 `join` | `workflow-variables`、`-grouped`、`-joined` |
| 每个 key 的最新版本 | 相关子查询 `VERSION_ = (SELECT MAX(VERSION_) ... WHERE KEY_ = RES.KEY_)`；Camunda 用分组派生表 | 自关联 `versions()` 加 `d.version().eq(d.versions().max(...))`；或 `qualify(rowNumber() ... = 1)` | `workflow-latest`、`-ranked` |
| 已办 | 历史任务 `END_TIME_ IS NOT NULL AND ASSIGNEE_ = ?` 按结束时间倒序分页 | 普通条件加 `db.page` | —（实库场景） |
| 定义统计（运行中、已结束、平均耗时） | Camunda 三个分组派生表再 LEFT JOIN 回定义表 | `groupJoin(d -> d.instances())` 加带 `where` 的 `count()` 和 `avg(...)` | `workflow-statistics` |
| 办理人工作量 | `GROUP BY ASSIGNEE_` 加 `SUM(CASE WHEN ...)` | `groupBy` 加 `count().filter(...)` | `workflow-workload` |
| 读取时计算耗时 | Warm-Flow、SnakerFlow 不存耗时，按方言写日期差 | `secondsBetween(start, end)` | `workflow-elapsed` |

写入一侧（乐观锁的 `UPDATE ... WHERE REV_ = ?`、认领任务的 `FOR UPDATE SKIP LOCKED`、批量插入和逻辑删除）由 2.0 的写入能力覆盖，见上表和 [0007](decisions/0007-writes.md)；认领任务的金样例是 `workflow-claim`。流程引擎本身暂不做。

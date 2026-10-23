# 变更记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [2.0.0] - 未发布

查询写法的重做：Java 的表改为接口，增加导航关系、对象树、动态列、条件对象和原生 SQL 片段，删除重复和复杂的写法。升级对照见[迁移指南](docs/migration.md#从-1x-升级到-20)，设计取舍见 [0006](docs/decisions/0006-query-model-direction.md)。1.x 已有场景的金样例除 `postgres-aggregates`（聚合过滤改为 CASE 写法）外全部不变。

### 不兼容的变化

- Java 的表是继承 `Entity`、带 `@Table` 的接口，列是返回 `Col<T>` 的抽象方法，注解写在方法上；lambda 里直接写 `l.material()`。CTE、派生表等行类来源仍用 `r.col(Row::x)`。声明为类的 `@Table` 会报错。
- 投影只留三种：单个表达式、按名取列的 `cols(...)`（Map 行）、按位置构造的 `row(X::new, ...)`；加上按同名映射的 `selectAs(X.class)` 和 `into(X.class)`。删除函数式投影、`selectAs(X, 额外列)`、`selectValue`、`@MapFrom`、`Nest` / `nest`。
- 可选条件统一用作用域上的 `skipEmpty()`：与 null、空白字符串、空集合比较的条件自动省略；不开启时与 null 比较报错。删除 `eqIfPresent` 等 `IfPresent` 系列和 `likeIfPresent`，以及 `(表, getter)` 比较简写。
- 原来分在方言门面里的同类函数并入核心：`stringAgg`（取代 MySQL 的 `groupConcat`）、`daysBetween`（取代 MySQL 的 `dateDiff`，参数顺序为 from、to）、`addDays`、`addMonths`、`addYears`；PostgreSQL 的 `filter(agg, cond)`（TS `filterWhere`）改为所有方言通用的 `agg.filter(cond)`，写法从 `FILTER (WHERE ...)` 变为 `CASE WHEN`。
- 改名：`mapTo` → `into`，`SimpleCase` → `Case.Simple`，`Entity.all()` → `columns()`；删除单参数的 `coalesce(Expr)` 重载。
- Java 包按职责分子包：`table`、`expr`、`scope`、`select`、`exec`（另有 `annotation`、`dialect`、`meta`、`type`），根包只有 `Sql` 和 `Fn`。

### 新增

- **作用域与投影的上限**：由脚本生成，Java 作用域最多 16 张表，`row(...)` 最多 22 个参数，可以嵌套。
- **导航关系**：Java 的 `one(...)` / `many(...)` 写在表接口的 default 方法里，或用 `@ManyToOne`、`@OneToMany` 按列名配对；TS 的 `@one` / `@many` 字段。多对一用到时才 LEFT JOIN，同一关系只连接一次，可以继续导航，全局过滤器进入 ON。一对多提供 `any`、`none`（EXISTS）、`count`、`sum`、`max`、`min`、`avg`（相关子查询）、`where`、`first(order)`（ROW_NUMBER 取每组第一行）。
- **分组连接**：作用域上的 `groupJoin(m -> m.deliveries())` 把同一关系上的 `count`、`sum`、`any` 等合成一个按连接键分组的派生表，只 LEFT JOIN 一次，代替每个运算一个相关子查询；关系上的 `where` 写成各自聚合的 CASE WHEN。
- **对象树**：`many.list(...)` 给每个父行一个子列表，父查询只多取连接键，子行用一条按键批量的 IN 查询加载（每 500 个键一批），可以逐层嵌套；`many.orderBy(...)` 决定子行顺序。Java 的 `selectAs(VO.class)` 按同名关系自动映射嵌套 VO 和 `List<X>` 属性。
- **动态列与排序**：`exclude`、`excludeIf`、`only` 把不要的列写成 NULL，相同的分组键和只被它们用到的连接一起去掉；`sortBy("total desc, material")` 只认结果列，可以直接传用户的排序参数。
- **条件对象**：`where(search)` 把对象的每个非空字段变成条件，默认与同名列相等（集合用 IN），`@Where` / `@where` 指定比较方式和列，列可以走关系路径（经过一对多关系时写成 EXISTS，同一关系的字段合并进一个 EXISTS），多列为 OR，`@Ignore` 的字段跳过；字段名找不到列时报错。
- **条件聚合**：`sum(x).filter(cond)`、`count().filter(cond)` 等只聚合满足条件的行，三个库都写成 `AGG(CASE WHEN cond THEN x END)`，多次调用按 AND 合并。
- **日期函数**：`year`、`month`、`day`、`addDays`、`addMonths`、`addYears`、`daysBetween`、`secondsBetween` 在三个库上语义一致（月末对齐、可为负、秒数舍去小数）。
- **文本聚合与半开区间**：核心 `stringAgg(e, sep, order...)`；`e.range(from, until)` 写成 `>= from AND < until`，skipEmpty 下缺少的一端被忽略。
- **窗口条件**：`qualify(cond)` 按窗口函数过滤（每组前 N 行），三个库都渲染成派生表（HANA 没有 QUALIFY）；窗口函数增加 `firstValue`、`lastValue`、`ntile`，`lag`、`lead` 可给越界默认值。
- **一对多与分页**：`many.orderBy(...).limit(n).list(...)` 每个父行只取前 n 个子行；`many.countDistinct(...)`、`many.stringAgg(...)`（顺序取 `orderBy`）；`db.page(q, page, size, total)` 沿用已知总数，不再执行 COUNT。
- **文本匹配**：`contains`、`startsWith`、`endsWith`，用户输入里的 `%`、`_` 按普通字符匹配。
- **原生 SQL 片段**：`sql`、`sqlCond` 的参数可以混用表达式和值，集合展开成多个参数；`.on(方言类, 模板)` 按方言换写法；`sqlTable(...)` 把原生查询当作数据源；`identifier(name)` 按方言加引号。
- **打印与测试 SQL**：`Rendered.debugSql()`（参数写回原处）、`parameterText()`；数据库实例的 `printSql` 选项按 MyBatis 的格式打印语句、参数、行数和耗时；`db.capture(...)` 收集一段代码执行的全部语句；`ConnectionProvider.dryRun()` / `dryRunDriver()` 不连数据库也能执行。

### 修复

- 空跑或没有行时 `count` 不再出错。

## [1.0.0] - 2026-10-22

第一个正式版本。Java 发布到 Maven 中央仓库（坐标 `org.dpdns.zerodep:query-*`，Java 包名仍是 `org.dpdns.zerodep.query`），TypeScript 发布到 npm（`@zerodep-dev/query-core`、`-postgres`、`-mysql`、`-hana`）。

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

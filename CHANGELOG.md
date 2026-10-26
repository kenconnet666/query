# 变更记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [2.1.0] - 未发布

### 新增

- `wire` 的 `Json.write(value)`：`Json.read` 的反向，把 Map、List、文字、数字、布尔与 null 写成紧凑的 JSON 文字（BigDecimal 不用科学计数法），不依赖 JSON 库；给存进库里的 JSON 列用（流程引擎的变量、运行状态）。
- 业务错误码（`spec/error-codes.json`，两端一致）：抛出的异常都带 `code`，写法是 `query.empty-result` 这样带命名空间的文字，HTTP 接口原样写进响应体，前端按码取文案。Java 的异常实现 `CodedException`（`code()` 是 `QueryErrorCode`），TS 的错误类带 `code` 字段，清单是 `QUERY_ERROR_CODES` 与本地库的 `IDB_ERROR_CODES`。
- 表格请求读不懂时报 `InvalidRequestException` / `InvalidRequestError`（`query.request-invalid`）：页码、条数写错，条件树写错，以及 `db.table` 里排序、筛选用了结果里没有的列。Java 的它继承 `IllegalArgumentException`。

### 变化

- TS 的 `parseTableRequest` 以前对写错的数字报 `RangeError`、对写错的条件树报 `TypeError`，现在都报 `InvalidRequestError`；`sortBy`、`filterBy`、`searchBy`、`conditionBy` 的列名错误由 `Error` 改为 `TypeError`（与 Java 的 `IllegalArgumentException` 对应）。

### 修复

- `query-it` 不再参与签名：`-P release deploy` 以前会在这个只有测试的模块上失败。

## [2.0.0] - 2026-10-26

查询写法的重做：Java 的表改为接口，增加导航关系、对象树、动态列、条件对象和原生 SQL 片段，删除重复和复杂的写法；同一版本还带来 SQLite 方言（两端，兼容 SQLite WASM）、PolarDB 方言和面向前端的 IndexedDB 本地存储。升级对照见[迁移指南](docs/migration.md#从-1x-升级到-20)，设计取舍见 [0006](docs/decisions/0006-query-model-direction.md)、[0009](docs/decisions/0009-sqlite-dialect.md)、[0010](docs/decisions/0010-indexeddb-local-store.md)、[0012](docs/decisions/0012-polardb-dialect.md)。1.x 已有场景的金样例除 `postgres-aggregates`（聚合过滤改为 CASE 写法）外全部不变。

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
- **写入**（[0007](docs/decisions/0007-writes.md)）：不可变的 `insertInto(T).values(rows)` / `.from(query)`、`update(T).set(...).where(...)`、`deleteFrom(T).where(...)`，`db.execute` 返回影响的行数；没有 WHERE 的更新和删除报错（`.all()` 放行），全局过滤器进入 WHERE，`expect(n)` 校验行数；多行写入自动在一个事务里，Java 用 JDBC 批量。
- **按主键写与乐观锁**：`@Id`、`@Version`（TS `@id`、`@version`），`update(T).byId(rows)`、`deleteFrom(T).byId(rows)`，版本不对时抛 `UnexpectedRowCountException`（TS `UnexpectedRowCountError`）。
- **生成主键**：`@Generated` 加 `db.insertKeys(insert)`，按行的顺序返回数据库生成的值。
- **插入或更新与加锁读**：`.onConflict(keys).update(cols)` / `.doNothing()`（HANA `MERGE`、PostgreSQL `ON CONFLICT`、MySQL `ON DUPLICATE KEY UPDATE`）；`.with(forUpdate(Lock.SKIP_LOCKED))`，HANA 写作 `IGNORE LOCKED`。
- **事务**：`db.transaction(work)`（Java 线程绑定连接，TS 用 AsyncLocalStorage）；接入 Spring 时交给 `@Transactional`。TS 的 `DriverResult` 增加 `affected`、`insertId`，`Driver` 增加 `transaction`。
- **逻辑删除与审计填充**：`@LogicDelete`（TS `@logicDelete()`）让查询、连接和关系自动只看未删除的行，删除改写成 UPDATE，`.physically()` 物理删除；数据库实例的 `onInsert` / `onUpdate`（TS `fills`）在插入、更新、插入或更新和逻辑删除时自动填充审计列。
- **差异更新与写入策略**：`update(T).byId(edited).diffFrom(loaded)` 只 SET 变了的属性，没变的行不发语句；`@ReadOnly`（插入和更新都不写）、`@WriteOnce`（只在插入时写，TS `@readOnly`、`@writeOnce`）；插入、更新、删除的 `withoutFills()`。
- **过滤与 schema**：具名的全局过滤器 `filter("tenant", ...)`，查询和写语句可以 `unfiltered("tenant")` 单独关闭、`withDeleted()` 只关逻辑删除；`Config.schema("ERP", "SAPHANADB")` / `schema(fn)`（TS `schemas`）把表声明里的 schema 换成实际名字。
- **按编码的枚举与毫秒逻辑删除**：Java 实现 `CodeEnum<C>` 的枚举按编码存取；`@LogicDelete(epochMillis = true)`（TS `@logicDelete({ epochMillis: true })`）删除时写当前毫秒数，`UNIQUE (业务键, 删除列)` 允许同一个键删除后再建。
- **函数**：`lpad`、`rpad`、`mod`、`power`、`sqrt`、`sign`、`hour`、`minute`、`second`、`addHours`、`addMinutes`、`addSeconds`、`monthStart`，三库实测语义一致。
- **执行**：`db.exists(q)`、`db.optional(q)`（零行或一行，多于一行报错）。
- **结构变更**（0008）：`@Column` 的长度、精度、可空、默认值、注释、`previousName`，`@Table(external, comment)`，`@Index`、`@ForeignKey`（TS 是装饰器选项）；`db.schema().check(...)` / `plan(...)` / `createTable(...)` / `read(...)`：安全的差异自动执行，有风险的要 `allowDestructive()`，库里多出的对象只报告；三库读系统目录（information_schema、pg_catalog、SYS 视图），HANA 不能就地缩短列时只报告。版本化迁移 `db.migrator(migrations)`：代码写的迁移（`TableBuilder`、方言专属的 `sql(...)`、写语句做数据变更）、历史表与校验和、锁表互斥、`plan` / `verify` / `baseline` / `repair` / `migrateTo`。
- **分区与分表**（0008）：`@TableOption(dialect, sql)`（TS `@table` 的 `options`、`tableBuilder(...).option`）写原生分区子句；同库物理分表 `Config.shard(table, ShardRule.monthly/modulo/custom)`（TS 选项 `shards: [shard(...)]`），按 WHERE 里分表键的条件选表（AND 取交、OR 取并），一张时直接写那张表、多张时写成 `UNION ALL`，推不出来时报错，`allShards()` / `onShards(...)` 显式选择，子查询沿用；插入按行、更新删除按条件或 byId 的行路由，改分表键报错；`db.schema()` 按物理表建表和比较。
- **实体追踪**（0008）：`db.tracking(t -> ...)`（Java 另有返回结果的 `tracked`），`t.one` / `optional` / `list(表, 查询)`、`track`、`forget`；同一主键只登记一份，范围正常结束时在一个事务里写 `byId(行).diffFrom(快照)`，成功后写回版本并刷新快照，异常或冲突时不动；范围绑定线程（TS 异步上下文），嵌套加入外层。`Update.changedRows()` 返回 byId 里会被写的行。
- **从数据库生成列**（0008）：Maven 插件 `query-maven-plugin` 的 `query:pull` 和 TS 方言包的 `query-pull-hana` / `query-pull-postgres` / `query-pull-mysql`，按配置的表（可只取部分列）生成只有列的父接口 / 基类，表接口继承它；内容不变不重写，`only` 只重新生成部分表，三库读回同一张表时生成相同的源码。读回的结构里默认值统一成 SQL 写法，并带上表和列的注释；TS 增加 `smallint` 类型。
- **方言包的结构能力**：PostgreSQL、MySQL 的 `intersectAll` / `exceptAll`、RANGE 窗口帧（`w.frame(range(daysPreceding(7), Bound.currentRow()))`）、`recursiveCte`、`forShare`、`forUpdateOf`；PostgreSQL 的 `db.returning(write, ...)`、`percentileCont` / `percentileDisc` / `mode` / `corr`；HANA 的 `median`、窗口式 `percentileCont` / `percentileDisc`、`corr`；核心作用域的 `fullJoin`（HANA、PostgreSQL）。删除 PostgreSQL 的 `pg(e).isDistinctFrom`，改用核心的 `isDistinctFrom`。
- **方言包的函数与来源**：HANA 的 `fromView`（计算视图参数）、`fromFunction`（表函数）、`hierarchy` / `descendants` / `ancestors`、`contains` / `containsFuzzy` / `score`、`jsonValue`；PostgreSQL 的数组（`eqAny`、数组列、`arrayContains`、`arrayOverlaps`、`cardinality`、`arrayAgg`）、JSONB（`jsonText`、`jsonContains`、`jsonHasKey`、`jsonb`）、全文检索（`textMatches`、`webMatches`、`textRank`）；MySQL 的优化器提示 `index` / `noIndex`（与 `maxExecutionTime` 合并进一个注释）、JSON（`jsonText`、`jsonContains`）、全文检索（`match`、`matchBoolean`、`matchScore`）。
- **定制**：执行前钩子 `config.intercept(...)` / TS `interceptors`（改写或拦下每条语句，缓存按改写后的 SQL 作键）；插入时逐行生成值的 `generate(...)`；条件对象的自定义比较 `@Where(using = ...)` / `@where(fn, ...)`；`db.attach` / `attachMany` 按键装回另一个查询的结果；`db.one` / `optional` 抛 `EmptyResultException`、`TooManyRowsException`（TS `EmptyResultError`、`TooManyRowsError`）。
- **集合运算与连接**：`intersect`、`except`（按调用顺序求值，三库读法可能不同处自动加括号）、`rightJoin`（左侧各表的全局过滤条件进它的 ON；TS 中之前各参数变成可空）。
- **子查询比较与条件列**：多列 `keys(a, b).in(q)`、`anyOf(q)` / `allOf(q)`、NULL 安全的 `isNotDistinctFrom` / `isDistinctFrom`（PostgreSQL 原生、MySQL `<=>`、HANA 用 CASE）、条件作为布尔列 `bool(cond)`。
- **聚合与窗口**：`sumDistinct`、`avgDistinct`、`stddevSamp`、`stddevPop`、`varSamp`、`varPop`，`percentRank`、`cumeDist`、`nthValue`。
- **联表更新与删除（方言包）**：`updateJoin(T, S, on).set(...).where(...)`、`deleteJoin(...)`；PostgreSQL `UPDATE ... FROM` / `DELETE ... USING`，MySQL `UPDATE ... JOIN ... SET` / `DELETE 别名 FROM ... JOIN`，HANA 按主键的 MERGE（一行匹配多行时报错）/ `WHERE EXISTS`。
- **打印与测试 SQL**：`Rendered.debugSql()`（参数写回原处）、`parameterText()`；数据库实例的 `printSql` 选项按 MyBatis 的格式打印语句、参数、行数和耗时；`db.capture(...)` 收集一段代码执行的全部语句；`ConnectionProvider.dryRun()` / `dryRunDriver()` 不连数据库也能执行。

### 修复

- 空跑或没有行时 `count` 不再出错。
- UPDATE、DELETE 的 WHERE 经过多对一导航时渲染出没有连接的列（SQL 无效）；现在写成 `EXISTS (... LEFT JOIN ...)`，语义与查询里的导航相同，SET 里用导航时报错。

### 新增：SQLite、PolarDB、IndexedDB 与其后的补充

- **SQLite 方言**：Java `query-sqlite`（sqlite-jdbc）与 TS `@zerodep-dev/query-sqlite`（`node:sqlite` 与 `@sqlite.org/sqlite-wasm` 两个驱动），金样例与三库逐字节对照，实库场景随单元测试运行。方言包另有视图 `sqlite(e)` 的 `glob`、`median`，以及 `percentileCont`、`percentileDisc`、`updateJoin`、`deleteJoin`、`query-pull-sqlite`。
  - 需要编译选项的函数照常渲染，每个数据库实例第一次渲染时警告（TS 选项 `warn`）；ROLLUP、`grouping()`、`forUpdate()` 报错，TS 从 SQLite 包导入时写不出来。
  - 结构变更里不能就地改的列归入只报告（REBUILD）。
- **核心的方言钩子**：按名字覆盖函数渲染、窗口、CAST、算术、量化子查询、集合运算的分组、写语句的别名；结构变更的能力声明（能否改列、内联外键、注释）。
- **浏览器里的核心包**：`db.transaction(async (tx) => ...)` 的回调收到绑定在事务上的数据库；库内部的多语句写入、追踪写回和迁移都经由它执行，不依赖 AsyncLocalStorage。迁移的校验和改用 Web Crypto，结果不变。
- **IndexedDB 本地存储** `@zerodep-dev/idb`（不依赖核心包）：`@store` / `@key` / `@index`（unique、multiEntry、optional、boolean / bigint / lowercase 编码器）、`@one` / `@many` 与 `include`；`openLocal` 按版本升级并核对结构；只按索引查询，`scan` / `sortBy` 写在明处；事务回调 await 了别的工作时报 `TransactionClosedError`；批量写入整批回滚并列出失败的行；实时查询按仓库失效，并经由 BroadcastChannel 通知其他标签页；`QuotaError`、`persist`、`estimate`。
- 开发编译器升到 TypeScript 7.1.0-dev.20261023.1（7.1 的 dev 构建）；`exclude` / `excludeIf` 的返回形态改用具名类型 `ExcludedKeys<R, K>`（7.1 不再把两处同样的内联映射类型当成同一个类型），核心包导出它。
- 投影、`groupJoin` 和写入的列筛选用品牌类型 `AnyMany`（核心包导出）认一对多关系，不再写成 `Many<any>`：编辑器只检查单个文件时，`Many<Lips>` 赋不给 `Many<any>`，带关系的投影被推成 `Query<unknown>`（TS2769）。`groupJoin` 收到的不是一对多关系时报 `TypeError`。
- 测试：Chrome 里的浏览器测试（`corepack pnpm test:browser`，`playwright-core` 用本机或 runner 预装的 Chrome）。
- **Spring 的事务管理器**：`SpringConnections.of(dataSource, transactionManager)` 之后，`db.transaction(...)` 与多行写入的自动事务由这个事务管理器执行（传播行为 REQUIRED，已有事务时加入）。多数据源的项目每个数据源各传自己的事务管理器；只给 DataSource 时仍然报错。连接来源的接口加了 `startsTransactions()` 与 `transaction(work)`。
- **迁移历史表可以改名**：`migrator.historyTable("flow_migration")`（两端），锁表跟着改名；同一个库里几套各自迁移的表不再共用 `QUERY_MIGRATION`。
- **PolarDB 方言**（[0012](docs/decisions/0012-polardb-dialect.md)）：Java `query-polardb` 与 TS `@zerodep-dev/query-polardb` 继承 MySQL 方言（MySQL 的全部实库场景在开源的 PolarDB-X 引擎上原样通过），另有闪回查询 `fromAsOf(表, 时刻)` 与热点行的库存提示 `inventory(n)`。`infra/polardb` 是本机与 CI 用的单节点引擎实例。`Mysql`、`MysqlDatabase` 去掉 final，TS 的 `MysqlDatabase` 构造器可以传方言，`Hint` 可以指定所属方言。
- **写语句的提示**：`update(...)` / `deleteFrom(...)` 的 `with(t -> 提示)`（两端），提示写在关键字后面并按方言合并；金样例 `mysql-update-hints`、`mysql-delete-hints`。
- **金样例可以沿用别的方言**：Java `RenderSpec.fallbackDialect()`、TS 的 `fallbacks`；PolarDB 只有专属写法有自己的文件。
- **流程引擎写法的实库场景**（两端、五个库）：并发汇聚只推进一次、重复提交、作业领取、候选与排除、长 JSON 文本、流转记录分页。
- **通用门面**（[0013](docs/decisions/0013-portable-facade-and-live-tests.md)）：Java `Sql.database(DataSource | ConnectionProvider, configure)` 按连接报告的产品名选方言（方言包经 `dialect.DialectProvider` 登记），TS 核心包的 `database(driver, options)` 用驱动适配器带的方言（`Driver.dialect`）。只用核心写法的代码不必依赖具体的方言包；PolarDB 得到 MySQL 方言。
- **测试：实库测试按主题拆分**（0013）：Java 全部搬进不发布的 `java/query-it`，测试类标 `@LiveTest(only / except)` 在每个选中的库上各跑一遍；TS 的 `test/src/live` 按同样的主题拆成 `liveSuite`；环境变量 `QUERY_DATABASES` 选库，CI 的实库 job 只设它。建表一律按声明生成，查询写回各个测试里。
- **typed JSON**（[0015](docs/decisions/0015-typed-json-wire.md)）：Java `java/wire`（`org.dpdns.zerodep:wire`，零依赖）与 TS `@zerodep-dev/wire`。`Wire.encode` / `toWire` 把大整数、小数、日期、时间、时间戳（带不带时区）、NaN 与无穷、字节写成文字，根上附 `$types`（路径到类型），`decode` / `fromWire` 按它还原；结果是普通的 Map / List 树，任何 JSON 库都能写。不限于查询结果，两端共用 `spec/wire` 的样例。查询库读地址栏里的值（表头筛选、条件对象的文字字段、区间）也用它的文字格式。
- **前端表格的请求**（[0016](docs/decisions/0016-table-query-contract.md)）：`sortBy` 认 JSON:API 的 `-amount,name`；`filterBy`（多选值写成 IN）、`searchBy`（LIKE）只认结果列，值按列的类型从文字读出，聚合列的条件放进 HAVING（渲染时判断，`FunctionDef.aggregating()` 标出方言的聚合函数），窗口列报错；`TableRequest.parse(params)` / `parseTableRequest(params)` 读 `page`、`pageSize`、`sort`、`total`、`filter.列`、`search.列`，条数上限默认 2000；`db.table(q, request)` 依次筛选、搜索、排序、分页，带了已知总数时不计数；`Page.toWire()`。
- **TS 转换器列的文字**：`converted(storage, converter, name, field)` 的第 4 个参数声明字段值在线上的种类，表头筛选、条件对象、条件树里的文字先按它读成字段值再经转换器存（以前原样交给绑定，日期存成文本的列筛不出来）。
- **一对多 `first(...)` 在 PostgreSQL、MySQL 上用 LATERAL**（[0018](docs/decisions/0018-lateral-first.md)）：方言能力 `supportsLateral()`；这两个库写成按父行取一行的 `LEFT JOIN LATERAL (… LIMIT 1) ON TRUE`，不再给整张子表排名；HANA、SQLite 与小版本未知的 PolarDB 仍写成 ROW_NUMBER 派生表。金样例 `one-to-many` 随之更新，PolarDB 有了自己的文件。
- **条件树**（[0017](docs/decisions/0017-condition-tree.md)）：`Query.conditionBy(tree)`（两端）接前端条件构造器的 all / any / not 加比较，14 种比较方式；字段只认结果列，值按列的类型读，与前端 `matchesCondition` 的规则一致（不等于类把空值算作满足、没填完整的比较与空组不加条件），最外层的 all 拆开各自进 WHERE 或 HAVING。`TableRequest` / `parseTableRequest` 读 `condition` 参数，`db.table` 随之筛选。Java 的 `ConditionTree`（`parse`、`from`）与 wire 模块的 `Json.read`（不依赖 JSON 库）；TS 的 `parseConditionTree`、`conditionTreeCond`。
- **条件对象的区间与文字**：`Range<T>` 字段（`@Where(RANGE)` / `@where("range")`）写成 `>= 起 AND <= 止`，缺一端只写另一端；时间戳列配日期时止端写成 `<` 后一天的零点，整天都算在内；文字字段按列的类型读（`textAs`），地址栏里的参数可以直接装进条件对象。
- **计数直接数行**：单段查询没有分组、DISTINCT、HAVING、QUALIFY、LIMIT、OFFSET、方言子句，投影和按结果列的条件里没有聚合时，`count` / `page` 写成 `SELECT COUNT(*) FROM ... WHERE ...`，投影里的子查询、只为投影连接的关系和 ORDER BY 都不写；否则仍在外面包一层。
- **多对多**：Java `many(UserRole.class, ...).through(ur -> ur.role())`，TS `@manyThrough((u) => u.userRoles, (ur) => ur.role)` 或查询时的 `through(...)`；查询中间表，用到的目标列 LEFT JOIN 过去，`any`、`count`、`list`、`first`、`groupJoin`、条件对象的路径都照常可用。`Many.where(条件对象)` 两端都可以用。
- **树形数据**：`Trees.build` / `buildTree` 把带父键的平铺行组装成树（同级保持顺序，键重复或成环时报错）；`db.withAncestors(表, key, parent, hits, select)` 取命中的行和全部祖先，PostgreSQL、MySQL（PolarDB）、SQLite 一条 `WITH RECURSIVE ... UNION`（`Dialect.supportsRecursiveCte`），HANA 一层一层按键查；不来自 SQL 的数据用 `Trees.withAncestors` / `withAncestors(rows, { key, parent, load })`。`Cte.recursive` 可以写成 UNION。
- **前端配合**：vue-tsx-pro 的 `useTableQuery` 发出上面的表格请求（多列排序、表头筛选、已知总数），`http` 的 `wire` 选项按 `$types` 还原响应，`buildTree` 组装服务端搜索的结果；见两份 API 说明的"与前端配合"一节。

### 变更（不兼容，相对上面写法的进一步调整）

- **Java：按逻辑 SQL 类型分的列与表达式**（[0014](docs/decisions/0014-typed-expressions.md)）：列声明成 `ColText<String>`、`ColInteger<Integer>`、`ColBigint<Long>`、`ColDecimal<BigDecimal>`、`ColDate<LocalDate>`、`ColTimestamp<LocalDateTime>` 等（包 `table.column`），表达式接口同名（`expr.typed`），泛型参数是 Java 类型；经转换器存成文本的日期是 `ColText<LocalDate>`。`Expr<T>` 改成接口，自定义节点继承 `ExprNode<T>`。`ColClob`、`ColBlob` 代替 `@Column(lob = true)`。
- **Java：有主操作数的函数改成实例方法**：`upper(e)`、`sum(e)`、`addDays(d, n)`、`plus(a, b)` 等约 55 个静态函数删去，写成 `e.upper()`、`e.sum()`、`d.addDays(n)`、`a.plus(b)`；加减乘按"宽"的类型提升，`caseWhen`、`lag`、`greatest`、`val`、`lit` 的结果保住类型。方言函数经由视图：`hana(e).toVarchar(fmt)`、`postgres(e).ilike(p)`、`mysql(e).dateFormat(fmt)`、`sqlite(e).glob(p)`。
- **Java：语句式条件**：条件方法返回 `void`，写进条件块（`where(l -> { a; b; })`、`any(() -> {...})`、`all`、`not`、`bool(() -> ...)`、`caseWhen(() -> ..., x)`、`filter(() -> ...)`），可选条件直接写 `if`；`Cond.and`/`or`、`onlyIf`、`ifPresent` 删去。UPDATE 的 `set(t -> { t.x().to(v); ... })` 是赋值块。写错位置的条件和赋值立即报错。
- **TypeScript：小数与日期是值对象**：`decimal`、`date`、`timestamp` 列读写 decimal.js 的 `Decimal`、@internationalized/date 的 `CalendarDate`、`CalendarDateTime`（与前端组件一致），两个库是核心包的 peerDependencies；字段从 `string` 改成这些类型。绑定时仍接受文本形式（按格式校验）。`CalendarDateTime` 精确到毫秒，更细的部分读出来时舍去；`Decimal` 不保留末尾的零。`query-pull` 生成的字段同改。
- **TypeScript：按逻辑 SQL 类型分的列与表达式**：查询回调里的字段按字段类型成为 `ColText<T>`、`ColNumber<T>`、`ColBigint<T>`、`ColDecimal<T>`、`ColDate<T>`、`ColTimestamp<T>`（`Col<T>` 兜底），表达式接口同名，结果按值类型给出接口（`ExprOf<T>`）。以另一种类型存的转换器列写成 `Stored<CalendarDate, string>`，是 `ColText`；没写时 `@column` 在编译期报错。`Expr<T>` 改成接口，自定义节点继承 `ExprNode<T>`；`SqlType` 多了存储种类的类型参数。
- **TypeScript：实例方法、语句式条件与方言视图**：与 Java 相同——`upper(e)`、`sum(e)`、`addDays(d, n)`、`plus(a, b)`、`like(e, p)` 等静态函数删去，写成 `e.upper()`、`e.sum()`、`d.addDays(n)`、`a.plus(b)`、`e.like(p)`（加减乘按"宽"的类型提升，接着写的 `plus` 并成一个括号）；条件方法返回 `void`，写进条件块（`where((l) => { a; b; })`、`any(() => {...})`、`all`、`not`、`bool(() => ...)`、`caseWhen(() => ..., x)`、聚合的 `filter(() => ...)`、关系与全局过滤器的回调），`Cond.and`/`or`/`not`、`onlyIf`、`ifPresent` 删去；`sqlCond` 一写下就在块里，`.on(...)` 改的是它本身。方言函数经由视图：`hana(e).toVarchar(fmt)`、`postgres(e).ilike(p)`、`mysql(e).dateFormat(fmt)`、`sqlite(e).glob(p)`；全文检索、数组等条件函数写进当前的块。UPDATE 的 `set` 仍是对象字面量，表达式要是这一列的那一类。PolarDB 的 `fromAsOf` 收 `CalendarDateTime`。
- Java：方言视图 `ViewString` 改名 `ViewText`，与逻辑类型的命名一致。
- `where(Object)` 收到 lambda（例如 `java.util.function.Consumer`）或没有字段的对象时报错：以前会当成条件对象，一个条件也不加。
- 金样例只有 `literals` 一处变了：小数常量由 12.50 改成 12.5（`Decimal` 不保留末尾的零，两端的用例输入同改）；其余新写法渲染出的 SQL 与原来逐字相同。

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

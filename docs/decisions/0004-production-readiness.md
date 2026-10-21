# 0004 生产就绪

- 状态：已采纳（2026-10-21）
- 承接 [0003](0003-road-to-1.0.md)：M1 到 M6 完成后，在 1.0.0 发布前补齐生产环境需要、但 1.0 蓝图没有覆盖的部分

## 背景

使用方是内部 ERP 分析大屏：Spring Boot 2.7、Java 17、Druid 连接池加动态数据源，直接查询 HANA。生产环境里除了"能不能表达查询"，还要回答四个问题：

1. 一条失控的报表查询能不能被及时取消？
2. 进入字面量的值会不会提前结束字面量，造成 SQL 注入？
3. 很长的 IN 列表会不会撞上数据库的参数上限，报出难懂的错误？
4. 在使用方以外的 JDK 版本上能不能跑？

## 决定

### 1. 查询超时

- **怎么设**：数据库实例上设默认值（Java `queryTimeout(Duration)`，TS `queryTimeoutMs`），查询和原生 SQL 可以用 `timeout(...)` 覆盖。不提供"按查询类型"的超时规则：报表的耗时差别大，在写查询的地方设置最直观。
- **Java**：用 JDBC 的 `setQueryTimeout`。它按整秒计，不足一秒向上取整，因为 0 表示不限时。
- **Spring 事务**：`ConnectionProvider` 新增一个默认方法 `prepared(PreparedStatement)`，`SpringConnections` 在里面调用 `DataSourceUtils.applyTransactionTimeout`。事务剩余的时间优先于数据库实例的默认值，与 JdbcTemplate 的行为一致，使用方从 JdbcTemplate 迁移过来时不会有意外。
- **TS**：三个 Node 驱动的能力不同，统一由驱动适配器在服务端取消：
  - HANA 用 `Statement.setTimeout`；
  - PostgreSQL 在语句前后设置并恢复 `statement_timeout`；
  - MySQL 在语句前后设置并恢复 `max_execution_time`。

  没有用客户端计时器：pg 的 `query_timeout` 只是放弃等待，语句会在服务端继续运行并占着连接。
- **区分超时**：`QueryExecutionException.isTimeout()`（TS 为 `QueryExecutionError.timedOut`）由方言判断。Java 默认认 `SQLTimeoutException`，PostgreSQL 另认 SQLState `57014`；TS 认 HANA 613、PostgreSQL `57014`、MySQL 3024。页面据此提示"查询超时"，监控可以单独计数。
- **不做**：超时后自动重试。报表查询超时多半是查询本身的问题，重试只会加重数据库负担。

### 2. 字面量

- 值默认绑定为参数，只有 SELECT 列表等位置的常量、`values(...)` 和 `lit(...)` 写成字面量（0002 第 10、11 条）。`values(...)` 的行可能来自运行时，所以字面量的转义也是安全边界。
- **PostgreSQL**：值含反斜杠时写成 `E'...'`。如果服务器或会话关闭了 `standard_conforming_strings`，`'...'` 里的反斜杠会转义下一个字符，`\'` 就能提前结束字面量；`E'...'` 在两种设置下读法相同。不含反斜杠的值仍然写成普通字面量，现有 SQL 不变。
- **MySQL**：本来就把反斜杠加倍。如果开启了 `NO_BACKSLASH_ESCAPES`，反斜杠会多出一倍，但不会提前结束字面量。这种情况只影响数据，不造成注入，所以保持默认 SQL 模式的写法，并在文档中说明。
- **非有限浮点数**：`NaN` 和 `Infinity` 没有标准的字面量写法，在 SELECT 列表里仍然绑定为参数，`lit(...)` 时直接报错。
- **TS 数值文本**：写成字面量前，必须符合 `decimal` 的格式。
- **LIKE**：`%`、`_` 不做自动转义。使用方的 LIKE 模式都是代码里的常量；各库的默认转义字符不同，HANA 需要 ESCAPE 子句。等有用户输入的模糊搜索时再设计。

### 3. 参数个数上限

- 方言新增 `maxParameters()`，默认 65535。执行前检查，超出时报出可读的错误，并建议拆分 IN 列表或改用子查询。
- Java 的 MySQL 不设上限：Connector/J 默认在客户端把参数写进语句。TS 的 mysql2 用服务端预处理语句，仍然是 65535。
- **不做**：自动把长 IN 列表改写成数组参数或临时表。HANA 没有数组参数；临时表要写库，本库只读。

### 4. 兼容性

- CI 增加 JDK 21 与 25 的单元测试，字节码仍按 release 17 生成。
- 1.x 的兼容性承诺写进 [API 使用说明](../api.md)第 10 节：主包、注解、类型、`Getter`、`NamingStrategy`、`exec` 包的四个接口与异常、方言门面和 `SpringConnections`。`dialect` 包是扩展点，次版本可以增加方法；`JdbcExecutor`、`MappingPlan` 等不在承诺范围内。

## 验证证据（2026-10-21，本机三库）

**JDBC 实测**（JDK 17，驱动版本与 pom 一致）：

| 项 | HANA 2.00.088 | PostgreSQL 17.11 | MySQL 8.4.11 |
| --- | --- | --- | --- |
| 最多参数个数 | 65535；65536 时报 `[307] numeric value out of range: exceed the number of the parameters` | 65535；驱动自己给出清楚的错误 | 10 万也能执行（Connector/J 在客户端写参数） |
| `setQueryTimeout(1)` | 约 1.1 秒后报错 `[613] execution aborted by timeout`（`SQLTimeoutException`） | 约 1.0 秒后报错，SQLState `57014` | 约 1.0 秒后抛 `MySQLTimeoutException` |
| 取消之后同一连接 | 可用 | 可用 | 可用 |

**HANA 慢语句**：HANA SQL 没有 sleep 函数，测试用 SQLScript 自带的 `SQLSCRIPT_SYNC` 库写了一个存储过程，`SLEEP_SECONDS` 可以被超时取消。

**Node 驱动实测**：

- @sap/hana-client 2.30.27：
  - `Statement.setTimeout(1)` 约 1 秒后报 613；
  - `Statement.exec` 的 `queryTimeout` 选项被忽略，语句睡满 3 秒；
  - `Connection.exec` 的同名选项有效。
- mysql2：`max_execution_time` 打断 `SLEEP(3)` 后返回 1 而不报错。真正在执行的 SELECT 超时会报 3024，所以 MySQL 的慢语句改用 10 亿行的计数。

**PostgreSQL 字面量**：`E'a\\b''c'` 在 `standard_conforming_strings` 为 on 和 off 时都读作 `a\b'c`。实库测试在关闭该设置的会话里验证含反斜杠的字面量能原样往返。

**实库场景**（两端、三库）：

- 恶意文本原样往返，分别作为 `values(...)` 的字面量、SELECT 列表常量和 WHERE 参数。文本包括 `O'Brien`、反斜杠、`\'; DROP TABLE x; --`、注释符、`? :name $1 $$` 和非 ASCII 字符。
- 慢语句分别用查询自己的超时和实例默认超时，都在约 1 秒内被取消，之后同一实例照常查询；带超时的流式读取能读完全部行。
- Spring：用 H2 验证事务超时覆盖实例默认值，以及事务时间已用完时抛出 `TransactionTimedOutException`。

**HANA 的 fetch size**：读 10 万行 5 列。驱动默认值（`getFetchSize()` 为 0，由驱动按数据包大小决定）约 55 ms，设成 10000 约 50 ms，设成 1000 反而约 90 ms。所以 HANA 不另设默认 fetch size。

**使用方技术栈上的影子试点**：在临时目录搭了一个最小的 Spring Boot 应用，版本与使用方相同：Spring Boot 2.7.11、Java 17、druid-spring-boot-starter 1.2.8、dynamic-datasource-spring-boot-starter 3.5.2、ngdbc 2.29.7，连本机 HANA，以下检查全部通过：

- 带 CTE、EXISTS、LEFT JOIN 和分组的查询结果正确；
- 用线程上下文切到第二个数据源后，查询走的是它的 Druid 连接池，之后能切回；
- `TransactionTemplate` 设 1 秒超时，慢语句约 1 秒后被取消，并标为超时；
- 查询自己的超时经过 Druid 的语句包装后仍然生效；
- 事务里的流式读取、分页都正常；
- 结束后两个 Druid 连接池的活动连接都是 0。

使用方仓库没有改动。

**顺带修复的问题**：

- **Java 分页**：`Page` 用 `List.copyOf` 复制结果，单列查询有 NULL 值时 `db.page` 抛 NPE。
- **TS 渲染**：`SqlWriter` 用 `push(...items)` 合并片段和参数，约 7 万个参数时超出调用栈，参数上限的检查因此根本走不到。

# 0002 API 与整体架构

- 状态：已采纳（2026-10-21）；Java 第一期与 TypeScript 第二期均已实现，并通过三库实库验证。第 1、3、5 条已由 [0006](0006-query-model-direction.md) 修订（2.0：接口表、导航关系、对象树、条件对象）；"用户必须选定方言包"与实库场景的组织已由 [0013](0013-portable-facade-and-live-tests.md) 修订（通用门面、query-it）；第 4 条（只给同类型的多参数运算用实例方法）已由 [0014](0014-typed-expressions.md) 修订（按逻辑 SQL 类型分的接口，有主操作数的运算都是实例方法）
- 修订 0001：
  - TypeScript 改以 7 为唯一基准；
  - 首批方言为 HANA、PostgreSQL、MySQL，三者同步推进；
  - DuckDB 延后。
- 用法见 [Java API 使用说明](../api.md) 与 [TypeScript API 使用说明](../api-typescript.md)，场景覆盖计划见[场景对照](../scenarios.md)

## 背景

0001 之后，用临时探针比较了多条 API 路线，并确定了取舍顺序：

1. 简洁、灵活、功能强大、方便用户扩展；
2. 类型安全尽量做到，但不能牺牲第 1 条；
3. 性能不是首要目标，但元数据、映射和渲染必须缓存或惰性计算。

同时明确了以下几点：

- 表和字段可以用注解，可以要求用户使用 Lombok；TypeScript 可以用装饰器。
- 结果要能方便地映射到不同的 DTO、VO 或临时类。
- 由共同的核心包加各方言包组成，库不抹平数据库之间的差异。实例可以由方言门面创建，也可以由通用门面按连接选方言创建（0013）；方言专属能力只在方言门面里。
- EasyQuery 的场景只作为参考清单，实现一律重新设计。

## 决定

### 1. 类就是行的形状

带 `@Table` 的类是表实体；不带的是临时实体，可以作为 CTE 的行、派生表的行或 DTO。同一张表可以有多个实体类，每个只声明自己用到的列。

列的类型来自 getter 的返回类型（Java）或字段声明的类型（TS）。

### 2. 语法由方言渲染，语义差异不模拟

- 核心包只包含 HANA、PostgreSQL、MySQL 三者语义一致的能力。
- 语法差异（引号、字面量、分页、`NULLS LAST`、`CONCAT` 等）由各方言渲染。
- 只有部分数据库具备的能力，只放在对应的方言门面里，在别的方言上渲染时明确报错，例如 HANA 的 `DAYS_BETWEEN`、PostgreSQL 的 `DISTINCT ON`、MySQL 的 `GROUP_CONCAT`。

### 3. lambda 作用域加 getter 引用，不使用 APT

- `from(A).join(B, (a, b) -> ...)` 中的每张表都是一个类型化参数 `Ref<E>`。`ref.col(getter)` 只接受 E 或其父类型、接口的 getter，用了不在作用域里的表的列就编译失败。
- 列名通过 SerializedLambda 解析，按 lambda 类缓存。
- `From1..From8` 和 `Fn.F1..F9` 由 `tools/generate-java-scopes.mjs` 生成后提交，使用方不需要生成任何代码。

### 4. 同类型的多参数运算必须是实例方法

javac 会把多个泛型实参推断成它们的公共父类型，所以 `coalesce(col(a), col(b))`、`eq(Getter, Getter)` 这类静态写法，在列类型不同时也能编译通过（已实测）。

规则如下：

- 比较、`coalesce`、`nullif`、`in`、CASE 的后续分支，都是 `Expr<T>` 上的实例方法，由接收者先固定 T；
- 静态函数只用于单参数泛型函数（`sum`、`max`），或参数类型写死的函数（`daysBetween(Expr<LocalDate>, Expr<LocalDate>)`）；
- 方言专属的同类型运算用包装对象实现，例如 `pg(e).isDistinctFrom(x)`。

### 5. 三种投影

| 投影 | 适用 |
| --- | --- |
| 函数式 `select((r, …) -> new X(...))` | 默认写法。先记录一次，再每行重放；不反射，能构造任意对象 |
| 按名 `selectAs(VO.class)` 或 `selectAs(Shape.class, …)` | 列有名字，可作为 CTE 或派生表；按标签映射到 record、`@Data`、`@Builder` |
| 单列 `selectValue(…)` | 标量子查询、`IN` 子查询、值列表 |

### 6. 方言是开放的类，扩展与内置同构

- 函数用 `FunctionDef` 定义。查找渲染的顺序是：数据库实例的覆盖、方言注册的渲染、函数自带的标准渲染。
- 子句实现 `Clause` 接口，位置分为 HINT、DISTINCT、END。
- 来源实现 `Source`，表达式节点继承 `Expr`。
- 内置能力和用户扩展走的是同一套机制。

### 7. 执行只依赖 `ConnectionProvider`

- 事务由使用方管理。Spring 应用用 `query-spring` 的 `SpringConnections` 取连接，查询就加入外层的 `@Transactional`（0001 第 6 条）。

- **全局过滤器**按实体类型匹配：FROM 的表加到 WHERE，JOIN 的表加到 ON；CTE 和子查询内的表同样生效。
- **结果缓存**存解码后的原始行，而不是映射后的对象，因为投影 lambda 可能捕获了外部变量。

### 8. 实例化、懒加载与缓存

| 对象 | 计算时机 | 缓存位置 |
| --- | --- | --- |
| 实体元数据、转换器实例 | 首次使用该类时 | `ClassValue` |
| getter 解析 | 首次使用该 lambda 类时 | `ClassValue` |
| 映射计划（构造器、setter、Builder 的 `MethodHandle`） | 某个目标类与某组标签首次配对时 | 按"目标类 + 标签"缓存 |
| 渲染结果 | 查询首次在某个数据库实例上执行时 | 记在不可变的查询对象上 |
| 大结果集 | `stream` / `forEach` 在单个游标上逐行读取 | — |

游标的前提由方言的 `openCursor` 钩子准备，因为各驱动的要求不同：

- **PostgreSQL**：pgJDBC 只在事务中、fetch size 为正时才用游标，否则先缓冲全部结果。方言在自动提交的连接上临时关闭自动提交，流关闭时回滚并恢复；外层已有事务（例如 Spring 的 `@Transactional`）时保持不动；没有配置 fetch size 时每批 1000 行。
- **HANA**：驱动本身分批读取，不需要准备。
- **MySQL**：Connector/J 默认也缓冲全部结果。逐行读取要配置 `fetchSize(Integer.MIN_VALUE)`（读完之前同一连接不能执行别的语句），或在 URL 中加 `useCursorFetch=true` 并配置正的 fetch size。两种做法都会改变连接的用法，所以不自动切换。

**原生 SQL 的参数扫描**：`:name` 只在引号、注释之外替换，"什么算引号"由方言的 `quotedEnd` 决定：默认是 `'…'` 和 `"…"`；PostgreSQL 另有 `$$…$$` 与 `$tag$…$tag$`（函数体常用）；MySQL 另有反引号，并且字符串里的反斜杠是转义符，与 MySQL 字面量的写法一致。

**UNION 的分组**：各段按左结合渲染，`a.union(b).unionAll(c)` 就是 `a UNION b UNION ALL c`。右侧本身是混用 UNION 与 UNION ALL 的组合时（`a.unionAll(b.union(c))`），渲染时给右侧加括号，三库都支持；右侧只用同一种运算符时满足结合律，不加括号。

### 9. TypeScript（第二期）

概念与 Java 一一对应，写法按 TS 的长处调整。

**包与构建**

- `@zerodep-dev/query-core` 加 `@zerodep-dev/query-hana`、`@zerodep-dev/query-postgres`、`@zerodep-dev/query-mysql`，放在 `typescript/` 下的 pnpm 工作区里，用 `tsc -b` 项目引用构建。
- 方言包 `export * from "@zerodep-dev/query-core"`，所以一个 import 就能拿到核心与方言的全部函数，相当于 Java 的门面静态导入。
- 测试放在私有包 `typescript/test`，相当于 Java 的共享 test-jar。

**实体**

- 使用标准装饰器：`@table`、`@column(name, type?)`、`@mapFrom(Entity, "key")`、`@ignore`。
- 元数据存放在 `Symbol.metadata` 上，沿继承链合并。Node 24 还没有这个符号，由核心包补上。
- 旧版 `experimentalDecorators` 是非标准语义，不采用。

**值类型**

- TS 没有运行时类型，所以列的类型由 `@column` 的第二个参数（`SqlType<T>`）声明，默认为 `text`。装饰器上下文会检查 `T` 与字段的声明类型是否一致（字段可以带 `| null`）。
- 内置类型：

  | 类型 | 值 |
  | --- | --- |
  | `text` | `string` |
  | `integer`、`double` | `number` |
  | `bigint` | `bigint` |
  | `decimal` | 精确的十进制字符串 |
  | `boolean` | `boolean` |
  | `date` | `'YYYY-MM-DD'` |
  | `timestamp` | `'YYYY-MM-DD HH:MM:SS[.ffffff]'` |
  | `bytes` | `Uint8Array` |

- 日期和精确小数用字符串表示，不经过 `Date`，因此不受进程时区影响。
- 转换器写作 `converted(storage, { toDatabase, fromDatabase })`，作用相当于 Java 的组合注解。

**作用域与表达式**

- 写法是 `from(Lips).leftJoin(Makt, (l, mk) => ...)`。回调参数是 `Ref<E>`，实体的每个字段对应一个 `Expr`，用 `l.material` 这样的属性访问代替 Java 的 getter 引用。
- LEFT JOIN 之后，该位置的参数变成 `NullableRef<E>`，即按元组位置跟踪可空性。表数不设上限。
- 同类型运算是实例方法，规则同第 4 条。比较值的参数用重载，不用 `value | Expr` 联合类型（联合类型会破坏推断，已实测）。

**投影**

- 对象投影 `select((l, mk) => ({ part: …, stock: { qty: sum(…) } }))` 同时承担 Java 的函数式投影和按名投影：
  - 对象的键就是列标签；
  - 嵌套对象得到嵌套的结果类型；
  - 扁平的对象投影可以直接作为 CTE 或派生表，引用它的 `Ref` 类型由行类型推出。
- 其余投影：
  - `selectValue`：单列；
  - `selectAs(VO)`：按同名字段和 `@mapFrom` 自动取列。
- 在 JS 端计算用 `query.map(fn)`。`mapTo(VO)` 在编译期检查行类型是否覆盖 VO 的字段。
- 按位置读列，不依赖数据库返回的标签大小写。

**执行**

- 方言包把各自的驱动包装成 `Driver`：`@sap/hana-client`、`pg`、`mysql2`。方言包只引用驱动的类型，驱动由使用方安装。
- 执行方法都返回 Promise；`stream` 返回 AsyncIterable，在一个游标上逐行读取。
- 驱动把 DECIMAL、BIGINT 和日期时间都读成字符串，再由列的类型解码。

**两端一致**

- 金样例放在 `spec/render/<场景>.<方言>.sql`，Java 与 TS 的方言测试都读取它，保证两端渲染出完全相同的 SQL 和参数。

### 10. SELECT 列表与分组表达式里的常量写成字面量

- SELECT 列表中的常量一律渲染为字面量。
- 带 GROUP BY 或 DISTINCT 的 SELECT，GROUP BY、HAVING、ORDER BY 中的常量也渲染为字面量。
- WHERE 和 ON 中的值仍然绑定为参数。
- 字面量由方言转义：文本中的引号加倍，MySQL 另外转义反斜杠，HANA 的非 ASCII 文本加 `N` 前缀。字节等写不成字面量的值仍然绑定为参数。

理由（2026-10-21 在三个库上实测）：

- 三个库都拒绝 `SELECT SUBSTRING(x, ?, ?) … GROUP BY SUBSTRING(x, ?, ?)`：两个占位符永远不算同一个表达式。写成字面量后三个库都能执行。按序号分组在 HANA 上也不行。
- HANA 推断不出 SELECT 列表里参数的类型：CTE 中的 `COALESCE(x, ?)` 交给外层 `SUM` 时会报错 [266]。
- PostgreSQL 在参数类型未知时，可能把 `SUBSTRING(x, $1, $2)` 解析成正则形式的重载。

代价：SELECT 列表里随请求变化的值会改变语句文本，影响数据库的语句缓存。需要绑定的值应写在 WHERE 中。

### 11. 常量行（码表）

HANA 不支持 `VALUES` 表构造，所以 `values(...)` 把每行渲染成一个字面量 SELECT，用 UNION ALL 连接，HANA 上加 `FROM DUMMY`。常量行可以作为 CTE 或派生表，列类型取自形状类（Java）或值本身（TS，可以另外指定）。

### 12. SQL 级列转换只管读取，写在字段上

- 字段上的 `@ReadAs("NULLIF({0}, '')")`（TS 为 `@readAs(...)`）规定这一列怎样读，`{0}` 代表带别名的列，做法同 Hibernate 的 `@ColumnTransformer(read = ...)`。
- 转换写进 SQL，列出现的每个位置都套上模板：SELECT、WHERE、GROUP BY、ORDER BY、连接条件、聚合参数。比较值仍然绑定为参数。
- 内置两个组合注解：`@BlankAsNull`（`NULLIF({0}, '')`）和 `@Trimmed`（`TRIM({0})`）。Java 的 `@ReadAs` 可以作元注解，组合出自己的注解；TS 的 `readAs(...)` 返回的装饰器可以赋给常量复用。
- 一个字段最多一个读取模板，模板必须包含 `{0}`，否则在读取元数据时报错。
- 需要原值时，在同一个实体上再声明一个映射到同一列的字段。
- 只做读取：本库不做写入，参数一侧的包装（如加密）以后与写入一起评估。

理由：

- 真实查询里 `NULLIF(col, '')` 出现了十几次，分散在 SELECT、分组和条件里，逐处手写既啰嗦又容易漏。
- 转换器（`Converter`、`converted`）只在 Java/TS 端改值，分组、聚合和条件看到的仍是原值；空串和 NULL 在 `COUNT(DISTINCT …)` 里结果不同，所以这类转换必须进 SQL。

代价：条件写在转换后的列上时，数据库可能用不上这一列的索引；需要命中索引时，把条件写在原值字段上。

### 13. ROLLUP 进核心包，CUBE 与 GROUPING SETS 留在方言包

- 核心包：`groupByRollup(keys)` 与 `grouping(key)`。HANA 和 PostgreSQL 渲染 `GROUP BY ROLLUP(a, b)`，MySQL 渲染 `GROUP BY a, b WITH ROLLUP`，三库的结果相同；`GROUPING()` 三库都支持，也都允许出现在 ORDER BY 中。
- `groupByRollup` 一次列出全部分组键，不能与 `groupBy` 混用：MySQL 的 `WITH ROLLUP` 作用于整个 GROUP BY 列表，`GROUP BY a, ROLLUP(b)` 这种部分汇总在 MySQL 上写不出来。
- `cube(...)` 与 `groupingSets(...)` 只在 HANA 和 PostgreSQL 包里，作为 `groupBy` 的键使用；MySQL 不支持，在别的方言上渲染时报错。
- 汇总行里被汇总掉的列读出 NULL，用 `grouping(col)`（汇总行为 1）区分汇总行和本身为 NULL 的值，也用它把小计、总计排在明细之后。

## 验证证据

**临时探针**（2026-10-21，JBR 17.0.14、Lombok 1.18.46、TS 7.0.2），结论已体现在上面的决定里：

- SerializedLambda 解析：未缓存约 620 ns，缓存后约 22 ns。
- 静态多参数泛型函数会推断成公共父类型；实例方法可以拦住类型不符。
- TS 装饰器能检查字段类型；TS 能按位置跟踪 LEFT JOIN 的可空性。

**第一期 Java 的测试**（中文命名），本机三个开发库上全部通过：

| 模块 | 测试 | 内容 |
| --- | --- | --- |
| query-core | 72 | 渲染、投影、映射计划、元数据、原生 SQL、缓存；`CompileTimeSafetyTest` 用 javac 验证 11 种错误写法不能编译 |
| query-hana | 51 | 20 个实库场景（HANA 2.00.088）、4 个方言渲染、27 个金样例 |
| query-postgres | 54 | 24 个实库场景（PostgreSQL 17）、4 个方言渲染、26 个金样例 |
| query-mysql | 49 | 19 个实库场景（MySQL 8.4）、7 个方言渲染、23 个金样例 |

**第二期 TypeScript 的测试**（TS 7.0.2、Node 24，`node:test`），本机三个开发库上全部通过：

| 内容 | 测试 |
| --- | --- |
| 渲染（与 Java 的 `RenderTest` 逐条对应，SQL 逐字相同） | 25 |
| 金样例：三个方言共 73 个用例，再加 3 个"每个样例都有查询"的检查 | 76 |
| 投影与映射、元数据、值类型、执行（原生 SQL、缓存、错误、监听）、方言 | 47 |
| 编译期检查：`@ts-expect-error` 验证 16 种错误写法不能编译 | 1 |
| 实库场景：HANA 20、PostgreSQL 22、MySQL 19 | 61 |

**两端一致**：`spec/render/` 下的 73 个金样例覆盖了 CTE 提升、全局过滤、UNION、自连接、分页、各类条件、字面量、函数与类型转换、聚合、窗口、子查询、常量行、文本函数、分组表达式中的常量、列转换、ROLLUP、UNION 的分组，以及三个方言的专属函数（含 HANA 与 PostgreSQL 的 CUBE、GROUPING SETS）。Java 与 TS 的测试都读取这些文件，渲染结果（SQL 与参数）必须完全相同。

三个方言共用一组实库场景（当时是 Java 的 `LiveScenarios`、TS 的 `live/live.ts`；0013 起按主题拆进 `java/query-it` 和 TS 的 `test/src/live`）：

- CTE 加 EXISTS 加 LEFT JOIN 的汇总；
- 全局过滤与关闭过滤；
- 泛型模板生成的 UNION；
- 窗口函数取最新行；
- 自动投影与 `@MapFrom`；
- SAP 日期转换器的绑定与读取；
- 嵌套投影；
- 分页、流式读取、原生 SQL 的集合参数、缓存命中；
- 在 Europe/Berlin、Pacific/Apia、Asia/Shanghai 三个时区下，DATE 与 TIMESTAMP 的读写都不变（TS 通过切换 `process.env.TZ` 验证）；
- 常量行与带常量的分组（分桶汇总）、文本函数、取负、多项求和、按子串分组；
- 列转换：`@Trimmed`、`@BlankAsNull` 字段与同一列的原值字段并存，分组、`COUNT(DISTINCT …)` 与 `IS NULL` 都按转换后的值；
- ROLLUP 的小计与总计，按 `GROUPING()` 排序；HANA 与 PostgreSQL 另有 GROUPING SETS 与 CUBE；
- `a UNION ALL (b UNION c)` 保留 a 与 c 共有的行（不加括号时会被去重）。

PostgreSQL 另有两个流式读取的实库测试：

- 自动提交的连接上，fetch size 为 1 时能先读到前两行，第三行除以零的错误在取到那一批时才出现，证明按游标逐批读取；去掉方言钩子后，错误在执行时就出现，测试失败。连接归还前已恢复自动提交。
- 外层事务中的流式读取前后，`txid_current()` 不变，外层事务没有被提交或回滚。

原生 SQL 的方言引用在实库上执行：PostgreSQL 的 `$$:x$$`、`$tag$…$tag$`，MySQL 的 `'it\'s :x'` 与反引号别名里的冒号都原样保留。

实库还发现并修复了一个 bug：`db.one()` 曾经覆盖用户自己设置的 LIMIT。

TS 驱动的实测（2026-10-21，`@sap/hana-client` 2.30.27、`pg` 8.23.1、`mysql2` 3.24.6）：

- 三个驱动按我们的配置都把 DECIMAL、超出安全范围的 BIGINT、DATE 和 TIMESTAMP 读成字符串；
- PostgreSQL 的 `DECLARE CURSOR` 可以带绑定参数，所以流式读取用服务端游标实现；
- 金样例顺带统一了 Java 的 TIMESTAMP 字面量：秒数总是写出（此前 `02:30:00` 会写成 `02:30`）。

**真实查询对照**（2026-10-21）：

- 从内部 ERP 分析大屏选了三条查询：按期间汇总的收入、带分桶的应收账龄、按类别的报废与库存。它们覆盖了码表 CTE、十六个期间列求和、贷项凭证自连接、HANA 正则与安全日期转换、`GROUP BY` 中的 CASE、窗口函数取最新供应商，以及可选的 EXISTS 过滤。
- 在本机 HANA 上建合成数据，原 SQL、本库的 Java 改写、TS 改写三者对比，各 21 组参数的结果逐行一致。
- 对照暴露并已补上的缺口：
  - 常量行（第 11 条）；
  - `substring`、`left`、`right`、`replace`、`negate`、接收列表的 `plus`；
  - HANA 的 `likeRegexpr` 与 `toNvarchar`；
  - 第 10 条的两类渲染问题。
- 列转换（第 12 条）完成后，改写把八处手写的 `nullif("")` 换成七个 `@BlankAsNull` 字段，Java 与 TS 再次与原 SQL 逐行一致（各 21 组）。
- 原查询里拼接 SQL 片段（`{{...}}`、`%s`）和 `(:flag = FALSE OR …)` 的写法，改写后分别是返回 CTE 或条件的普通函数，以及 `eqIfPresent`、`ifPresent`。按条件拼接的 JOIN 改写成可选的 EXISTS。
- 改写代码含业务常量，不放进仓库。

HANA 2.00.088 的语法边界（用 IDEA 的 SQL 工具实测）：

- **可用**：`DATE` / `TIMESTAMP` 字面量、`N'…'`、`WITH HINT`、带排序的 `STRING_AGG`、`NULLS FIRST/LAST`、ROWS 框架、`ROLLUP`、`CUBE`、`GROUPING SETS`、`GROUPING()`；
- **不可用**：`VALUES` 表构造、`QUALIFY`、`GROUP BY` 别名、行值比较、`IS DISTINCT FROM`、`LATERAL`。

## 代价与风险

- SerializedLambda 依赖私有的 `writeReplace`。classpath 上没有问题；JPMS 命名模块需要 `opens`；GraalVM 需要额外配置。
- 函数式投影每行都会重跑一次 lambda；lambda 里不能有副作用，也不能按行值有条件地读列。重放时按表达式的结构核对（列、函数名、参数、常量值、CAST 目标），CASE 的条件是不透明的渲染函数，只核对个数。
- 作用域最多 8 张表，更多时用 CTE 拆开。
- getter 引用必须可序列化，javac 为每个类生成的 `$deserializeLambda$` 方法里，每个不同的方法引用占一个分支。一个类引用约 600 个不同的 getter 时会超过 64KB 方法上限，编译报 "code too large"（0003 的 M4 实测）；把查询分到多个类即可。
- 方言专属函数在渲染时才报错，由各方言包的"在别的方言上渲染应当失败"测试兜底。
- SELECT 列表里的常量写成字面量（第 10 条），随请求变化的值放进 SELECT 列表会让语句文本变化。
- Java 不跟踪可空性，结果属性要用包装类型。
- Java 的 MySQL 流式读取默认会缓冲全部结果，需要逐行读取时按第 8 条配置 fetch size。
- TS：
  - DTO 靠实例化取得字段列表，需要无参构造器，字段要按 ES2022 类字段声明；
  - `Symbol.metadata` 由核心包补上，实体模块必须在导入本库之后定义（导入方言包即可满足）；
  - decimal 与文本在类型上都是 `string`，`sum` 等函数对列类型的检查只能在构造查询时进行；
  - PostgreSQL 与 MySQL 的流式读取需要连接池，传入单个连接时会先读完全部行；
  - MySQL 的流式读取走文本协议，参数由 mysql2 转义后写进语句。

## 分期

1. **第一期（已完成）**：Java 的 core、hana、postgres、mysql 四个模块，单元测试与三库实库场景，GitHub Actions 并行运行。
2. **第二期（已完成）**：TypeScript 的 core、hana、postgres、mysql，单元、编译期与三库实库测试；`spec/render/` 金样例由两端测试共同读取。
3. **第三期**：
   - 真实查询对照与补齐的能力（已完成，见验证证据）；
   - Spring 适配（已完成）：`query-spring` 只有 `SpringConnections` 一个类，spring-jdbc 为 `provided`；H2 上的事务测试在 Spring 5.3、6.2、7.0 下都通过；
   - 分组集合（已完成，第 13 条）与 SQL 级列转换（已完成，第 12 条）；
   - 其余事项按 [0003](0003-road-to-1.0.md) 重新取舍：一对多改为对扁平结果分组，按请求对象生成条件不做，HANA 计算视图、表函数与 JSON 延后。
4. **之后**：写入与 DuckDB（见 0003）。

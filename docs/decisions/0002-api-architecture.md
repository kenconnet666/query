# 0002 API 与整体架构

- 状态：已采纳（2026-10-21）；Java 第一期已实现并通过三库实库验证
- 修订 0001：
  - TypeScript 改以 7 为唯一基准；
  - 首批方言为 HANA、PostgreSQL、MySQL，三者同步推进；
  - DuckDB 延后。
- 用法见 [API 使用说明](../api.md)，场景覆盖计划见[场景对照](../scenarios.md)

## 背景

0001 之后，用临时探针比较了多条 API 路线，并确定了取舍顺序：

1. 简洁、灵活、功能强大、方便用户扩展；
2. 类型安全尽量做到，但不能牺牲第 1 条；
3. 性能不是首要目标，但元数据、映射和渲染必须缓存或惰性计算。

同时明确了以下几点：

- 表和字段可以用注解，可以要求用户使用 Lombok；TypeScript 可以用装饰器。
- 结果要能方便地映射到不同的 DTO、VO 或临时类。
- 由共同的核心包加各方言包组成，用户必须选定方言包并创建实例，库不抹平数据库之间的差异。
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

### 9. TypeScript（第二期）

- 使用标准装饰器（TS 7 不需要编译开关），装饰器上下文检查转换器类型和 `@mapFrom` 的键。
- 回调作用域按元组位置跟踪 LEFT JOIN 的可空性。
- 嵌套投影得到嵌套的结果类型；`selectAs(VO)` 按同名字段自动取列。
- 已有探针证据，见下文。旧版 `experimentalDecorators` 是非标准语义，不采用。

## 验证证据

**临时探针**（2026-10-21，JBR 17.0.14、Lombok 1.18.46、TS 7.0.2），结论已体现在上面的决定里：

- SerializedLambda 解析：未缓存约 620 ns，缓存后约 22 ns。
- 静态多参数泛型函数会推断成公共父类型；实例方法可以拦住类型不符。
- TS 装饰器能检查字段类型；TS 能按位置跟踪 LEFT JOIN 的可空性。

**第一期实现的测试**（中文命名），本机三个开发库上全部通过：

| 模块 | 测试 | 内容 |
| --- | --- | --- |
| query-core | 65 | 渲染、投影、映射计划、元数据、原生 SQL、缓存；`CompileTimeSafetyTest` 用 javac 验证 11 种错误写法不能编译 |
| query-hana | 18 | 14 个实库场景（HANA 2.00.088）加方言渲染 |
| query-postgres | 19 | 15 个实库场景（PostgreSQL 17）加方言渲染 |
| query-mysql | 20 | 13 个实库场景（MySQL 8.4）加方言渲染 |

三个方言共用 `LiveScenarios` 这一组场景：

- CTE 加 EXISTS 加 LEFT JOIN 的汇总；
- 全局过滤与关闭过滤；
- 泛型模板生成的 UNION；
- 窗口函数取最新行；
- 自动投影与 `@MapFrom`；
- SAP 日期转换器的绑定与读取；
- 嵌套投影；
- 分页、流式读取、原生 SQL 的集合参数、缓存命中；
- 在 Europe/Berlin、Pacific/Apia、Asia/Shanghai 三个时区下，DATE 与 TIMESTAMP 的读写都不变。

实库还发现并修复了一个 bug：`db.one()` 曾经覆盖用户自己设置的 LIMIT。

HANA 2.00.088 的语法边界（用 IDEA 的 SQL 工具实测）：

- **可用**：`DATE` / `TIMESTAMP` 字面量、`N'…'`、`WITH HINT`、带排序的 `STRING_AGG`、`NULLS FIRST/LAST`、ROWS 框架、`GROUPING SETS`；
- **不可用**：`VALUES` 表构造、`QUALIFY`、`GROUP BY` 别名、行值比较、`IS DISTINCT FROM`、`LATERAL`。

## 代价与风险

- SerializedLambda 依赖私有的 `writeReplace`。classpath 上没有问题；JPMS 命名模块需要 `opens`；GraalVM 需要额外配置。
- 函数式投影每行都会重跑一次 lambda；lambda 里不能有副作用，也不能按行值有条件地读列（已有检测）。
- 作用域最多 8 张表，更多时用 CTE 拆开。
- 方言专属函数在渲染时才报错，由各方言包的"在别的方言上渲染应当失败"测试兜底。
- Java 不跟踪可空性，结果属性要用包装类型。

## 分期

1. **第一期（已完成）**：Java 的 core、hana、postgres、mysql 四个模块，单元测试与三库实库场景，GitHub Actions 并行运行。
2. **第二期**：TypeScript 的 core、hana、postgres、mysql；`spec/` 金样例由两端测试共同读取。
3. **第三期**：
   - Spring 适配；
   - 方言扩展：分组集合、HANA 计算视图与表函数、JSON；
   - SQL 级列转换；一对多的批量组装；
   - 根据请求对象注解生成条件。
4. **之后**：写入与 DuckDB。

# 0014 按逻辑 SQL 类型分的列与表达式、语句式条件

- 状态：已采纳（2026-10-25）。Java 已实施（分支 `feat/typed-expressions`）；TypeScript 的值类型与写法见第 7 节，待实施。
- 起因：
  - 用户决定把 `Col<T>` 改成按类型的具体接口，让文本、数值、日期上的写法可以直接点出来，并删除因此变得多余的写法；
  - 用户提出条件写成一条条语句，不必用 `.and(...)` 链起来，以避开"链到一半返回值是什么类型"的问题；
  - 其他写法也可以适当改进；TS 引入真正的小数、日期类型。
- 决定的过程：先按 Java 类型命名（`ColString<String>`），讨论后改成**按逻辑 SQL 类型命名**（`ColText<String>`、`ColDecimal<BigDecimal>`），泛型参数是 Java 类型；只有个别库才有的物理类型（无符号整数、NVARCHAR 与 VARCHAR）不进接口，放注解。其余按调研时的推荐：语句式条件（下文第 4 节）、数值按"宽"的类型提升、不要单独的聚合类、方言能力走视图。
- 时机：2.0.0 还没有发布，公开 API 现在改，不需要兼容期（0006 第 7 条的先例）。
- 修订：0002 第 4 条（只给"同类型的多参数运算"用实例方法）、0006 的"列引用用 `Col<T>`"、0005 的条件写法、0008 的 `@Column(lob = true)`。

## 参考做法

看的版本：Querydsl 5.1.0、Jimmer 0.12.4、jOOQ 3.22（快照）、EasyQuery 3.2.17、Exposed 1.5.0、Drizzle 0.45、Kysely 0.28；前四个读源码，Exposed 读 GitHub 源码，TS 两个读文档。

| 库 | 类型 | 怎样选 | 结果怎样保住类型 | 日期加减 | SET 的目标 |
| --- | --- | --- | --- | --- | --- |
| Querydsl | 数值、文本、布尔、日期、日期时间、时间、枚举、可比较、兜底 | 类别表，可逐字段覆盖 | 每个子类重写 `coalesce`、`min`、`max` 等 | 静态函数 `SQLExpressions.addDays` | 只能是 `Path` |
| Jimmer | 文本、数值、时间、日期、可比较、兜底 | APT 按 Java 类型 | 每类重写 `coalesce` | 实例方法 `plus(n, 单位)` | 属性 |
| jOOQ | 只有一个 `Field<T>`，方法对所有 T 开放 | 无 | 天然保住 | 静态 `localDateAdd` | 任意 `Field` |
| EasyQuery | 文本、数值、日期时间（含日期）、布尔、JSON、Any 兜底 | APT 按精确类名查表 | 共享基接口带"结果类型"泛型参数 | 实例方法 `plusDays` | 只能是列 |
| Exposed | 一个 `Column<T>`，函数是带上界的扩展函数 | Kotlin 类型 | 扩展函数的泛型 | 无 | 只能是列 |
| Drizzle、Kysely | 自由函数 | 结构类型 | 泛型 | 自由函数 | 列名对象 |

借鉴和避开的：

- **jOOQ 有意不分类型**（issue #200）：分类型有代价，所以要保住类型的方法清单要短，并用测试检查每个类型都重写了（Querydsl #2014、#2123 与 Jimmer #1529 都是漏重写）。
- **数值规则**：Querydsl 两边类型不同时 `divide` 实际按 Double 算、声明的却是左边的类型（#2788）；EasyQuery 的 `sum()` 结果类型靠调用方推断；Jimmer `sum().coalesce(0)` 因 Long 和 Integer 不同报错（#1355）。本库的规则见第 2 节。
- **日期不和时间戳合成一类**：EasyQuery 只有一个日期时间类，日期上也能点出 `hour()`。
- **转换器列没人处理**：四个库都按 Java 一侧的类型选，SQL 函数却作用在库里存的值上，日期存成文本时 `plusDays` 会生成错误的 SQL。本库按库里存的类型选（第 1 节）。
- **语句式条件**：EasyQuery 的条件方法返回 `void`，写进当前的"接收处"，OR 用 `o.or(() -> {...})`；本库照这个思路做，但不加 `eq(boolean 条件, 值)` 这类重载（可选条件直接写 `if`），在接收处之外写条件时给出说明（EasyQuery 抛不带说明的异常）。

## Java 语言探针（JBR 17，javac）

| 写法 | 结果 |
| --- | --- |
| `where(l -> { l.delivery().contains("D"); l.quantity().gt(x); })`：参数是返回 void 的函数式接口 | 通过，并且能和条件对象的重载 `where(Object)` 共存 |
| 同时保留 `where(返回 Cond 的 lambda)` 和 `where(语句块)` | 编译报"引用不明确"：两种只能留一种 |
| `r.col(Row::name)` 按 getter 的返回类型选重载 | 通过（只限方法引用） |
| 接口里协变重写 `coalesce`，`l.delivery().coalesce("x").upper()` | 通过 |
| 结果是类型变量的泛型方法放进重载的参数：`x.eq(m.items().max(i -> i.v()))` | **引用不明确**：lambda 没写参数类型时不参与重载判断，javac 排除不了 `eq(T)`。所以关系上的 `sum`、`max` 不返回类型变量（第 2 节） |
| 泛型参数推断 `F extends Expr<T>` 时，`F` 同时要满足 `F <: Integer` | javac 只在两个类的上界无关时报错，接口与 final 类并存不报，这也是上一条的原因 |

## 1. 类型：按逻辑 SQL 类型分，泛型是 Java 类型

| 逻辑类型 | 表达式接口 | 列接口 | 默认的 Java 类型 | 也存成这一类的 Java 类型 |
| --- | --- | --- | --- | --- |
| 文本 | `ExprText` | `ColText`；不限长度 `ColClob` | `String` | `Character`、按名字存的枚举 |
| 短整数 | `ExprSmallint` | `ColSmallint` | `Short` | `Byte` |
| 整数、长整数 | `ExprInteger`、`ExprBigint` | `ColInteger`、`ColBigint` | `Integer`、`Long` | 按编码存的枚举（编码的类型） |
| 定点小数 | `ExprDecimal` | `ColDecimal` | `BigDecimal` | `BigInteger` |
| 浮点数 | `ExprDouble` | `ColDouble` | `Double` | `Float` |
| 布尔值 | `ExprBoolean` | `ColBoolean` | `Boolean` | |
| 日期、时间戳、时间 | `ExprDate`、`ExprTimestamp`、`ExprTime` | `ColDate`、`ColTimestamp`、`ColTime` | `LocalDate`、`LocalDateTime`、`LocalTime` | |
| 带时区的时间戳 | `ExprTimestampTz` | `ColTimestampTz` | `Instant` | |
| 字节串 | `ExprBinary` | `ColBinary`；不限长度 `ColBlob` | `byte[]` | |
| 其他（JSON、数组、UUID） | `Expr<T>` | `Col<T>` | | |

- **类名表示库里怎么存，泛型参数表示 Java 拿到什么**，正对应 `Converter<J, D>` 的 D 与 J：SAP 以 'yyyyMMdd' 存的日期是 `ColText<LocalDate>`，比较传 `LocalDate`（经转换器绑定成文本），文本函数作用在库里的文本上（结果是 `ExprText<String>`），日期函数在编译期点不出来。`Metadata` 按属性的存储类型检查声明的接口，对不上时报错并给出应写的接口；`ColClob`、`ColBlob` 只在建表时与 `ColText`、`ColBinary` 不同，代替原来的 `@Column(lob = true)`。
- 逻辑类型与建表的 `ColumnType.Kind`、CAST 的 `CastType.Kind`、TS 的 `TypeKind` 是同一套词，与方言无关，同一个表接口能在各个库上用。只有个别库才有的物理类型不进接口：放进接口会让表接口绑死一个库，做完一次运算方言方法又没了。
- **结构**：`Expr<T>` 改成接口，节点继承 `ExprNode<T>`；`Family<T, F>` 是有自己方法的类型的共同父接口，F 是这一类的表达式接口，列也实现它（`ColText<T>` 是 `Family<T, ExprText<T>>`）；`Col<T>` 是所有列接口的父接口。`ColText<T>` 同时继承 `Col<T>` 与 `ExprText<T>`，所以列能传给收 `ExprText<?>` 的辅助方法，也能当 `Col<?>` 用。
- 方法都是接口的 default 方法，所以每种类型的实现只是一个空类：计算的结果包在外壳（`expr.typed.Shells`）里，外壳经由 `w.render(node)` 渲染，渲染器按节点做的替换（QUALIFY 的窗口函数、groupJoin 的聚合）照样认得出被包的节点；按节点种类判断时先 `Nodes.unwrap`。列的实现在 `table.column.Columns`，按存储类型选。
- 包：`expr`（`Expr`、`ExprNode`、`Family`、条件与节点）、`expr.typed`（各类型的表达式接口与外壳）、`table.column`（列接口与实现）。

## 2. 方法放在哪里、结果是什么类型

规则：**有一个主操作数的运算是它的实例方法；没有主操作数、或几个操作数地位相同的，留作静态函数。** 函数名沿用原来的静态函数名，`addDays(d, 3)` 变成 `d.addDays(3)`。

| 类型 | 实例方法 |
| --- | --- |
| 所有表达式 | 条件 `eq`…`le`、`between`、`range`、`in`、`notIn`、`isNull`、`isNotNull`、`isDistinctFrom`、`isNotDistinctFrom`；结果仍是本类型的 `coalesce`（一到两个后备值）、`nullif`、`max`、`min`、`filter`、`over`；`count`、`countDistinct`；`as`、`asc`、`desc`、`cast` |
| 文本 | `like`、`notLike`、`contains`、`startsWith`、`endsWith`、`upper`、`lower`、`trim`、`substring`、`left`、`right`、`replace`、`locate`、`concat`、`lpad`、`rpad`、`stringAgg` |
| 数值 | `plus`、`minus`、`times`、`divide`（只有小数、浮点数）、`mod`、`negate`、`abs`、`round`、`floor`、`ceil`、`power`、`sqrt`、`sign`；`sum`、`sumDistinct`、`avg`、`avgDistinct`、`stddevSamp`、`stddevPop`、`varSamp`、`varPop` |
| 日期 | `year`、`month`、`day`、`addDays`、`addMonths`、`addYears`、`monthStart`、`daysBetween` |
| 时间戳 | `year`、`month`、`day`、`hour`、`minute`、`second`、`addHours`、`addMinutes`、`addSeconds`、`secondsBetween` |

**结果类型的规则**：

- 值不变的运算（比较、`coalesce`、`max`、`min`、`filter`、`over`、`in`）保住 T；算出新值的运算回到库里的类型（`upper` 是 `ExprText<String>`，`sum` 是本类型的默认 Java 类型）。计算结果的 SqlType：Java 类型本来就是存储类型时沿用表达式自己的（文本到文本的转换器照样生效），否则用存储类型。
- 加减乘按"宽"的提升：浮点 > 小数 > 长整数 > 整数（短整数相加按整数，与 Java 一致）。每个运算对常量 `int`、`long`、`double`、`BigDecimal` 和每种数值表达式各有一个重载，由生成器产出。`a.plus(b).plus(c)` 并成一个括号 `(a + b + c)`，与原来的 `plus(a, b, c)` 渲染相同；减法和除法不并。
- 整数没有 `divide`：整数相除各库有的取整、有的不取整，先 `cast`。
- `avg` 是 `ExprDecimal`，统计聚合与 `sqrt`、`power` 是 `ExprDouble`，`sign`、`locate`、`year` 等是 `ExprInteger`，`count` 是 `ExprBigint`，`secondsBetween` 是 `ExprBigint`。
- 不要单独的聚合类：`filter(() -> ...)`、`over(...)` 在每个表达式上，接在非聚合上时立即报错。少一半的类型，误用很少见且第一次运行就暴露。
- **泛型函数借 `Family<T, F>` 保住类型**：`caseWhen(() -> ..., l.quantity()).otherwise(...)` 是 `ExprDecimal`，`lag(l.quantity(), 1).over(...)`、`greatest(a, b)`、`percentileDisc(p, e)` 与参数同一类型；`val`、`lit`、`caseWhen`、`caseOf(...).when(...)` 的常量参数按值的类给出类型（`val("x")` 是 `ExprText<String>`），由生成器产出各类型的重载。
- **关系上的 `sum`、`max`、`min` 不返回类型变量**：值来自没写参数类型的 lambda，返回类型若是 `F`，`x.gt(m.items().sum(i -> i.amount()))` 在 `gt(T)` 与 `gt(Expr<T>)` 之间引用不明确（探针）。所以 `sum` 是 `ExprNumber<N>`、`max`/`min` 是 `Expr<T>`，运行时的对象仍是具体类型，要接着用某种类型的方法时包一层 `ExprDecimal.of(...)`。
- **只有 `Expr<T>` 的地方**（`scalar(q)`、`sql(...)`、`call(...)`）用 `ExprText.of(e)` 等包一层，不提供强转；包一层不改 SQL。
- **静态函数**只留没有主操作数的：条件块 `any`、`all`、`not`、`exists`、`notExists`、`bool`、`sqlCond`；`val`、`lit`、`nullOf`；`count()`、`grouping`；窗口函数；`caseWhen`、`caseOf`；`scalar`、`anyOf`、`allOf`；`currentDate`、`currentTimestamp`；`keys`、`orders`、`cols`；`sql`、`sqlTable`、`identifier`、`call`。

## 3. 继承与转型

- 向上转型不用写：每种列都是 `Col<T>` 与 `Expr<T>`，每种表达式都是 `Expr<T>`。
- 向下用显式的 `ExprText.of(e)` 等，不强转：节点本身不是 `ExprText`。
- 通配符：辅助方法收"任意文本"写 `ExprText<?>`，收"任意数值"写 `ExprNumber<?>`；`mod`、`power`、`divide` 的参数收 `Expr<? extends Number>`。
- 不用自限定泛型：协变重写的样板交给生成器，每个类型接口重写 `coalesce`、`nullif`、`max`、`min`、`filter`、`over`、`retype`，`TypedExprTest` 用反射检查都重写了。

## 4. 语句式条件

```java
from(Lips.class).where(l -> {
    l.delivery().contains("D");                      // 条件方法返回 void，写进最内层的条件块
    l.quantity().gt(new BigDecimal("2"));
    if (maker != null) l.mara().manufacturer().eq(maker);   // 可选条件就是 if
    any(() -> {                                      // OR：块里每行一个选项
        l.unit().eq("EA");
        all(() -> { l.unit().eq("KG"); l.quantity().lt(BigDecimal.TEN); });
    });
})
.select(l -> cols(l.delivery(), bool(() -> l.quantity().gt(new BigDecimal("2"))).as("big")))
```

- 接收处（`expr.Conditions`，线程局部的栈）：`where`、`having`、`qualify`、连接的 `on`、关系的 `one`/`many`/`where`/`any`/`none`、`any`、`all`、`not`、`bool`、`caseWhen`、聚合的 `filter`、全局过滤器、条件对象的 `@Where(using = ...)`。块里的条件用 AND 连起来，`any` 的块里每个条件是一个 OR 选项。
- 在条件块外写条件立即报错，并提示用 `bool(() -> ...)`；块里的投影、分组、排序、关系的 `list`/`first` 等 lambda 压一个"不收条件"的标记，在那里写条件也报错，不会悄悄并进外层的块。
- UPDATE 的赋值同样写成块：`set(t -> { t.title().to("x"); t.qty().to(t.qty().plus(1)); })`（`write.Assignments`）；`to(...)` 只在列上，写在赋值块外报错。
- 复用条件写成返回 `void` 的方法。条件不能存进变量、不能当返回值传递；`sqlCond(...)` 写进块后返回它本身，`.on(方言类, 模板)` 改的就是块里的那个条件。
- 删除的写法：`Cond.and`/`or`/`not`、`onlyIf`、`ifPresent`、条件参数的 `all(Cond...)` 和 `any(Cond...)`；`Cond` 不再出现在使用方的代码里，留给方言包和自定义条件节点。
- 曾考虑过的另两种：条件既是值又自动登记、被组合时撤下（漏了"撤下"就会同时进 AND 和 OR，SQL 不报错、结果悄悄出错）；保持值式只删 `.and`（顶层要包 `all(...)`，可选条件仍要 `onlyIf`）。

## 5. 不同数据库的差异

- **存储与语义不同**（HANA 的 TIMESTAMP 精确到 100 纳秒、MySQL 的 TIMESTAMP 按会话时区换算、GREATEST 遇 NULL 的行为）属于渲染与读写，继续由方言处理，不进类型。
- **可用的函数不同**：方言门面给出视图，表接口保持中立，视图的结果回到核心的类型接口：

  ```java
  hana(l.createdOn()).toVarchar("YYYYMMDD").upper()      // ExprText，接着点
  hana(l.delivery()).toDate("YYYYMMDD").addDays(1)        // 文本解析成日期
  postgres(l.unit()).eqAny(units)                         // 条件写进当前的块
  mysql(e.onDay()).dateFormat("%Y%m")
  sqlite(l.text()).glob("A*")
  ```

  视图按参数的静态类型选重载（`hana(ExprText)` 有 `toDate`、`length`，`hana(ExprNumber)` 有 `median`，`hana(Expr)` 有 `toVarchar`、`toNvarchar`）。没有主操作数的（`hint`、`distinctOn`、`fromView`、全文检索、数组）照旧是方言门面的静态函数。

## 6. 其他写法的整理

- 删掉：约 55 个静态的类型函数、`plus(List)`（链式 `plus` 渲染相同）、`Cond.and`/`or`、`onlyIf`、`ifPresent`、`@Column(lob)`。
- `coalesce` 的多个后备值改成一个或两个的固定重载：接口的 default 方法不能用 `@SafeVarargs`，泛型可变参数会在每个调用处报警告。
- `cols(Cols first, Expr<?>... more)` 保留：`l.columns()` 加额外列的写法有用处（`Many.first` 也用它）。
- `CastType<T, F>` 带上结果的类型接口，`cast(CastType.decimal(15, 3))` 是 `ExprDecimal`。
- `query:pull` 生成的列方法按读回的类型写列接口（`ColClob`、`ColBlob` 代替 `lob = true`）。

## 7. TypeScript（待实施）

TS 的字段类型就是值的类型，所以要先让小数和日期不再是 `string`：用 `decimal.js` 的 `Decimal`、`@internationalized/date` 的 `CalendarDate` / `CalendarDateTime`（与 vue-tsx-pro 一致），作为核心包的 peerDependencies，前端从接口拿到的值不用再转换；`bigint` 不变。之后按字段的 TS 类型选列接口，写法与 Java 对齐成 `l.unit.contains("E")`、`l.quantity.sum()`、`l.createdOn.addDays(3)`、语句式条件。

转换器列（字段类型是 `CalendarDate`、库里存文本）在 TS 的类型系统里看不到装饰器，计划用字段类型上的标记 `Stored<CalendarDate, string>` 让 `Ref` 映射成文本列，实施前先用小探针验证。

列接口的名字（沿用 Java 的逻辑类型名 `ColText`、`ColDecimal`，还是按 TS 类型叫）待用户确认。

## 8. 验证

- 金样例（`spec/render`）一个都没有变：新写法渲染出的 SQL 与旧写法逐字相同，这是最主要的回归证据。
- Java 各模块的测试与 HANA、PostgreSQL、MySQL、PolarDB、SQLite 五个库的实库测试全部通过；`CompileTimeSafetyTest` 加了文本列点日期函数、以文本存的日期点日期函数、整数相除、给非列赋值、条件当投影几种不能编译的写法；`TypedExprTest` 检查每个类型接口的协变重写、类型提升和写错位置的条件。
- 测试与文档的旧写法由一次性的改写脚本转换后逐个复核，可选条件、`.and` 链和返回 `Cond` 的辅助方法手工改。

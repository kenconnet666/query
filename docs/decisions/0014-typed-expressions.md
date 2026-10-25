# 0014 按类型分家的列与表达式、语句式条件（草案）

- 状态：草案（2026-10-25），待用户确认；只做了盘点、参考调研和 Java 语言探针，没有改代码
- 起因：
  - 用户决定把 `Col<T>` 改成按类型的具体类，让文本、数值、日期上的写法可以直接点出来，并删除因此变得多余的写法；
  - 用户提出条件可以写成一条条语句，不必用 `.and(...)` 链起来，以避开"链到一半返回值是什么类型"的问题；
  - 其他写法也可以适当改进。
- 时机：2.0.0 和 2.1.0 都还没有发布，公开 API 现在改，不需要兼容期（0006 第 7 条的先例）。
- 修订：0002 第 4 条（只给"同类型的多参数运算"用实例方法）、0006 的"列引用用 `Col<T>`"、0005 的条件写法。

## 现状

### 列与表达式

- 每一列都是 `Col<T>`，它继承 `Expr<T>`。`Expr<T>` 上只有与类型无关的实例方法：
  - 比较：`eq`、`ne`、`gt`、`ge`、`lt`、`le`；
  - 区间与集合：`between`、`range`、`in`、`notIn`；
  - 空值：`isNull`、`isNotNull`、`isDistinctFrom`；
  - 同类型函数：`coalesce`、`nullif`；
  - 其他：`as`、`asc`、`desc`、`cast`。
- 按类型才有的运算都是 `Sql` 的静态函数，参数写死类型，用错就编译失败：
  - 文本 16 个（含 `substring`、`left`、`right`、`locate` 的 int 与表达式重载共 22 个签名）；
  - 数值 13 个；
  - 日期与时间 15 个；
  - 聚合与统计 14 个；
  - 窗口 11 个。
- 仓库内（Java 代码与文档）的用法次数：`count` 52、`sum` 39、`plus` 25、`substring` 20、`stringAgg` 11、`minus` 10、`max` 10、`month` 9、`daysBetween` 8，其余多为个位数。
- **结构上的限制**：`Expr<T>` 的子类不只有 `Col`，还有 `Aggregate`、`Case`、`WindowFunction` 的结果、`Nodes` 里的十来种节点、`Many` 的聚合值、`SqlExpr`。如果让每种节点各自去继承具体的类型类，类的数量是"节点种类 × 类型族"。所以具体类型只能做成**外壳**：外壳是 `Expr<T>` 的子类，内部持有一个节点，渲染时交给节点。

### 条件

- `where`、`having`、`qualify`、连接的 `ON`、关系的 `any`/`none`、聚合的 `filter` 都收一个返回 `Cond` 的 lambda，多个条件用 `all(...)` 或 `cond.and(...)` 组合。
- 可选条件有三种写法：作用域的 `skipEmpty()`、`onlyIf(flag, () -> cond)`、`ifPresent(value, v -> cond)`。
- lambda 求值时已经用线程局部状态传递 `skipEmpty` 的开关（`scope/SkipEmpty`），有先例。

### TS

- 表达式的类型由实体字段的 TS 类型推出来（`Ref<E>` 的 `Member<E[K]>`）。文本、小数（精确的十进制字符串）、日期、时间戳在 TS 里**都是 `string`**，按值的类型分不出这四类。

## 参考做法

看的版本：Querydsl 5.1.0、Jimmer 0.12.4、jOOQ 3.22（快照）、EasyQuery 3.2.17、Exposed 1.5.0、Drizzle 0.45、Kysely 0.28；前四个读源码（`orm-references/` 与 `easy-query/`），Exposed 读 GitHub 源码，TS 两个读文档。

| 库 | 族 | 怎样选族 | 结果怎样保住族 | 日期加减 | SET 的目标 |
| --- | --- | --- | --- | --- | --- |
| Querydsl | 数值、文本、布尔、日期、日期时间、时间、枚举、可比较、兜底 | 类别表，可逐字段覆盖 | 每个子类重写 `coalesce`、`min`、`max` 等 | 静态函数 `SQLExpressions.addDays` | 只能是 `Path` |
| Jimmer | 文本、数值、时间、日期、可比较、兜底 | APT 按 Java 类型 | 每族重写 `coalesce` | 实例方法 `plus(n, 单位)` | 属性 |
| jOOQ | 只有一个 `Field<T>`，方法对所有 T 开放 | 无 | 天然保住 | 静态 `localDateAdd` | 任意 `Field` |
| EasyQuery | 文本、数值、日期时间（含日期）、布尔、JSON、Any 兜底 | APT 按精确类名查表 | 共享基接口带"结果类型"泛型参数 | 实例方法 `plusDays` | 只能是列 |
| Exposed | 一个 `Column<T>`，函数是带上界的扩展函数 | Kotlin 类型 | 扩展函数的泛型 | 无 | 只能是列 |
| Drizzle、Kysely | 自由函数 | 结构类型 | 泛型 | 自由函数 | 列名对象 |

可借鉴和要避开的：

- **jOOQ 有意不分族**：issue #200（2012）讨论过 `Field` 的子类，结论是"会让 API 和实现都很笨重"，拒绝。所以分族是有代价的选择，不是公认的更好。
- **漏重写就在运行时出错**：Querydsl #2014、#2123（`coalesce` 的结果少了方法、结果错误）、Jimmer #1529（两个时间戳 `coalesce` 时强转出 `ClassCastException`）。要保住族的方法清单要短，并有测试检查每个族都重写了。
- **数值规则的坑**：Querydsl 两边类型不同时 `divide` 实际按 Double 算、声明的却是左边的类型（#2788）；EasyQuery 的 `sum()` 结果类型由调用方推断，靠不住，只好另加 `sumInt`、`sumLong`；Jimmer `sum().coalesce(0)` 因 Long 和 Integer 不同报错（#1355）。
- **日期不要和时间戳合成一族**：EasyQuery 只有一个日期时间族，日期上也能点出 `hour()`。Jimmer 作者在 #989 说日期类不能再细分是因为它只有 4 类表达式、Java 没有扩展函数。
- **族在 CASE、窗口、标量子查询上丢失**：Querydsl 的窗口结果、EasyQuery 的 CASE 和子查询都退回兜底类型，再用 `asNumber`、`asString`、`asLocalDate` 之类的函数重新定型。
- **转换器列没人处理**：四个库都按 Java 一侧的类型选族，SQL 函数却作用在库里存的值上；日期存成文本时，`plusDays`、`year` 会生成错误的 SQL。
- **语句式条件**：EasyQuery 的条件方法返回 `void`，写进查询自己的"当前接收处"（where、having、set 等）；OR、NOT 用无参的语句块 `o.or(() -> {...})` 隔开；要把条件当值用时（SELECT 里的布尔值、CASE 的条件）用 `valueOf(() -> ...)`、`caseWhen(() -> ...)` 转一次。它把"返回表达式"的旧写法标了弃用。每个比较方法另有 `eq(boolean 条件, 值)` 的重载，API 数量翻倍。在没有接收处的地方写条件，抛不带说明的 `UnsupportedOperationException`。
- **值式条件的做法**：jOOQ `DSL.and(Condition...)`、Querydsl `where(Predicate...)` 跳过 null、Jimmer `where(Predicate...)` 和 `eqIf`、Drizzle `and(...filters)`。调研者的看法：值式的 `Cond` 加"可变参数、忽略空值"能拿到语句式八成的简洁，条件可以返回、存起来、组合，没有可变的作用域状态。

## Java 语言探针（JBR 17，javac）

| 写法 | 结果 |
| --- | --- |
| `where(l -> { l.delivery().contains("D"); l.quantity().gt(x); })`：参数是返回 void 的函数式接口，单个表达式的 lambda 和多行语句块都能写 | 通过，并且能和现有的条件对象重载 `where(Object)` 共存 |
| 同时保留 `where(返回 Cond 的 lambda)` 和 `where(语句块)` | **编译报"引用不明确"**：Java 对隐式类型的 lambda 不能按返回值区分重载（0005 也记过）。两种只能留一种 |
| `r.col(Row::name)` 按 getter 的返回类型选重载，得到 `TextExpr`；`r.col(Row::total)` 得到 `NumExpr<BigDecimal>`；再加一个泛型的 `col(Getter<R, T>)` 兜底也不冲突 | 通过（只限方法引用；写成 `x -> x.name()` 报不明确，本库本来就要求方法引用） |
| `static <E extends Expr<?>> E max(E e)`，`max(l.delivery()).upper()` | 通过：单参数的泛型函数能保住具体类型 |
| 子类协变重写 `coalesce`，`l.delivery().coalesce("x").upper()` | 通过 |

## 方案

### 1. 类型族

按 Java 一侧的类型分族；带转换器、而库里存的类型属于别的族的列，只能用兜底的 `Col<T>`（见表后的说明）：

| 族 | Java 类型 | 表达式 | 列（表接口里写的） | 聚合与窗口的结果 |
| --- | --- | --- | --- | --- |
| 文本 | `String` | `TextExpr` | `TextCol` | `TextAgg` |
| 数值 | `Integer`、`Long`、`BigDecimal`、`Double`，以及少见的 `Short`、`BigInteger`、`Float` | `NumExpr<N>` | `IntCol`、`LongCol`、`DecimalCol`、`DoubleCol`（都继承 `NumCol<N>`；少见类型直接写 `NumCol<Short>`） | `NumAgg<N>` |
| 日期 | `LocalDate` | `DateExpr` | `DateCol` | `DateAgg` |
| 时间戳 | `LocalDateTime` | `DateTimeExpr` | `DateTimeCol` | `DateTimeAgg` |
| 其他 | `Boolean`、枚举、`CodeEnum`、数组、`byte[]`、`Config.type(...)` 注册的自定义类型 | `Expr<T>` | `Col<T>` | `Agg<T>` |

```java
@Table(schema = "ERP", value = "LIPS")
public interface Lips extends Client {
    @Column("VBELN") TextCol delivery();
    @Column("MATNR") TextCol material();
    @Column("ERDAT") @SapDate Col<LocalDate> createdOn();   // 库里是 'yyyyMMdd' 文本：只能比较，不能用日期函数
    @Column(value = "LFIMG", precision = 13, scale = 3) DecimalCol quantity();
    @Column("STATUS") Col<NoteState> state();            // 其他类型照旧用 Col<T>
}
```

- **带转换器的列按库里存的类型约束**：`@SapDate` 把日期存成文本，日期函数作用在文本上会生成错误的 SQL（参考库都没处理）。所以存储类型与 Java 类型不在同一族时，列只能声明成 `Col<T>`：比较、`in`、`between` 照常可用（`yyyyMMdd` 的文本顺序就是日期顺序），日期函数在编译期就点不出来。声明成 `DateCol` 时 `Metadata` 报错，除非转换器声明自己支持 SQL 侧的日期运算。
- **列与表达式的区别**：列类只多两样东西，UPDATE 的 `to(...)`（只有列能被赋值，编译期就拦住 `upper(x).to(...)`）和所属的来源。
- **聚合结果类**只多 `filter(条件)` 和 `over(window)`：`l.quantity().sum().filter(...)` 能写，`l.quantity().filter(...)` 编译不过。条件的写法随第 5 节的选择（值或语句块）。
- **全部由生成器产出**：五个族 × 表达式、列、聚合三种类，加上协变重写的样板，由 `tools/generate-java-scopes.mjs` 一起生成并提交，与 `From1..From16` 同样处理，不手写。

### 2. 方法放在哪里

一条规则：**有一个主操作数的运算是它的实例方法；没有主操作数、或者几个操作数地位相同的，留作静态函数**。

| 族 | 实例方法（现在的静态函数搬过来） |
| --- | --- |
| 所有族（`Expr<T>`） | 现有的比较、区间、空值、`as`、排序、`cast`；`coalesce`、`nullif`、`min()`、`max()`（各族协变重写，返回本族）；`count()`、`countDistinct()`（返回 `NumAgg<Long>`） |
| 文本 | `contains`、`startsWith`、`endsWith`、`like`、`notLike`、`upper`、`lower`、`trim`、`substring`、`left`、`right`、`replace`、`locate`、`concat`、`lpad`、`rpad`、`stringAgg` |
| 数值 | `plus`、`minus`、`times`、`divide`（结果是 `NumExpr<BigDecimal>`）、`negate`、`abs`、`round`、`floor`、`ceil`、`mod`、`power`、`sqrt`、`sign`；`sum`、`sumDistinct`、`avg`、`avgDistinct`、`stddevSamp`、`stddevPop`、`varSamp`、`varPop` |
| 日期 | `year`、`month`、`day`、`addDays`、`addMonths`、`addYears`、`monthStart`、`daysBetween(到哪天)` |
| 时间戳 | `year`、`month`、`day`、`hour`、`minute`、`second`、`addHours`、`addMinutes`、`addSeconds`、`secondsBetween(到何时)` |

函数名沿用现有的名字（`addDays` 等），只是从 `addDays(d, 3)` 变成 `d.addDays(3)`，迁移时少记一套新名字。

留作静态函数的：`all`、`any`、`not`（第 5 节选甲时 `any`、`not` 改收语句块，`all` 删掉）、`exists`、`notExists`、`val`、`lit`、`nullOf`、`caseWhen`、`caseOf`、`count()`（无参数）、`rowNumber`、`rank`、`denseRank`、`percentRank`、`cumeDist`、`ntile`、`currentDate`、`currentTimestamp`、`scalar`、`anyOf`、`allOf`、`keys`、`orders`、`sql`、`sqlCond`、`sqlTable`、`identifier`、`call`。

窗口的取值函数留作静态，但按"单参数泛型保住具体类型"写，`over(...)` 之后回到同一族：

```java
static <E extends Expr<?>> WindowCall<E> lag(E e, int offset)  // lag(l.quantity(), 1).over(w -> ...) 的类型是 NumExpr<BigDecimal>
```

**结果类型的规则**（避开参考库的坑）：

- 数值：`plus`、`minus`、`times` 的结果取左边的类型；`divide` 和 `avg` 返回 `NumExpr<BigDecimal>`（与现在相同）；`sum` 返回本身的类型；`count`、`countDistinct` 返回 `NumExpr<Long>`；`coalesce` 的字面量参数收任意 `Number`，按列的类型转换。
- `val(x)` 按值的类型返回对应的族：`val("x")` 是 `TextExpr`，`val(BigDecimal.ONE)` 是 `NumExpr<BigDecimal>`，`val(LocalDate)` 是 `DateExpr`，其余是 `Expr<T>`。这样 `val("x").upper()` 能写。`lit`、`nullOf` 同理。
- `caseWhen(cond, 结果)` 按第一个结果的类型决定族：结果是值时按值的类型重载（文本、数值、日期、时间戳、其余），是表达式时用单参数的泛型保住族。
- 每个族的方法控制在 30 个左右，冷门的留在 `Sql`，免得补全列表太长。

**写法对照**：

```java
// 现在
.select(l -> cols(upper(l.unit()).as("unit"), sum(l.quantity()).as("total"), stringAgg(l.delivery(), ",", l.delivery().desc()).as("deliveries")))
.select(e -> cols(addDays(e.onDay(), 3).as("due"), year(e.atTime()).as("y")))          // EVENTS 的真正 DATE、TIMESTAMP 列
// 之后
.select(l -> cols(l.unit().upper().as("unit"), l.quantity().sum().as("total"), l.delivery().stringAgg(",", l.delivery().desc()).as("deliveries")))
.select(e -> cols(e.onDay().addDays(3).as("due"), e.atTime().year().as("y")))
```

**方言函数**：方言包不能往核心的类里加实例方法，方言的运算经由方言门面的视图，见第 4 节。

### 3. 继承与转型

- **向上**：每个族都是 `Expr<T>` 的子类，接收任意表达式的地方（`cols`、`groupBy`、`orderBy`、`keys`、`in(子查询)`、原生 SQL 的参数）照旧收 `Expr<?>`，不用转换。
- **向下不用强转**：只拿得到 `Expr<T>` 的地方（标量子查询、原生 SQL 片段 `sql(String.class, ...)`、自定义函数 `call(...)`、还没改的方言函数），用适配函数套一层外壳，类型由参数检查，不会有运行时的 `ClassCastException`：

  ```java
  static TextExpr text(Expr<String> e)
  static <N extends Number> NumExpr<N> num(Expr<N> e)
  static DateExpr date(Expr<LocalDate> e)
  static DateTimeExpr dateTime(Expr<LocalDateTime> e)

  text(scalar(from(Makt.class).where(...).select(t -> t.text()))).upper()
  ```

  不提供 `(TextExpr) e` 式的强转：节点本身不是 `TextExpr`，强转必然失败。
- **通配符**：泛型不协变，`NumExpr<Integer>` 不是 `NumExpr<Number>`。数值运算的参数写成 `NumExpr<? extends Number>`，结果取接收者的类型（与现在的 `plus(Expr<N>, Expr<? extends Number>...)` 规则相同）；`divide`、`avg` 固定返回 `NumExpr<BigDecimal>`。
- **不用自限定泛型**：`Expr<T, S extends Expr<T, S>>` 能让基类方法直接返回本族，但所有签名都要多写一个类型参数（`Expr<String, ?>`），使用方写辅助方法时也躲不开。改用协变重写，样板交给生成器。
- **用测试防止漏重写**：要保住族的方法只有 `coalesce`、`nullif`、`min`、`max` 和聚合的 `filter`、`over`。加一个反射单测，检查每个族都按本族的类型重写了它们（Querydsl #2014、Jimmer #1529 就是漏了）。
- **同类型的多参数运算仍是实例方法**：`static <E extends Expr<?>> E coalesce(E a, E b)` 遇到 `TextExpr` 和 `NumExpr` 会推断成公共父类 `Expr<?>`，悄悄通过编译（0002 第 4 条）。所以只有单参数的泛型函数留作静态。

### 4. 不同数据库的差异

同一个 Java 类型在各库上的差别有两种，处理方法不同：

1. **存储与语义不同**：HANA 的 TIMESTAMP 精确到 100 纳秒、MySQL 的 DATETIME 到微秒；MySQL 的 TIMESTAMP 按会话时区换算、DATETIME 不换算；GREATEST 遇到 NULL 时 PostgreSQL 跳过、HANA 和 MySQL 返回 NULL。这些属于渲染和读写，继续由方言（`Dialect`、`TypeRegistry`、方言的 `SchemaSyntax`）处理，**不进列的类型**。列的族只描述 Java 一侧的值。
2. **可用的方法不同**：HANA 的日期有 `TO_VARCHAR`、文本有 `LENGTH`、`TO_DATE`；MySQL 有 `DATE_FORMAT`；PostgreSQL 的文本有 `ILIKE`、JSONB 操作。三种做法：

| 做法 | 写法 | 问题 |
| --- | --- | --- |
| 甲：列类带上方言 | 表接口里写 `HanaDateCol createdOn()`，它继承 `DateCol`，多出 HANA 的方法 | 表接口绑死一个库，同一张表不能在别的库上用（本库的实库测试、流程引擎都要跨库）；做完核心运算后（`addDays`）结果回到 `DateExpr`，HANA 的方法又没了，要想保住就得"方言 × 族 × 三种类"全部协变重写 |
| 乙：所有类型带一个方言参数 | `DateCol<D extends Dialect>`，表接口也跟着泛型化 | 所有签名多一个类型参数，比自限定泛型还重 |
| 丙：表接口保持中立，方言能力经由方言门面的视图（推荐） | `hana(l.createdOn()).toVarchar("YYYYMMDD")`、`mysql(l.createdOn()).dateFormat("%Y%m")`、`pg(l.title()).ilike("ea%")` | 方言运算要先套一层视图；好处是表接口跨库可用，视图的结果回到核心的族，后面照样点 |

丙的视图按参数的静态类型选重载（探针已验证）：

```java
// Hana 门面
static DateView hana(DateExpr e)     // toVarchar(format)
static TextView hana(TextExpr e)     // toDate(format)、length()、likeRegexpr(pattern)
static <N extends Number> NumView<N> hana(NumExpr<N> e)

hana(l.createdOn()).toVarchar("YYYYMMDD").upper()            // 视图 → TextExpr，继续点
hana(l.createdOn().addDays(3)).toVarchar("YYYYMMDD")         // 先做核心运算再进视图
hana(l.delivery()).toDate("YYYYMMDD").addDays(1)              // 文本 → DateExpr
```

- 视图只是方言函数换了个入口，渲染仍按现在的规则：放到别的方言上渲染时报错。
- 没有主操作数的方言能力（`hint`、`distinctOn`、`fromView`、`hierarchy`、`intersectAll` 等）照旧是方言门面的静态函数。
- 0002 里 PostgreSQL 的 `pg(e).isDistinctFrom(x)` 就是这种视图的先例。

### 5. 语句式条件

目的是条件一行一个，不用 `.and(...)` 链起来，也就不用关心链到一半返回的是什么类型。三种做法：

**甲：条件只能写成语句（EasyQuery 的做法，推荐）**

```java
from(Lips.class).where(l -> {
    l.delivery().contains("D");                      // 比较方法返回 void，写进当前的接收处
    l.quantity().gt(new BigDecimal("2"));
    if (maker != null) l.mara().manufacturer().eq(maker);   // 可选条件就是 if
    any(() -> {                                      // OR 用无参的语句块隔开
        l.unit().eq("EA");
        l.unit().eq("KG");
    });
})
.select(l -> cols(l.delivery(), bool(() -> l.quantity().gt(new BigDecimal("2"))).as("big")))   // 条件当值用：语句块
```

- **规则**：比较、`in`、`isNull` 等返回 `void`，写进最内层的接收处，块里的条件用 AND 连起来。接收处有 `where`、`having`、`qualify`、连接的 `ON`、关系的 `any`/`none`/`where`、`any(() -> ...)`（OR）、`not(() -> ...)`、`bool(() -> ...)`、`caseWhen(() -> ..., 结果)`、聚合的 `filter(() -> ...)`、全局过滤器的回调。在没有接收处的地方（比如 `select` 的 lambda 里直接写 `l.x().eq(1)`）写条件，立即报错，并说明应该套 `bool(() -> ...)`。
- **复用**：辅助方法写成返回 `void` 的方法，在块里调用：`static void todo(WorkItem w, String user) { any(() -> { w.assignee().eq(user); ... }); }`。条件不能存进变量、不能传来传去。
- **没有歧义**：每个位置只有一个返回 `void` 的函数式接口；无参的块 `() -> {...}` 是显式类型的 lambda，也不会和别的重载冲突（探针与 EasyQuery 一致）。
- **能删掉的**：`Cond` 不再出现在公开 API 里；`all`、`onlyIf`、`ifPresent`、`Cond.and`/`or` 都删掉。不学 EasyQuery 的 `eq(boolean 条件, 值)` 重载，可选条件用 `if` 或作用域的 `skipEmpty()`。
- **代价**：接收处靠线程局部的栈（与 `skipEmpty` 相同），"写了就生效"是隐式的；所有收条件的 API 都改成收语句块，生成的作用域 `From1..From16` 的签名跟着改。

**乙：条件既是值、又自动登记，被组合时撤下**

条件照旧返回 `Cond`，创建时登记进当前的块，被 `any`、`caseWhen` 等收下时撤下。改动最小，`any(a, b)`、辅助方法返回 `Cond` 都照旧能写。**不推荐**：只要某个收条件的地方漏了"撤下"，同一个条件会同时进 AND 和 OR，SQL 不报错、结果悄悄出错。

**丙：保持值式，只做小改**

`where(l -> all(a, b, c))` 本来就不需要链式的 `.and`；只删掉 `Cond.and`/`or`，让 `all`、`any` 忽略值为空的条件。条件可以返回、存起来、组合，没有隐式状态；代价是顶层要包一层 `all(...)`，可选条件还是靠 `skipEmpty` 或 `onlyIf`。

**取舍**：用户要的是"一行一个条件"，甲是能做到这一点、又不会悄悄出错的做法，调研在 EasyQuery 的 issue 里也没有查到针对这种写法的抱怨。如果更看重条件能当值传递和组合，选丙。

### 6. 其他写法的整理（候选，逐条确认）

| 候选 | 现状 | 建议 |
| --- | --- | --- |
| 静态的类型函数 | 约 55 个，与实例方法二选一 | 删掉，只留实例方法（第 2 节） |
| `onlyIf`、`ifPresent` | 与 `skipEmpty`、`if` 重复 | 第 5 节选甲时删掉 |
| `Cond.and` / `Cond.or` | 与 `all` / `any` 重复 | 删掉（三种做法都删） |
| UPDATE 的赋值 | `set(t -> t.title().to("x")).set(t -> t.qty().to(...))` 每列一次 `set` | 改成语句式：`set(t -> { t.title().to("x"); t.qty().to(t.qty().plus(val(1))); })` |
| `substring`、`left`、`right` 的表达式参数重载 | int 与 `Expr<Integer>` 两套 | 留 int 一套加 `NumExpr<Integer>` 一套，作为实例方法 |
| `plus(List)` | 与可变参数版本重复 | 删掉，用可变参数 |
| `cols(Cols first, Expr... more)` | 拼接已有的 `Cols` | 先查用处，没有就删 |
| `cols(...)` 的结果按名字取值 | `r.get("material")` 无类型，测试里大量 `(BigDecimal) r.get(...)` | 文档推荐局部 record 加 `into(X.class)`；`cols` 只留给真正动态的场景 |
| 关系的聚合 | `m.deliveries().sum(l -> l.quantity())` 返回 `Expr<N>` | 返回本族；`max`/`min` 用泛型保住类型 |

### 7. TS：值类型改用 decimal.js 与 @internationalized/date

现在文本、小数、日期、时间戳在 TS 里都是 `string`，按值类型分不出族。用户 2026-10-25 提出：TS 引入真正的小数、日期类型，与 vue-tsx-pro 一致，需要的依赖可以加。vue-tsx-pro（`packages/ui`）现在用的是：

| 值 | vue-tsx-pro | 依赖与版本（vue-tsx-pro 的 catalog） |
| --- | --- | --- |
| 精确小数 | `Decimal`；组件的数值统一是 `NumericValue = number | bigint | Decimal`，表单有 `decimalSchema` | `decimal.js` 10.6.0；运行时不导入，按 `constructor.isDecimal` 识别，所以 `Decimal.clone` 配过精度的实例也认 |
| 大整数 | 原生 `bigint` | 无 |
| 日期 | `CalendarDate` | `@internationalized/date` 3.12.4 |
| 不带时区的日期时间 | `CalendarDateTime` | 同上；另有 `ZonedDateTime`、`Time` |

**建议的 TS 值类型**（与 Java 的 `BigDecimal`、`LocalDate`、`LocalDateTime` 一一对应）：

| SQL 类型 | 现在 | 改成 |
| --- | --- | --- |
| `decimal` | 十进制字符串 `'3.000'` | `Decimal` |
| `bigint` | `bigint` | 不变 |
| `integer`、`double` | `number` | 不变 |
| `date` | `'YYYY-MM-DD'` | `CalendarDate` |
| `timestamp` | `'YYYY-MM-DD HH:MM:SS[.fff]'` | `CalendarDateTime` |
| `text`、`boolean`、`bytes` | 不变 | 不变 |

这样字段的 TS 类型就能区分各族，`Ref` 按字段类型映射：`string` → `TextCol`，`number` / `bigint` / `Decimal` → `NumCol`，`CalendarDate` → `DateCol`，`CalendarDateTime` → `DateTimeCol`，其余 → `Col<T>`。TS 的写法和 Java 对齐成 `l.unit.contains("E")`、`l.quantity.sum()`、`l.createdOn.addDays(3)`。

```ts
@table("LIPS", { schema: SCHEMA })
export class Lips extends ClientEntity {
  @column("VBELN") delivery!: string;
  @column("ERDAT", sapDate) createdOn!: CalendarDate | null;          // 转换器改成 CalendarDate ↔ 'yyyyMMdd'
  @column("LFIMG", decimal, { precision: 13, scale: 3 }) quantity!: Decimal;
}
```

- **依赖怎么加**：`decimal.js` 和 `@internationalized/date` 作为核心包的 **peerDependencies**，由应用安装，与 vue-tsx-pro 用同一份实例，前端从接口拿到的值不用再转换。代价是核心包不再是"运行时没有第三方依赖"，README 与包描述要改。参数里的 `Decimal` 按 `constructor.isDecimal` 识别（与 vue-tsx-pro 相同），读出的值用默认的 `Decimal` 构造。
- **其他走法**（不推荐）：
  - 保持字符串、维持函数写法：两端写法不一致；
  - 在 `Expr<T>` 上用 `this: Expr<string>` 限定方法：小数、日期列上也能点出 `contains`，限定形同虚设；
  - 自己做品牌字符串类型（`string & { brand }`）：不另加依赖，但前端拿到的仍是字符串，还要再转一次。
- **影响**：所有读写小数、日期、时间戳的 TS 代码都要改（读出的值从字符串变成对象，比较用 `compare`/`equals`）；转换器 `sapDate` 改成 `CalendarDate`；SQLite WASM、浏览器里同样适用；`@zerodep-dev/idb` 有自己的编码，这次不动。金样例的 SQL 不受影响，参数的文本写法要核对（`Decimal` 的 `toString` 会去掉末尾的零，参数按 `toFixed()` 写出才与现在相同）。

### 8. 实施与验证

1. 生成器产出五个族的表达式、列、聚合类和协变重写；`Metadata` 认新的列类（值类型从类本身或 `NumCol<N>` 的泛型参数读出）。
2. 核心函数搬成实例方法，删掉静态版本；方言函数改成方言门面的视图（第 4 节）。
3. 语句式条件（按第 5 节的选择）：选甲时是接收处的栈、各处收条件的 API 改成语句块、作用域签名，删除 `all`/`onlyIf`/`ifPresent`/`and`/`or`；选丙时只删 `Cond.and`/`or`。
4. 全部测试、`SpecCases`、文档示例改成新写法。**金样例一个都不应该变**：新写法渲染出的 SQL 与旧写法逐字相同，这是最主要的回归证据。
5. 实库测试五个库全跑；TS 按第 7 节的选择处理。

## 待确认的问题

1. 族的命名：`TextCol` / `NumCol` / `DateCol` / `DateTimeCol`，还是按 Java 类型叫 `StringCol` / `LocalDateCol`，或者 `ColString` 这种前缀式？
2. 数值列：常用四种给具体的类名（`DecimalCol quantity()`），还是统一写 `NumCol<BigDecimal>`？
3. 聚合结果要不要单独的 `XxxAgg` 类（`filter`、`over` 在编译期受限），还是放在各族上、不是聚合时运行时报错（少五个类）？
4. 语句式条件选甲（只能写成语句）、乙还是丙？
5. TS 是否改用 `Decimal`、`CalendarDate`、`CalendarDateTime`，并作为 peerDependencies 加入核心包？
6. 方言能力用丙（方言门面的视图），还是另有偏好？
7. 第 6 节的候选逐条定。

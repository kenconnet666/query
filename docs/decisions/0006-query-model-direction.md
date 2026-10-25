# 0006 下一代查询写法：手写元模型、关系、对象树与条件对象

- 状态：已采纳（2026-10-23），在 `feat/v2` 上分六期实现，见文末[决定](#决定)；列引用 `Col<T>` 已由 [0014](0014-typed-expressions.md) 改成按逻辑 SQL 类型分的列接口（`ColText<String>` 等）
- 目标版本：2.0（改变实体的声明方式，1.x 的兼容性承诺不允许）
- 修订对象：0002 第 1、3、5 条，以及 0003 中"按请求对象的注解生成条件：不做"的决定

## 背景

1.0 发布后回看 Java 的写法，啰嗦的地方集中在三处：

1. **列引用太长**：每一列都要写 `l.col(Lips::getMaterial)`；连接条件写成 `ma.col(Mara::getMaterial).eq(l, Lips::getMaterial)`。0005 曾把这一点记为"不用代码生成的代价"。
2. **作用域参数到处重复**：`(l, ma, mk) ->` 要在 join、where、groupBy、select 里各写一遍。为此还生成了 `From1..8`、`Fn` 两组类，并为每个作用域设了最多 8 张表的上限。
3. **复杂查询没有可以沉淀的地方**：连接条件、业务口径、DTO 形状都写在调用点。同一组连接（例如物料加物料描述）在每个查询里都要再写一遍。

用户在 2026-10-23 放开了下列限制：

- 可以要求实体、DTO、VO 继承本库的基类或实现本库的接口；
- 可以在运行时生成代理子类；
- 可以使用注解、泛型参数和接口继承；
- 可以要求使用方多手写一些东西，只要调用点因此变简单。

同时提出三项新需求，都参考 EasyQuery：

- 用注解或手写的类声明连接关系（导航）；
- 由 DTO 生成查询条件，条件可以沿导航路径写；
- 结果映射成 VO 对象树，包括子对象和子集合。

不变的约束：

- 不用 APT，使用方不生成代码；
- 三个方言同步实现；
- HANA 没有 LATERAL、行值比较和 `VALUES` 表构造（0002 的语法边界）。

## 参考做法

完整的调研笔记不入库，这里只记结论。

| 参考 | 做法 | 借鉴什么 |
| --- | --- | --- |
| EasyQuery 3.2.17（`2b25e9510`） | APT 生成代理 `XxxProxy`，`t.author().name()` 走隐式连接；`@Navigate` 声明一对一、多对一、一对多、多对多；`whereObject` 加 `@EasyWhereCondition`；`selectAutoInclude(VO)` 生成对象树 | 关键事实：**元数据、隐式连接、隐式子查询、include 批量加载、`whereObject` 的核心实现都只用反射和字符串属性名**，APT 生成的代理只是类型安全的外壳。所以去掉 APT、换成手写元模型，语义可以照搬 |
| Blaze-Persistence Entity Views 1.6 | 手写视图接口，用 `@Mapping("owner.address.city")` 写取值路径；集合有 JOIN、SELECT（批量）、SUBSELECT、MULTISET 四种加载策略；可以按视图属性过滤和排序 | "视图类等于结果形状加取值路径加加载策略"，调用点只写 `select(View.class)` |
| jOOQ 3.21 | 隐式路径连接，同一路径只连接一次；**一对多路径默认报错，必须显式写出**；`mapping(Record3::new)` 按位置检查构造器参数；MULTISET | 一对多不做隐式连接的原则；构造器投影。jOOQ 官方把 HANA 列为不支持 MULTISET 的方言 |
| Ktorm 4.2、Exposed 1.6 | 手写的表对象，列就是字段；`references` 声明外键，按引用自动 LEFT JOIN；每个别名克隆一份表实例 | 手写元模型的规模和写法 |
| jimmer 0.12 | 对象抓取器；集合按父键批量查询；`@Formula`；由 Specification 类生成条件（值为 null 时忽略，`flat` 把关联条件拍平） | 条件对象和计算属性的用法。jimmer 依赖 APT，只借鉴体验 |
| Hibernate 7 | `@Formula`、`@JoinFormula`、`@SQLRestriction`、`@Subselect` | "SQL 逻辑声明在手写类上"的先例。教训：它们都是 SQL 字符串，三个方言要写三套；本库应该存表达式树 |

## 要做的决定

下面五件事彼此独立，可以分别选择，最后再组合成整体方案：

1. 列怎样引用（元模型的形态）；
2. 关系（导航）怎样声明；
3. 结果怎样成形，包括 VO 对象树；
4. 条件对象；
5. 可复用的查询逻辑放在哪里。

### 一、列引用：五种元模型

#### A. 手写表类，字段就是列

```java
@Table(schema = "ERP", value = "LIPS")
public class Lips extends Entity {                         // 本库的基类
    @Column("VBELN") public Col<String> delivery;
    @Column("MATNR") public Col<String> material;
    @Column("LFIMG") public Col<BigDecimal> quantity;
}
// l.material.eq("X")、ma.material.eq(l.material)
```

- **机制**：库为每个别名实例化一次表类，把绑定了别名的 `Col` 注入字段。列的 Java 类型从字段的泛型声明读取。
- **好处**：写法最短，与 TS 的 `l.material` 一致；不依赖线程局部状态；任何类型都可以，包括 `Boolean`、枚举和基本类型的包装。
- **坏处**：
  - 表类不再是结果的行类型，结果要另外写 DTO 或 record；
  - 字段只能是可变的 public 字段。探针证实，JDK 27 EA 上反射修改 final 字段会报警告，并提示将来会被禁止（JEP 500）。
  - 也可以把表类写成 record，由构造器注入，这样是真正不可变的，代价是写法变成 `l.material()`。
  - 单继承：共用的列只能放进一条基类链。

#### B. 接口表，由 JDK 动态代理实现

```java
public interface Client extends Entity { @Column("MANDT") Col<String> client(); }

@Table(schema = "ERP", value = "LIPS")
public interface Lips extends Client {
    @Column("MATNR") Col<String> material();
    @Column("LFIMG") Col<BigDecimal> quantity();
    default Mara mara() { return one(Mara.class, m -> m.material().eq(material())); }   // 关系
    default Expr<BigDecimal> openQuantity() { return minus(quantity(), delivered()); }  // 计算列
}
```

- **机制**：`java.lang.reflect.Proxy` 实现接口：
  - 列方法返回绑定了别名的 `Col`，按方法缓存；
  - default 方法用 `InvocationHandler.invokeDefault`（Java 16 起可用）执行。
- **好处**：
  - 零依赖，不可变；
  - **接口可以多继承**：`Client`、`PeriodStock` 这类公共列和业务口径可以像拼积木一样组合；
  - 子接口重新声明同名方法，就能改列名（探针已验证）；
  - 关系和计算列直接写成 default 方法，类型都受检查。
- **坏处**：
  - 写法是 `l.material()`，带括号；
  - 表接口同样不是结果类型；
  - 代理对象在调试器里不如普通对象直观。

#### C. 普通实体加标记值（JaQu 的做法）

```java
@Data @Table("LIPS") public class Lips { @Column("MATNR") private String material; @Column("ERDAT") private LocalDate createdOn; }
// $(l.getMaterial()).eq("X")、$(ma.getMaterial()).eq(l.getMaterial())
```

- **机制**：库给每个别名造一个实体实例，每个字段填一个独一无二的标记值（例如 `new String(...)`），`$()` 按对象身份查回对应的列。
- **好处**：实体就是行类型，同一个类既能当查询用的表，又能当结果。
- **坏处**（探针已证实）：
  - `Boolean`、枚举和基本类型做不出独一无二的标记值，这类字段无法引用；
  - **在 Valhalla 下不可靠**：JDK 27 EA 加 `--enable-preview` 时，`Integer` 和 `LocalDate` 变成值类型，没有对象身份。一个与标记值相等的普通常量会被**误认成列**。只有 `String`、`BigDecimal` 这类身份类是安全的。
  - 每处都要套一层 `$()`。

结论：不采用。

#### D. 运行时子类代理，记录 getter 调用（Querydsl alias 的做法）

```java
$(l.getMaterial()).eq("X")       // l 是运行时生成的 Lips 子类，getter 把调用记到线程局部变量里
```

- **好处**：
  - 1.x 的 Lombok 实体可以原样保留，迁移成本最低；
  - 基本类型也能引用。
- **坏处**：
  - Java 17 生成类的子类需要字节码库（ByteBuddy 约 4 MB），或者自己写一个小的字节码生成器。核心包目前零依赖。
  - record 和 final 类无法代理。
  - 忘记套 `$()` 时不会报错，只会悄悄出错。探针能检测出"一次 `$()` 记录到两次调用"，但检测不出"根本没有调用 `$()`"。

本轮探针用 JDK `Proxy` 代理接口，验证了记录机制本身；没有引入字节码库，类的子类代理还没有验证。

#### E. 现状：getter 引用

`l.col(Lips::getMaterial)`。保留它只作为 1.x 的写法。

**推荐**：B（接口表），A（字段表类）作为备选。两者能力相同，区别在于：

- B 用多继承组合公共列，换来 `()`；
- A 写法最短，换来单继承和可变字段。

### 二、关系：用注解或方法声明导航

两种声明方式都提供，语义相同：

```java
// 1) 方法：条件任意，类型受检查（B 用 default 方法，A 用普通实例方法）
default Mara mara() { return one(Mara.class, m -> all(m.material().eq(material()), m.client().eq(client()))); }
default Makt text() { return one(Makt.class, t -> all(t.material().eq(material()), t.language().eq("1"))); }
default Many<Lips> items() { return many(Lips.class, i -> i.delivery().eq(delivery())); }

// 2) 注解：等值键的简写，属性名在读取元数据时校验
@ManyToOne(self = "material", target = "material") Mara mara();
@OneToMany(self = "delivery", target = "delivery", orderBy = "position") Many<Lips> items();
```

**规则**（取自 EasyQuery 和 jOOQ）：

- **一对一、多对一**：隐式 LEFT JOIN。同一个别名上的同一处声明只连接一次：探针用"声明处 lambda 的类"作为键，两次导航只生成一个 JOIN。只被已经省略的条件引用的连接，不会出现在 SQL 里。
  - 只有声明了 `required` 时才用 INNER JOIN；
  - 关系可以继续往下走：`l.mara().maker().name()`。
- **一对多**：不做隐式连接，否则会产生重复行，只提供集合运算：
  - `l.items().any(i -> ...)`、`none(...)` 渲染为 `EXISTS`、`NOT EXISTS`；
  - `count(...)`、`sum(i -> i.quantity())`、`max(...)` 渲染为相关标量子查询；
  - 同一路径有多个聚合时，可以改成"分组派生表加 LEFT JOIN"（EasyQuery 的 group join），由查询或关系上的开关决定。
- **取每组第一行**：`l.items().first(i -> i.createdOn().desc())` 渲染为 `ROW_NUMBER() OVER (PARTITION BY 键 ORDER BY …)` 派生表加 LEFT JOIN，再按行号等于 1 过滤，之后可以继续导航。
  - 这正是使用方"窗口函数取最新供应商"的写法；
  - HANA 没有 LATERAL，所以统一走窗口函数。
- **多对多**：经过中间表，`many(Target.class, via(Mapping.class, ...))`。使用方目前没有用例，最后再做。

**效果**：大部分查询的作用域只剩一张根表。连接写进了表定义，lambda 参数个数和 8 张表的上限不再是问题。显式的 `join` 仍然保留，用于临时的、不值得写进表定义的连接。

### 三、结果成形：投影与 VO 对象树

**1. 构造器投影**（jOOQ 的 `mapping`）

```java
.select(l -> row(Delivery::new, l.mara().partNumber(), max(l.text().name()), sum(l.quantity())))
```

- 按位置检查类型：探针里第 2 位类型不符、参数个数不符都编译失败。
- 每行直接调用构造器，不重放 lambda。现在函数式投影的重放、结构核对，以及"不能按行值决定读哪一列"的限制都可以去掉。

**2. VO 自动投影**：VO 是普通 record 或 Lombok 类，按同名属性取列。用注解声明路径、计算列和子集合：

```java
@View(Likp.class)                                         // 根表
public record DeliveryVO(
    String delivery,                                      // 同名列
    @Path("customer.name") String customerName,           // 多对一：并入主查询
    @Path("openQuantity") BigDecimal open,                // 表接口上的计算列
    CustomerVO customer,                                  // 嵌套的一对一 VO：并入主查询
    @Children(path = "items", orderBy = "position") List<ItemVO> items) { }   // 一对多：单独查询

List<DeliveryVO> rows = db.list(from(Likp.class).where(h -> ...).selectAs(DeliveryVO.class));
```

- **主查询**：标量、多对一和嵌套 VO 都并入主查询，所以主查询的行不会重复，分页照常。
- **子集合**默认分批查询（EasyQuery include、Blaze SELECT 策略、jimmer 都是这样）：
  - 主查询结束后，按父键分批执行 `IN (...)`，在内存里按键装配；
  - 每批的大小受 0004 的参数上限约束。
- **复合键**：HANA 没有行值比较，所以改用 SUBSELECT 策略，即 `EXISTS (SELECT 1 FROM (主查询) p WHERE p.a = c.a AND p.b = c.b)`，不展开成很长的 `OR`。
- **一条 SQL 的做法**（JOIN 后再分组，也就是现在的 `Nest`）作为可选策略保留，适合子项少的场景。
- MULTISET（JSON 聚合）只能作为 PostgreSQL、MySQL 方言包的可选项，因为 HANA 不支持。
- **改正 EasyQuery 的一个坑**：VO 的路径或属性在实体里找不到时，EasyQuery 静默跳过。本库在读取元数据时直接报错。

**3. 类型化的 VO 定义**（不想用字符串路径时）

VO 实现本库的泛型接口，在一个方法里用代码描述取值：

```java
public record DeliveryVO(String delivery, String customerName, List<ItemVO> items) implements ViewOf<Likp> {
    static ViewDef<Likp, DeliveryVO> define(ViewDef<Likp, DeliveryVO> v) {
        return v.map(h -> row(DeliveryVO::new, h.delivery(), h.customer().name(), v.children(h.items(), ItemVO.class)));
    }
}
```

字符串路径版本短，类型化版本有编译期检查。两者都在读取元数据时校验，并提供一个测试工具 `QueryModel.verify("com.example.vo")`，在单元测试里一次校验整个包。

### 四、条件对象

```java
public class DeliveryFilter {                                     // 前端请求对象
    @Where String delivery;                                       // 同名列，默认相等；null 时忽略
    @Where(path = "customer.name", op = CONTAINS) String customer; // 多对一：值非空时才加连接
    @Where(path = "items.material", op = IN) List<String> materials;   // 一对多：EXISTS
    @Where(path = "createdOn", op = GE) LocalDate from;
    @Where(path = "createdOn", op = LE) LocalDate to;
    @OrderBy(allowed = {"delivery", "createdOn"}) String sort;    // 排序白名单
}
from(Likp.class).where(filter).selectAs(DeliveryVO.class);
```

- **取值规则**：null、空串（可以另外允许）、空集合都不生成条件。
- **一对多路径**：同一路径上的几个条件合并进同一个 EXISTS，与 EasyQuery 一致。
- **运算符要克制**：相等、不等、大小比较、`IN`、`BETWEEN`、`CONTAINS`、`STARTS_WITH`（这两个会转义 `%` 和 `_`）、`IS NULL`。
- **复杂条件**：条件类实现 `Filter<Likp>`，用代码写：

  ```java
  public Cond where(Likp h) { return any(h.status().eq("A"), h.items().any(i -> i.blocked().eq(true))); }
  ```

  注解和代码可以在同一个类里共存。
- **重新评估 0003**：0003 拒绝"按请求对象的注解生成条件"，理由是字符串属性名会丢掉类型安全。现在路径会在读取元数据时和测试里统一校验，而 25 个使用方文件都用请求对象传筛选条件，收益明显。所以改为提供，但它不是唯一写法。

### 五、可复用的查询逻辑放在哪里

| 逻辑 | 位置 |
| --- | --- |
| 计算列（`openQuantity`、物料号归一化） | 表接口的 default 方法，或表类的实例方法 |
| 常用连接（物料加描述、客户） | 表上的关系方法（第二节） |
| 表级默认过滤（客户端、删除标记） | 0002 的全局过滤器；也可以在表上覆盖 `defaultWhere()` 声明，查询用 `unfiltered()` 关闭 |
| 公共列和业务口径（十六个期间列求和） | 公共接口（B 方案多继承）或泛型基类（A 方案） |
| 只有列名不同的同形状表 | 子接口重新声明列方法并改列名（B），或用 `@ColumnOverride`（A，即 JPA 的 `@AttributeOverride`） |
| CTE、派生表、码表 | **CTE 类**：一个类同时声明列和定义查询，见下 |

CTE 类解决了"CTE 的列形状和结果类型要写两个类"的问题：

```java
@Cte("material_scope")
public interface MaterialScope extends Entity {
    Col<String> material();
    Col<String> partNumber();
    static Query<?> define(Params p) {                     // 也可以写成 record 构造参数
        return from(Mara.class).where(m -> m.type().eq(p.get("type")))
            .select(m -> cols(m.material(), normalize(m.partNumber())));
    }
}
from(MaterialScope.class, Params.of("type", "ZMAT")).join(Lips.class, (s, l) -> l.material().eq(s.material()))
```

参数的传法有三种候选：`Params`、构造参数、泛型参数对象。这是待决问题之一。

## 组合成整体方案

| | 甲：接口表（推荐） | 乙：字段表类 | 丙：子类代理加标记 | 丁：保持现状加简写 |
| --- | --- | --- | --- | --- |
| 列 | `l.material()` | `l.material` | `$(l.getMaterial())` | `l.col(Lips::getMaterial)` |
| 实体同时是结果类型 | 否 | 否 | 是 | 是 |
| 公共列与口径 | 接口多继承 | 单继承基类 | 单继承基类 | 单继承基类 |
| 关系 | default 方法或注解 | 实例方法或注解 | 注解 | 注解，`l.nav(Lips::getMara)` |
| 新依赖 | 无 | 无 | 字节码库 | 无 |
| 误用风险 | 低 | 低 | 忘写 `$()` 时悄悄出错 | 低 |
| 迁移 | 实体改写成接口 | 实体改写成表类 | 实体基本不动 | 不动 |
| TS 对应 | 已有 `l.material`；关系用类方法或装饰器 | 同左 | 不适用 | 不变 |

第二到第五节（关系、对象树、条件对象、CTE 类）与第一节选哪种元模型无关，四种方案都可以用。差别在于甲和乙的关系、计算列能写成有类型的方法，丙和丁只能靠注解里的字符串路径。

**甲方案的同一查询**（对照 [api.md](../api.md) 第 3 节）：

```java
from(Lips.class).skipEmpty()
    .where(l -> all(
        l.createdOn().between(start, end),
        l.mara().manufacturer().eq(maker),                          // 作用域开了 skipEmpty()，maker 为空时没有这个条件
        l.businessData().any()))                                    // 一对多关系上的 EXISTS
    .groupBy(l -> l.mara().partNumber())
    .orderBy(l -> l.mara().partNumber())
    .select(l -> row(Delivery::new, l.mara().partNumber(), max(l.text().name().coalesce(l.mara().partNumber())), sum(l.quantity())));
```

与现在相比：

- 连接从三处显式 `join` 变成两个表方法 `mara()` 和 `text()`；
- 作用域只有一个参数；
- `col(getter)`、`r.get(...)` 都不再需要。

## 决定

用户在 2026-10-23 选定甲方案，要求"删除重复的、复杂的能力，只保留简明易懂的写法"，方便使用优先于类型安全；用脚本生成代码来放宽表数和参数个数的上限。讨论稿的八个问题这样解决：

1. **元模型**：甲。表是继承 `Entity` 的接口，每次表出现一个 JDK 动态代理；列方法的结果和 default 方法的结果都按（方法，参数）记住，所以同一关系只连接一次。
2. **实体与结果类型分开**：接受。结果、CTE 的行、常量行用 record 或类，按 `t.col(Row::x)` 引用；`selectAs(VO.class)` 按同名属性取列。
3. **关系声明**：两种都提供。default 方法里的 `one(...)`、`many(...)` 能写任意条件；`@ManyToOne`、`@OneToMany` 是按列名配对的简写。多对一用到时才 LEFT JOIN，一对多不做隐式连接，只能通过 `any`、`count`、`first`、`list` 等使用（jOOQ 的原则）。
4. **子集合**：分批 `IN`。父查询只多取连接键，每层一条子查询，每 500 个父键一批；多列键写成 `(a = ? AND b = ?) OR ...`，因为 HANA 没有行值比较。不做 JOIN 后再分组，那会让父行重复、分页失真。
5. **条件对象**：运算符为相等（集合自动 IN）、不等、四种大小比较、包含、开头、结尾；字段写多个列时为 OR，列可以走关系路径。字段名在构造时就解析，写错时无论有没有值都报错，所以不另设 `verify`。
6. **CTE 类**：不做。返回 `Cte` 的普通方法已经能复用，参数就是方法参数。
7. **版本**：2.0 直接替换，不与 1.x 并存；1.x 已有场景的金样例保持不变，作为回归证据。
8. **TS**：与 Java 同步，每一期两端一起完成并共用金样例。关系用 `@one`、`@many` 字段；条件对象用 `@where` 装饰器或普通对象。

讨论稿之外，实现时还做了这些取舍：

- **删掉的写法**：`IfPresent` 系列（由作用域的 `skipEmpty()` 代替）、函数式投影、`(表, getter)` 比较简写、`@MapFrom`、`Nest`、`selectValue`、`selectAs(VO, 额外列)`。
- **动态列**：`exclude` / `only` 把列写成 NULL 而不是从 SQL 里删掉，结果形态不变，`row(...)`、`into`、UNION 都不受影响；相同写法的分组键一起去掉，于是"用户选维度"不需要单独的 API。`@OnDemand`、分组列表都因此不做。
- **排序**：`sortBy` 只认结果列名，等于白名单；不做排序对象的注解。
- **原生 SQL**：模板参数混用表达式和值，按方言给不同写法（`.on(...)`），`sqlTable` 当作数据源，`identifier` 让同一段原生 SQL 在三库可用。不做 `@Path` 取值路径，嵌套 VO 已经覆盖。

## 验证证据

临时探针（2026-10-23，JBR 17.0.14，以及 JDK 27 jep401ea3；代码在会话临时目录，不入库）：

| 探针 | 结论 |
| --- | --- |
| 字段表类注入 | public 字段与 final 字段都能注入，`Col<T>` 的 T 能从泛型声明读出；record 表类能通过构造器注入。JDK 27 EA 修改 final 字段时报警告并提示将来禁止，所以字段不能是 final |
| 接口表加 `Proxy` | 列方法、`invokeDefault` 执行的 default 方法（关系、计算列）、接口多继承、子接口改列名、泛型模板 `<E extends PeriodStock>` 都可用；同一关系导航两次只登记一个 JOIN |
| 标记值 | `String`、`BigDecimal`、`LocalDate`、`Integer` 可以做标记，`Boolean` 不行。Valhalla 预览模式下 `Integer` 失去身份，与标记值相等的常量被误认成列 |
| 记录 getter 调用 | 基本类型和 boolean 都能记录；"一次 `$()` 记录到两次调用"能检测出来 |
| 编译期检查 | 5 种错误写法都编译失败：列与列类型不符、列与值类型不符、`row()` 某个位置类型不符、`row()` 参数个数不符、标记方式类型不符 |

实现后在本机三库（WSL Docker 中的 HANA Express、PostgreSQL 17、MySQL 8.4）和 CI 上验证：

- 导航关系、一对多的 EXISTS 与相关子查询、`first`（ROW_NUMBER 派生表）在三库上结果正确，两端共用 `navigation`、`one-to-many` 金样例；
- 子列表与对象树：父查询加一条子查询，三库结果正确；500 个键一批的分批逻辑有单元测试；
- 动态列（`dynamic-columns`）、条件对象（`filter-object`，含 `ESCAPE !` 的通配符转义）、原生 SQL（`native-sql`）都有金样例和三库实库场景；
- TS 装饰器声明关系时，关系字段的参数类型能从装饰器上下文推导；引用的成员类型写成分配式条件类型，泛型模板 `<E extends PeriodStock>` 里的共有字段仍是列表达式。

没有做的：字节码子类代理（丙方案）。

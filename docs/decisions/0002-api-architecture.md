# 0002 API 与整体架构

- 状态：已采纳（2026-10-21）；第一期实现中
- 修订 0001：TypeScript 改以 7 为唯一基准，不再兼顾 6.0；首批方言改为 HANA 和 PostgreSQL，MySQL 随后，DuckDB 延后。0001 的其余决定继续有效。

## 背景

0001 之后，用一系列临时探针比较了多种 API 路线，同时确定了取舍顺序：

1. 简洁、灵活、功能强大，并且方便用户扩展；
2. 类型安全尽量做到，但不为它牺牲第 1 条；
3. 性能不是首要目标，但元数据、映射和渲染要有缓存和惰性计算，不能有明显浪费。

此外还明确了以下几点：

- 表和字段可以用注解标记，可以要求用户使用 Lombok；TypeScript 可以使用装饰器。
- 结果要能方便地映射到不同的 DTO、VO 或临时类。
- 由共同的核心包加各方言包组成。用户必须选定方言包、创建该方言的实例，才能访问对应的数据库；库不尝试抹平数据库之间的差异。
- 思路可以参考 EasyQuery 及其增强包，实现一律重新设计。

探针代码只是临时验证，没有进入仓库，结论和证据记录在本文末尾。

## 设计原则

1. **类就是行的形状。**
   - 带 `@Table` 的类是表实体；不带的是临时实体，可以作为 CTE 的行、派生表的行或结果 DTO。
   - 同一张表可以对应多个实体类，每个只声明自己用到的列。
2. **语法差异由方言渲染，语义差异不模拟。**
   - 引号、分页、`FROM DUMMY`、参数占位符等语法差异由方言负责渲染。
   - 核心包只包含所有已支持数据库语义都相同的能力，也就是它们的交集。
   - 其余能力只在对应的方言包中出现，例如 HANA 的 `DAYS_BETWEEN`、PostgreSQL 的 `DISTINCT ON`。
3. **列的类型来自实体。**
   - Java 用 getter 引用（`Lips::getQuantity`）取得列，列的类型就是 getter 的返回类型。
   - TypeScript 由字段声明的类型推导。
4. **扩展与内置同构。**用户自定义的函数、类型、方言和子句，与内置的写法和注册方式完全相同。
5. **查询是不可变的值。**
   - 每一步构建都返回新的查询对象，可以安全地复用和分支。
   - 渲染结果、映射计划和元数据都惰性计算，并在第一次计算后缓存。

## 模块

| Java（Maven 多模块，groupId `org.dpdns.zerodep.query`） | TypeScript（pnpm workspace） | 职责 |
| --- | --- | --- |
| `query-core` | `@query/core` | 注解、元数据、类型与转换器、表达式、查询构建、渲染框架、执行、映射、缓存、方言 SPI |
| `query-hana` | `@query/hana` | HANA 方言：门面、数据库实例、HANA 专属函数与子句、JDBC 读写细节 |
| `query-postgres` | `@query/postgres` | PostgreSQL 方言 |
| `query-mysql`（第三期） | `@query/mysql`（第三期） | MySQL 方言 |
| `query-spring`（第三期） | — | 通过 `DataSourceUtils` 加入 Spring 事务 |

核心包运行时不依赖任何第三方库。每个方言包只依赖核心包，JDBC 驱动由用户自行提供。

仓库根目录的 `pom.xml` 是聚合父工程，各 Java 模块放在 `java/` 下，TypeScript 工程放在 `typescript/` 下，`spec/` 放两端共用的金样例（第二期开始）。

## Java

### 声明：注解实体

```java
@Data
public abstract class ClientEntity {                      // shared columns live in a base class
    @Column("MANDT") private String client;
}

@Data @FieldNameConstants
@Table(schema = "ERP", value = "LIPS")
public class Lips extends ClientEntity {
    @Column("VBELN") private String delivery;
    @Column("MATNR") private String material;
    @Column("ERDAT") @SapDate private LocalDate createdOn;  // composed annotation mounts a converter
    @Column("LFIMG") private BigDecimal quantity;
}

public record MaterialScope(String client, String material, String partNumber) { }  // temporary entity
```

- 实体可以是 Lombok `@Data` 类、普通 JavaBean 或 record。
- 字段可以放在基类中；与 getter 同名的接口也可以作为共享形状（见"复用"）。
- 不写 `@Column` 时，列名由数据库实例配置的命名策略决定，默认与属性名相同。

注解的取值只能是编译期常量。在这个限制内，按推荐程度选用以下几种强类型写法：

| 写法 | 例子 | 保证 |
| --- | --- | --- |
| 组合注解 | `@ConvertWith(SapDateConverter.class) @interface SapDate {}` | 用户自定义类型挂载，写法最短；Class 引用由编译期检查 |
| Class 字面量 | `@Column(value = "ERDAT", converter = SapDateConverter.class)` | 类改名或删除后编译失败 |
| 编译期字符串常量 | `@MapFrom(value = Lips.class, property = Lips.Fields.material)` | Lombok `@FieldNameConstants` 生成的常量；字段改名后编译失败 |

注解里放不了方法引用、lambda 或对象实例。"转换器的 Java 类型与字段类型一致"这一条，在首次扫描实体时检查。

### 查询：lambda 作用域

```java
import static org.dpdns.zerodep.query.hana.Hana.*;      // core + HANA functions in one import

Query<Delivery> q = from(Lips.class)
    .join(Mara.class, (l, ma) -> ma.col(Mara::getMaterial).eq(l, Lips::getMaterial))
    .leftJoin(Makt.class, (l, ma, mk) -> mk.col(Makt::getMaterial).eq(l, Lips::getMaterial))
    .where((l, ma, mk) -> all(
        l.col(Lips::getCreatedOn).between(start, end),
        ma.col(Mara::getManufacturer).eqIfPresent(maker)))
    .groupBy((l, ma, mk) -> keys(ma.col(Mara::getPartNumber)))
    .select((r, l, ma, mk) -> new Delivery(
        r.get(ma, Mara::getPartNumber),
        r.get(max(mk.col(Makt::getText).coalesce(ma.col(Mara::getPartNumber)))),
        r.get(sum(l.col(Lips::getQuantity)))));

List<Delivery> rows = db.list(q);
```

- `from(...)` 返回 `From1<A>`，之后每 `join` 一张表，作用域就多一个类型化参数，直到 `From8`。
- 这些类和对应的函数式接口由 `tools/` 下的脚本一次性生成后提交，不在使用方生成任何代码。
- 每个 lambda 参数都是 `Ref<E>`。`ref.col(getter)` 只接受该实体（或它实现的接口）的 getter，所以用了不在作用域里的表的列会编译失败。
- 自连接时，第二个实体就是下一个参数，不需要手写别名。
- 关联子查询直接捕获外层 lambda 的参数。

### 投影

| 写法 | 结果 | 适用场景 |
| --- | --- | --- |
| `select((r, a, b) -> new X(...))` | `Query<X>` | 默认写法：record、构造器、Builder、嵌套 DTO、Java 端计算都行，不需要反射映射 |
| `selectAs(VO.class)` | `Query<VO>` | 按同名属性自动从作用域里的表取列；改了名的属性用 `@MapFrom`，计算列在 `selectAs(VO.class, (a, b) -> keys(...))` 里用 `.as(VO::getX)` 指定 |
| `selectValue((a, b) -> expr)` | `Query<T>` | 单列查询，可以作为标量子查询或 `IN` 子查询 |

函数式投影的机制：

- 第一次运行 lambda 时记录它读取了哪些表达式，据此生成 SELECT 列表；之后每一行重放一次，填入真实值。
- 记录阶段返回的是无害的占位值，例如数值返回 0、字符串返回 `""`。
- 如果读取顺序随行值变化，例如在条件分支里读列，重放时会检测到并报错。

`selectAs` 产生的列带名字，所以只有它的结果能作为 CTE 或派生表；函数式投影的列没有名字，只能作为结果。

### 表达式与泛型规则

- `Expr<T>` 是抽象类，不是函数式接口，所以接收 getter 的重载永远不会产生歧义。`Cond` 是条件，独立于 `Expr<Boolean>`，因为 HANA 不能直接投影谓词。
- **T 类型相同的多参数运算必须是实例方法**，例如 `a.eq(b)`、`a.coalesce(b)`、`a.nullif(v)`、`a.in(list)`、`caseWhen(c, x).when(c2, y)`。
  - 原因是 javac 会把多个泛型实参推断成它们的公共父类型，`coalesce(col(a), col(b))` 在列类型不同时也能编译通过（已实测）。
  - 改成实例方法后，由接收者先固定 T，参数类型不符就会报错。
- 静态函数只用于两类：
  - 单参数的泛型函数，例如 `sum`、`max`、`trim`；
  - 参数类型写死的函数，例如 `daysBetween(Expr<LocalDate>, Expr<LocalDate>)`。
  这两类都没有推断成公共父类型的问题。
- 连接键写成 `ma.col(Mara::getMaterial).eq(l, Lips::getMaterial)`：由接收者固定类型，键的类型不同时编译失败。
- 动态条件：`eqIfPresent`、`inIfPresent`、`whereIf`、`onlyIf(test, () -> cond)`、`ifPresent(value, v -> cond)`。被省略的条件是 `Cond.EMPTY`，组合时会被丢弃。
- 参数与字面量：比较时传入的值默认绑定为参数，并使用左侧列的类型和转换器；`lit(x)` 表示直接内联为字面量。
- 原生 SQL 兜底：`sql(Integer.class, "DAYS_BETWEEN({0}, {1})", a, b)` 得到带类型的表达式。

### 复用

业务口径都是普通的 Java 函数：

```java
interface PeriodStock { String getClient(); String getYear(); String getPeriod(); String getMaterial(); }

static <E extends PeriodStock> Cond clientAndYear(Ref<E> h, String year) {
    return h.col(PeriodStock::getClient).eq(CLIENT).and(h.col(PeriodStock::getYear).eq(year));
}

static <E extends PeriodStock> Query<StockRow> arm(Class<E> table, Function<Ref<E>, Expr<BigDecimal>> qty) {
    return from(table).where(h -> clientAndYear(h, year))
        .select((r, h) -> new StockRow(r.get(h, PeriodStock::getMaterial), r.get(qty.apply(h))));
}

Stream.of(arm(Mardh.class, h -> h.col(Mardh::getUnrestricted)), arm(Mchbh.class, h -> h.col(Mchbh::getBatchQty)))
      .reduce(Query::unionAll);
```

- 实体通过 Lombok 生成的 getter 实现共享接口，泛型约束 `<E extends PeriodStock>` 保证模板只能套用到有这些列的表上。
- 常用的连接条件写成静态函数，例如 `BiFunction<Ref<Lips>, Ref<Mara>, Cond>`，在需要时调用即可。
- UNION 的各段必须产生相同的结果类型，类型不同时编译失败。

### 方言

```java
HanaDatabase db = Hana.database(dataSource, config -> config
    .filter(ClientEntity.class, r -> r.col(ClientEntity::getClient).eq("100"))
    .cache(QueryCache.inMemory(Duration.ofMinutes(5))));
```

- `Hana` 门面继承核心包的 `Sql`，所以一次静态导入就能同时拿到核心函数和 HANA 函数。
- 方言专属的函数和子句只出现在对应的门面里，例如 `Hana.daysBetween`、`.with(Hana.hint("NO_CS_JOIN"))`、`Postgres.distinctOn(...)`。
- 在另一种方言上渲染这些函数或子句时，会报出明确的错误，指明它属于哪个方言。
- 子句的方言检查放在渲染时，不做成泛型参数：探针证明编译期检查可以做到，但每个查询类型都要多带一个方言参数，代价超过收益。
- 方言是开放的类：
  - 函数渲染按"函数定义 + 方言"注册；
  - 内置的方言知识随函数定义一起提供；
  - 用户覆盖时在方言副本上进行。

### 执行

- `Database` 是每个方言各自的入口，提供以下方法：
  - `list`、`one`、`first`、`stream`、`forEach`
  - `page(q, page, size)`、`count`
  - `sql("... :name").bind(...).list(Class)`
- **全局过滤器**：`filter(Type.class, ref -> cond)` 会作用于所有实体类型可赋值给 `Type` 的表来源。
  - FROM 中的表，过滤条件追加到 WHERE；JOIN 的表，追加到 ON，以保持 LEFT JOIN 的语义。
  - 单个查询可以用 `.unfiltered()` 关闭。
  - 典型用途是 SAP 每张表都要带的客户端条件。
- `IN` 列表超过方言上限时，自动拆成 `(c IN (...) OR c IN (...))`。
  - 空列表渲染成恒假条件；`NOT IN` 空列表渲染成恒真条件。
  - `inIfPresent` 遇到空列表时直接省略该条件。
- 连接只通过 `ConnectionProvider` 获取，核心包不依赖 Spring。

### 扩展点

| 扩展 | 方式 |
| --- | --- |
| 值类型 | `Converter<J, D>`，配合组合注解 `@ConvertWith` 或 `@Column(converter)` 使用；也可以在 `TypeRegistry` 中按 Java 类型注册默认读写 |
| 函数 | 静态方法，内部用 `FunctionDef.of(name)` 或 `sql(type, template, args)` 构造表达式；需要时为各方言分别注册渲染 |
| 表达式节点 | 继承 `Expr<T>` 并实现 `render(SqlWriter)` |
| 子句 | 实现 `Clause`，声明所属方言和插入位置 |
| 方言 | 继承 `Dialect`，覆盖引号、分页、字面量、类型名、函数渲染和 JDBC 读写 |
| 全局过滤器 | `config.filter(Type.class, ref -> cond)` |
| 结果缓存 | 实现 `QueryCache` SPI，可接入 Caffeine、Redis 等 |
| 监听 | 实现 `QueryListener`，用于日志、耗时统计和慢 SQL 记录 |
| 连接 | 实现 `ConnectionProvider` |

### 实例化、懒加载与缓存

| 对象 | 何时计算 | 缓存位置 |
| --- | --- | --- |
| 实体元数据（表名、列、类型、转换器） | 首次使用该类时 | `ClassValue`，跟随类的生命周期 |
| getter 引用解析（SerializedLambda） | 每个 lambda 类首次使用时 | `ClassValue`，按 lambda 类缓存 |
| 转换器实例 | 首次扫描到该转换器时 | 按转换器类缓存的单例 |
| 结果映射计划（构造器、setter、Builder 的 `MethodHandle` 以及数值转换） | 某个目标类与某组列标签首次配对时 | 按"目标类 + 列标签"缓存 |
| 渲染结果（SQL 与参数） | 查询首次在某个数据库实例上执行时 | 查询是不可变值，渲染结果记在查询对象上 |
| 查询结果（可选，`.cached(ttl)`） | 执行时 | 缓存的是解码后的原始行，而不是映射后的对象，因为投影 lambda 可能捕获了外部变量 |
| 大结果集 | `stream` 或 `forEach` 逐行处理，可设置 `fetchSize` | — |

库不提供实体的导航属性懒加载，因为本库不跟踪实体状态。"一对多"的结果组装放到第三期，届时以批量查询加内存分组的方式提供（见场景对照）。

## TypeScript（以 TS 7 为基准，使用标准装饰器）

```ts
@table("LIPS", { schema: "ERP" })
class Lips {
  @column("MANDT") client!: string;
  @column("MATNR") material!: string;
  @column("ERDAT", sapDate) createdOn!: LocalDate;   // converter type must equal the field type
  @column("LFIMG") quantity!: Decimal;
}

const db = hana.connect(pool, { filters: [filter(ClientEntity, (r) => r.client.eq("100"))] });
const rows = await db.list(
  from(Lips)
    .leftJoin(Makt, (l, mk) => mk.material.eq(l.material))
    .where((l) => l.createdOn.between(start, end))
    .select((l, mk) => ({ part: l.material, text: mk.text, stock: { qty: sum(l.quantity) } })),
);
// rows: { part: string; text: string | null; stock: { qty: Decimal | null } }[]
```

- **装饰器**：使用标准装饰器，不需要编译开关。`ClassFieldDecoratorContext<This, V>` 会检查转换器的类型和 `@mapFrom(Lips, "material")` 的键与字段类型是否一致。
  - TS 7 也支持旧版的 `experimentalDecorators` 加 `emitDecoratorMetadata`，能在运行时拿到字段类型；但它是非标准语义，联合类型只会记录成 `Object`，所以不采用。
- **列与作用域**：`Ref<E>` 是对 `keyof E` 的同态映射类型，所以泛型模板（`<E extends PeriodStock>`）里也能访问 `h.year`。
  - 作用域是一个元组：`leftJoin` 之后，该位置的 ref 自动变成可空的 `NullableRef<E>`，结果类型随之包含 `null`。
- **投影**：
  - 对象字面量可以嵌套，结果类型也随之嵌套；
  - `selectAs(VO)` 按同名字段自动取列；
  - `list(VO)` 把结果映射成类实例。
- **函数**：多参数同类型运算同样用实例方法；`` sql<number>`...` `` 是带类型的模板兜底。
- **方言**：方言函数和子句在渲染时检查，与 Java 一致。
- **复用片段**：写成普通函数 `(q: From<T>) => From<T>`，通过 `.pipe(...)` 组合。泛型片段里的回调必须写成 `(...[l]) =>`，这是 TS 的限制。
- **缓存**：元数据用 `WeakMap`，映射计划按类缓存，渲染结果记在查询对象上。

## 验证证据

均为 2026-10-21 的临时探针，Java 使用 JBR 17.0.14 和 Lombok 1.18.46，TypeScript 使用 7.0.2：

| 假设 | 结果 |
| --- | --- |
| getter 引用可以取代 APT 代理 | 成立。SerializedLambda 解析未缓存约 620 ns，缓存后约 22 ns；继承自基类的 getter 通过 `instantiatedMethodType` 正确解析到子类 |
| lambda 作用域保证表成员关系 | 成立。用了作用域外的表的 getter、连接键类型不符、投影类型不符、UNION 两段结果类型不同、泛型模板的类型约束不满足，都会编译失败 |
| 函数式投影可以取代反射映射 | 成立。record、Builder、嵌套 record、Java 端计算都能构造；条件读取在重放时被拒绝 |
| 结果按名映射到多种目标类 | 成立。同一个查询可以映射成 record、`@Data`（链式 setter）、`@Builder @Value` 三种类型 |
| 静态的多参数泛型函数是类型安全的 | 不成立。`coalesce(col(a), col(b))`、`eq(Getter, Getter)` 会被推断成公共父类型而通过编译。改为实例方法后，类型不符会报错 |
| 注解里可以放强类型引用 | 部分成立。组合注解、Class 字面量、Lombok 字段名常量都可以；常量拼错时编译失败 |
| TS 装饰器能检查字段类型 | 成立。转换器类型与字段类型不符、`@mapFrom` 的键拼错、源字段与 DTO 字段类型不符，都会编译失败 |
| TS 能跟踪 LEFT JOIN 的可空性 | 成立。按元组位置跟踪；嵌套投影的结果类型也正确 |
| HANA 2.00.088 的语法边界 | `VALUES` 表构造、`QUALIFY`、`GROUP BY` 别名、行值比较、`IS DISTINCT FROM`、`LATERAL` 均不可用；`GROUPING SETS`、派生表内的 `WITH`、多 CTE 加标量子查询可用。不可用的几项尚未拿到错误码，写入方言前再用 JDBC 复核 |

## 代价与风险

- **SerializedLambda 依赖私有的 `writeReplace`**：
  - classpath 上没有问题；JPMS 命名模块需要 `opens` 声明；GraalVM 原生镜像需要额外的序列化配置。
  - 实体元数据和映射计划用到的反射同样如此。
- **函数式投影的代价**：
  - lambda 不能有副作用，也不能按行值有条件地读列；
  - 每一行都会重跑一次 lambda，有分配开销。
- **作用域最多 8 张表**。更多表时，先用 CTE 或派生表把查询拆开。
- **方言专属函数在渲染时才报错，不在编译期报错**。用测试兜底：每个方言包都有"在别的方言上渲染应当失败"的测试。
- **Java 不跟踪可空性**，结果类型都用包装类型。如果 record 组件是基本类型而值为 NULL，报错会指出具体是哪个属性。

## 分期

1. **第一期（本次）**：
   - Java 的 `query-core`、`query-hana`、`query-postgres`，覆盖上文所有 Java 能力；
   - 渲染的单元测试，以及在开发库上执行的集成测试（实库不可达时跳过）。
2. **第二期**：TypeScript 的 core、hana、postgres，与 Java 对齐；建立 `spec/` 金样例，两端测试共同读取。
3. **第三期**：
   - MySQL 方言、Spring 适配；
   - 方言扩展：分组集合、HANA 计算视图与表函数、JSON；
   - SQL 级列转换；一对多的批量组装；
   - 根据请求对象注解生成条件（对应 EasyQuery 的 `whereObject`）。
4. **之后**：写入（insert、update、upsert）与 DuckDB。

场景的逐项对照见[场景对照](../scenarios.md)。

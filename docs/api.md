# API 使用说明（Java）

本文是 2.0 Java API 的用法。设计理由见 [0002](decisions/0002-api-architecture.md) 和 [0006](decisions/0006-query-model-direction.md)；从 1.x 升级见 [迁移指南](migration.md)。文中示例都能在测试里找到可运行的版本：`java/query-core/src/test/java/.../testing/`（`Erp` 是表声明，`Scenarios`、`SpecCases` 是查询）以及三个方言模块的 `*LiveTest`。示例 SQL 取自测试：schema 写作 `ERP`，别名是测试方言的大写形式（`LIPS`），真实方言的引号和别名写法见 `spec/render/` 的金样例。

TypeScript 版本见 [API 使用说明（TypeScript）](api-typescript.md)，两端渲染出的 SQL 由金样例保证一致。

## 速查

`a`、`b` 代表列表达式，例如 `l.material()`；`l`、`m` 是 lambda 的参数，每张表（或 CTE）一个。

| SQL | 写法 |
| --- | --- |
| `FROM LIPS l JOIN MARA m ON m.MATNR = l.MATNR` | `from(Lips.class).join(Mara.class, (l, m) -> m.material().eq(l.material()))` |
| `WHERE a = ? AND b BETWEEN ? AND ?` | `.where(l -> all(a.eq(x), b.between(low, high)))` |
| 参数为空时不加这个条件 | 作用域 `.skipEmpty()`；单个条件用 `ifPresent(x, v -> ...)`、`onlyIf(flag, () -> ...)`；整个请求对象用 `.where(search)` |
| `LEFT JOIN MARA` 只为取几列 | 表接口声明关系 `mara()`，查询里写 `l.mara().partNumber()`，用到才连接 |
| `EXISTS (SELECT 1 FROM 子表 WHERE ...)`、`COUNT(*)` 子查询 | `m.deliveries().any()`、`m.deliveries().count()` |
| `GROUP BY a, b`、`ORDER BY a, b DESC` | `.groupBy(l -> keys(a, b))`、`.orderBy(l -> orders(a, b.desc()))` |
| `SELECT` 命名列（Map） | `.select(l -> cols(l.material(), sum(l.quantity()).as("total")))` |
| `SELECT` 构造对象 | `.select(l -> row(Delivery::new, l.material(), sum(l.quantity())))` |
| `SELECT` 同名列到 VO（含对象树） | `.selectAs(Dto.class)` |
| 父行带子行列表 | `m.deliveries().list(l -> row(Line::new, ...))` |
| `WITH x AS (...)` | `Cte<X> x = cte("x", query.into(X.class))`，然后 `from(x)` |
| `UNION ALL` | `q1.unionAll(q2)` |
| `ROW_NUMBER() OVER (PARTITION BY a ORDER BY b DESC)` | `rowNumber().over(w -> w.partitionBy(a).orderBy(b.desc()))` |
| 每组取前 N 行（`QUALIFY`） | `.qualify(l -> rowNumber().over(w -> w.partitionBy(a).orderBy(b.desc())).le(3L))` |
| 原生 SQL 片段 | `sql(String.class, "LPAD({0}, {1}, '0')", l.material(), 18)` |
| 执行 | `db.list(q)`、`db.one(q)`、`db.first(q)`、`db.page(q, 1, 20)`、`db.stream(q)` |

## 1. 依赖与数据库实例

选定方言，引入对应的方言包。方言包会传递依赖核心包，JDBC 驱动由使用方自己提供。

```xml
<dependency>
    <groupId>org.dpdns.zerodep</groupId>
    <artifactId>query-hana</artifactId>      <!-- 或 query-postgres、query-mysql -->
    <version>2.0.0</version>
</dependency>
```

每个数据源创建一个方言实例，作为长期存在的单例使用：

```java
import static org.dpdns.zerodep.query.hana.Hana.*;   // 一次导入：核心函数加 HANA 函数

HanaDatabase db = Hana.database(dataSource, c -> c
    .filter(Client.class, t -> t.client().eq("100"))   // 全局过滤器，按表接口的父接口匹配
    .cache(QueryCache.inMemory())                      // 结果缓存（按查询开启）
    .printSql()                                        // 打印每条语句，见第 11 节
    .listener(e -> log.debug("{} {}ms", e.statement().sql(), e.elapsed().toMillis()))
    .queryTimeout(Duration.ofSeconds(30))              // 每条语句的默认时限，超时由数据库取消
    .naming(NamingStrategy.UPPER_SNAKE));              // 没写 @Column 的列按此从方法名推导列名
```

| 方言 | 门面（静态导入） | 实例 |
| --- | --- | --- |
| SAP HANA | `org.dpdns.zerodep.query.hana.Hana` | `HanaDatabase` |
| PostgreSQL | `org.dpdns.zerodep.query.postgres.Postgres` | `PostgresDatabase` |
| MySQL 8 | `org.dpdns.zerodep.query.mysql.Mysql` | `MysqlDatabase` |

`database(...)` 接收 `DataSource` 或自定义的 `ConnectionProvider`。`Config` 还有 `function`、`type`（覆盖函数渲染与类型读写，见第 12 节）、`fetchSize`、`name`。

**在 Spring 中使用**：引入 `query-spring`（版本同上），用 `SpringConnections.of(dataSource)` 取连接：

```java
@Bean
HanaDatabase hana(@Qualifier("sapHana") DataSource dataSource) {
    return Hana.database(SpringConnections.of(dataSource), c -> c.filter(...));
}
```

- 它通过 `DataSourceUtils` 取连接，所以在 `@Transactional` 方法里，查询使用事务的连接：能看到同一事务里 MyBatis、JdbcTemplate 尚未提交的写入，也随事务一起回滚（`QueryExecutionException` 是运行时异常）。不在事务里时，每条语句借一个连接，用完归还。
- `@Transactional(timeout = 30)` 的事务里，每条语句的时限是事务剩余的时间，优先于 `queryTimeout`；时间已经用完时直接抛出 `TransactionTimedOutException`。
- 路由型数据源可以直接传入。模块只依赖 spring-jdbc（`provided`）：5.3、6.2、7.0 都在 CI 中验证。

## 2. 声明表

表是带 `@Table` 的接口，继承 `Entity`；列是返回 `Col<T>` 的无参抽象方法，方法名就是列在结果里的名字。库为查询里的每次表出现生成该接口的实现，所以 lambda 里直接写 `l.material()`。接口不依赖 Lombok，也不需要代码生成。

```java
/** 各表共有的集团列放进不带 @Table 的基接口，全局过滤器按它匹配。 */
public interface Client extends Entity {
    @Column("MANDT") Col<String> client();
}

@Table(schema = "ERP", value = "LIPS")
public interface Lips extends Client {
    @Column("VBELN") Col<String> delivery();
    @Column("MATNR") Col<String> material();
    @Column("ERDAT") @SapDate Col<LocalDate> createdOn();      // 组合注解挂载转换器
    @Column("LFIMG") Col<BigDecimal> quantity();

    /** default 方法放计算列和关系，同一次表出现上的结果会被记住。 */
    default Expr<BigDecimal> doubled() { return times(quantity(), lit(2)); }
}
```

- 不写 `@Column` 的列由数据库的命名策略从方法名推导。`l.columns()` 是这张表的全部列，配合 `cols(l.columns(), 额外列...)` 使用。
- 子接口重新声明某个列方法并带上自己的 `@Column`，就改了这一列的列名：库存历史的共同形状 `PeriodStock` 声明 `Col<BigDecimal> quantity()`，`Mardh` 和 `Mchbh` 各自映射到不同的数量列。
- 抽象方法既不是 `Col<T>` 也不是声明了注解的关系时，首次使用时报错。接口可以不是 public；在具名模块里，要把包 `opens` 给 `org.dpdns.zerodep.query`，default 方法才能被调用。
- 结果类、CTE 行、常量行、派生表行是不带 `@Table` 的普通类：record、Lombok `@Data`（含链式 setter）或 `@Builder`。把表声明成类或 record 会报错。

| 注解 | 作用 |
| --- | --- |
| `@Table(value, schema)` | 标记表接口 |
| `@Column(value, converter)` | 列名；可选的转换器 |
| `@ConvertWith(Converter.class)` | 元注解，用来组合出自己的类型注解，例如 `@SapDate` |
| `@ReadAs("NULLIF({0}, '')")` | SQL 级列转换：列在语句的每个位置都按模板读取，`{0}` 是带别名的列；也可作元注解 |
| `@BlankAsNull`、`@Trimmed` | 内置的列转换：空串读作 NULL（`NULLIF(col, '')`）、去掉首尾空格（`TRIM(col)`） |
| `@ManyToOne`、`@OneToMany` | 关系的简写，见第 3 节 |
| `@Ignore` | 结果类、行类或条件对象里不参与映射的字段 |
| `@Where(value, op)` | 条件对象的字段怎样变成条件，见第 7 节 |

自定义类型：实现 `Converter<J, D>`（`toDatabase`、`fromDatabase`），再声明一个组合注解：

```java
@Retention(RUNTIME) @Target(METHOD) @ConvertWith(SapDateConverter.class)
public @interface SapDate { }
```

列的值与比较时的参数都会经过转换器，例如 `l.createdOn().between(start, end)` 绑定的是 `'20260101'`。

**SQL 级列转换**：转换器只在 Java 端改值，数据库看到的仍是原值；列转换写进 SQL，分组、聚合和条件看到的都是转换后的值。

```java
@Table(schema = "ERP", value = "MARA")
public interface MaraView extends Client {
    @Column("MATNR") Col<String> material();
    @Column("MFRPN") @Trimmed Col<String> partNumber();        // TRIM(MARAVIEW.MFRPN)
    @Column("MFRNR") @BlankAsNull Col<String> maker();         // NULLIF(MARAVIEW.MFRNR, '')
    @Column("MFRNR") Col<String> storedMaker();                // 同一列的原值
}
```

- `maker().eq("X")` 渲染为 `NULLIF(…, '') = ?`，比较值仍然绑定为参数。
- 一个列方法只能有一个读取模板，模板必须包含 `{0}`，否则读取元数据时报错。
- 需要原值时再声明一个映射到同一列的方法。条件写在原值上，数据库才能用上这一列的索引。

## 3. 关系

关系写成表接口的 default 方法，查询里直接导航，不再手写 JOIN。

```java
@Table(schema = "ERP", value = "MARA")
public interface Mara extends Client {
    @Column("MATNR") Col<String> material();
    @Column("MFRPN") Col<String> partNumber();
    @Column("MFRNR") Col<String> manufacturer();

    /** 一对多：这个物料的交货行。 */
    default Many<Lips> deliveries() { return many(Lips.class, l -> all(l.material().eq(material()), l.client().eq(client()))); }

    /** 多对一：语言 1 的物料描述。 */
    default Makt text() { return one(Makt.class, t -> all(t.material().eq(material()), t.client().eq(client()), t.language().eq("1"))); }
}

public interface Lips extends Client {
    default Mara mara() { return one(Mara.class, m -> all(m.material().eq(material()), m.client().eq(client()))); }
    // text() 同 Mara.text()

    // 注解写法：按列方法名配对做等值连接，self 与 target 一一对应，可以多列
    @OneToMany(self = "salesOrder", target = "salesOrder") Many<Vbkd> salesData();
    @ManyToOne(self = {"client", "material"}, target = {"client", "material"}) Makt anyText();
}
```

**多对一 / 一对一：`one(Target.class, t -> on)`** 返回目标表，可以取列，也可以继续导航。

```java
from(Lips.class)
    .where(l -> l.mara().manufacturer().eq("MK1"))
    .orderBy(l -> l.mara().partNumber())
    .select(l -> cols(l.delivery(), l.mara().partNumber(), l.text().text()));
```

```sql
SELECT LIPS.VBELN AS "delivery", MARA.MFRPN AS "partNumber", MAKT.MAKTX AS "text"
FROM ERP.LIPS LIPS
LEFT JOIN ERP.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
LEFT JOIN ERP.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
WHERE MARA.MFRNR = ?
ORDER BY MARA.MFRPN ASC
```

- 总是 LEFT JOIN，没有匹配时目标表的列为 NULL。
- 只有被用到的关系才会出现在 SQL 里；同一个关系在一条查询里只连接一次（`l.mara()` 写多少次都一样）。`skipEmpty()` 把条件省略后，只被这个条件用到的关系也不会连接。
- 全局过滤器对被连接的表加到它的 ON 里，LEFT JOIN 的语义不变。

**一对多：`many(Target.class, t -> on)`** 返回 `Many`，不做连接（那会让主查询的行重复），只能通过集合运算使用：

| 写法 | 渲染 |
| --- | --- |
| `m.deliveries().any()`、`any(l -> cond)` | `EXISTS (...)` |
| `m.deliveries().none()`、`none(l -> cond)` | `NOT EXISTS (...)` |
| `count()`、`countDistinct(l -> l.salesOrder())`、`sum(l -> l.quantity())`、`max`、`min`、`avg` | 相关标量子查询，没有行时 `sum` 等为 NULL |
| `stringAgg(l -> l.delivery(), ",")` | 相关标量子查询拼接文本，顺序取 `orderBy(...)`：`m.deliveries().orderBy(l -> l.delivery()).stringAgg(...)` |
| `where(l -> cond)` | 先筛选目标行，可接上面任一运算：`m.deliveries().where(l -> l.unit().eq("KG")).none()` |
| `first(l -> l.createdOn().desc())` | 每组按顺序取第一行，返回那一行的表接口，可继续取列 |
| `orderBy(l -> l.delivery())`、`limit(n)`、`list(projection)` | 子行列表，见第 6 节；`limit(n)` 每个父行只取按顺序的前 n 行（批量查询里用 ROW_NUMBER 按父键分区） |

```java
from(Mara.class)
    .where(m -> m.deliveries().any(l -> l.quantity().gt(BigDecimal.ONE)))
    .select(m -> cols(m.material(), m.deliveries().count().as("lines"), m.deliveries().sum(l -> l.quantity()).as("total"),
        m.deliveries().first(l -> l.createdOn().desc()).delivery().as("latest")));
```

`first` 渲染为 `ROW_NUMBER` 派生表再 LEFT JOIN 回来（HANA 没有 LATERAL，三个库都用这一种写法），没有匹配行时各列为 NULL。`first` 和 `list` 要求连接条件里至少有一个“目标列 = 本表的列”的等值，这些列作为分组键或批量加载的键，其余条件只筛选目标行；没有时报错。

**合成一个分组连接（`groupJoin`）**：默认每个 `count`、`sum`、`any` 等都是一个相关子查询。同一关系上的运算较多、父表较大时（HANA 尤其明显），在作用域上写 `groupJoin(m -> m.deliveries())`，这些运算合成一个按连接键分组的派生表，只 LEFT JOIN 一次：

```java
from(Mara.class)
    .groupJoin(m -> m.deliveries())
    .where(m -> m.deliveries().any(l -> l.unit().eq("EA")))
    .select(m -> cols(m.material(), m.deliveries().count().as("lines"), m.deliveries().sum(l -> l.quantity()).as("total")));
// LEFT JOIN (SELECT MATNR AS k1, MANDT AS k2, COUNT(*) AS c1, SUM(LFIMG) AS c2, COUNT(CASE WHEN VRKME = 'EA' THEN 1 END) AS c3
//            FROM LIPS GROUP BY MATNR, MANDT) mara_g ON ...
// SELECT COALESCE(mara_g.c1, 0), mara_g.c2 ... WHERE COALESCE(mara_g.c3, 0) > 0
```

关系上的 `where` 筛选写成各自聚合的 `CASE WHEN`；行数和 `any`/`none` 在没有子行时按 0 处理，`sum` 等仍为 NULL；`stringAgg`、`first`、`list` 不参与合并。结果与相关子查询相同，由金样例 `group-join` 和三库实库场景核对。

## 4. 写查询

`from` 开启一个作用域，每 `join` 一张表，lambda 就多一个参数（最多 16 个来源）。表的参数是表接口本身，CTE、派生表等来源的参数是 `Ref`（第 8 节）。

```java
Query<Map<String, Object>> q = from(Lips.class)
    .skipEmpty()                                                            // 其后的条件遇到空值就省略
    .join(Mara.class, (l, ma) -> ma.material().eq(l.material()))
    .leftJoin(Makt.class, (l, ma, mk) -> mk.material().eq(l.material()).and(mk.language().eq("1")))
    .where((l, ma, mk) -> all(
        l.createdOn().between(start, end),
        ma.manufacturer().eq(maker),                                        // maker 为 null 时省略
        exists(from(Vbkd.class).where(v -> v.salesOrder().eq(l.salesOrder())))))
    .groupBy((l, ma, mk) -> ma.partNumber())
    .orderBy((l, ma, mk) -> ma.partNumber())
    .select((l, ma, mk) -> cols(ma.partNumber(), max(mk.text()).as("name"), sum(l.quantity()).as("total")));

List<Map<String, Object>> rows = db.list(q);
```

作用域上的方法：

| 方法 | 说明 |
| --- | --- |
| `join` / `leftJoin(表接口.class 或 Source, (…) -> cond)` | 连接条件里可以用到作用域内所有的表；`crossJoin(...)` 是笛卡尔积 |
| `where(… -> cond)`、`having(… -> cond)` | 条件；多次调用之间是 AND，空条件（`Cond.EMPTY`）自动丢弃 |
| `where(searchObject)` | 按条件对象筛选，见第 7 节 |
| `skipEmpty()` | 其后的 WHERE、HAVING、ON 里，与 null、空白字符串或空集合比较的条件直接省略；`between` 只缺一端时留下另一端 |
| `groupBy(… -> 键)` | 一个键直接写表达式；多个键写 `keys(a, b)` |
| `orderBy(… -> 排序)` | 一个表达式按升序排，`.desc()` 降序，`.nullsFirst()` / `.nullsLast()` 指定 NULL 的位置；多项写 `orders(a, b.desc())` |
| `groupByRollup(… -> 键)` | 分组并加上各级小计和总计；一次列出全部分组键，不能与 `groupBy` 同用 |
| `distinct()`、`limit(n)`、`offset(n)` | — |
| `unfiltered()` | 本查询不加全局过滤器 |
| `with(clause)` / `with(… -> clause)` | 方言子句，例如 `Hana.hint(...)`、`Postgres.distinctOn(...)` |

- 与 null 比较在 `skipEmpty()` 之外是错误（比较永远不成立，多半是漏传了参数）；确实要判空用 `isNull()`。单个条件也可以不开 `skipEmpty`，写成 `ifPresent(x, v -> ...)` 或 `onlyIf(flag, () -> ...)`。
- 自连接时，同一个表接口再 `join` 一次就是下一个参数，别名自动唯一。
- 关联子查询直接捕获外层 lambda 的参数；`exists` / `notExists` 直接接收作用域，不用写 SELECT 列表。
- 两列可以直接比较：`a.eq(m.material())`，`ne`、`gt`、`ge`、`lt`、`le` 同样可以。

**小计与总计**：

```java
from(Lips.class)
    .groupByRollup(l -> keys(l.material(), l.unit()))
    .orderBy(l -> orders(grouping(l.material()), l.material(), grouping(l.unit())))
    .select(l -> cols(l.material(), l.unit(), sum(l.quantity()).as("total"), grouping(l.material()).as("level")));
```

- HANA 和 PostgreSQL 渲染 `GROUP BY ROLLUP(a, b)`，MySQL 渲染 `GROUP BY a, b WITH ROLLUP`。汇总行里被汇总掉的列读出 NULL；`grouping(col)` 在汇总行为 1，用来区分汇总行和本身为 NULL 的值，也用来把小计排在明细之后。
- 任意组合的分组用 HANA、PostgreSQL 门面里的 `cube(...)` 和 `groupingSets(...)`，见第 9 节。

## 5. 投影

`select(...)` 的 lambda 返回下面三者之一，`selectAs` 按同名属性取列：

| 写法 | 结果 | 用途 |
| --- | --- | --- |
| `select(l -> expr)` | `Query<T>` | 单列；也可以作为标量子查询或 `IN` 子查询 |
| `select(l -> cols(a, b.as("x")))` | `Query<Map<String, Object>>` | 按列名选列，名字取列方法名；再 `.into(X.class)` 按名映射到类 |
| `select(l -> row(X::new, a, b))` | `Query<X>` | 按位置调用构造器（最多 22 个参数），javac 逐位检查类型；参数可以嵌套 `row(...)`、`cols(...)` |
| `selectAs(VO.class)` | `Query<VO>` | VO 的每个属性取作用域里恰好一张表的同名列；有对象树时见第 6 节 |

```java
record Delivery(String partNumber, String name, BigDecimal quantity) { }

from(Lips.class)
    .join(Mara.class, (l, ma) -> ma.material().eq(l.material()))
    .groupBy((l, ma) -> ma.partNumber())
    .select((l, ma) -> row(Delivery::new, ma.partNumber(), max(l.unit()).as("name"), sum(l.quantity()).as("quantity")));

// row 的第一个参数也可以是普通 lambda，做 Java 端计算，参数顺序与后面的列一致
row((quantity, part) -> new PartTotal(part, new Amount(quantity, quantity.doubleValue() / 10_000)), sum(l.quantity()), ma.partNumber())
```

**`cols` 的列名**：列取列方法名；只包着一列的函数或聚合沿用该列名（`sum(l.quantity())` 叫 `quantity`）；其他表达式必须写 `.as("name")`。缺名字或名字重复（不分大小写）时立刻报错。

**映射到类**：`into(X.class)` 按列名映射，名字忽略大小写和下划线（`PART_NUMBER` 对应 `partNumber`）。类缺少的列被忽略，类的属性找不到列时报错；数值类型宽松转换（BigDecimal 可以赋给 Double）。目标可以是 record、`@Data`（含链式 setter）、`@Builder` 或标量，字段上的 `@Ignore` 跳过映射。按名的查询会把列重排成类的属性顺序，所以结果可以做 CTE 或派生表。

```java
Query<Map<String, Object>> q = from(Lips.class)
    .leftJoin(Makt.class, (l, mk) -> mk.material().eq(l.material()).and(mk.language().eq("1")))
    .select((l, mk) -> cols(l.delivery(), l.material().as("materialNo"), l.createdOn(), mk.text()));

Query<LineVO> lines = q.into(LineVO.class);
Query<OtherVO> other = q.into(OtherVO.class);          // 同一条 SQL 映射成不同的类
Query<String> texts = lines.map(v -> v.getText());      // 在映射结果上再套一层 Java 函数
```

**`selectAs`**：属性在作用域的表里找不到同名列，或同时出现在多张表里，都会立刻报错并提示改用 `cols(...).into(VO.class)`。关系目标表的列不参与同名匹配。

## 6. 对象树与子列表

### 子列表

`many.list(projection)` 放进 `row(...)`，每个父行得到一个 `List<子行>`：

```java
record Line(String delivery, BigDecimal quantity) { }
record MaterialLines(String material, List<Line> lines) { }

Query<MaterialLines> q = from(Mara.class)
    .orderBy(m -> m.material())
    .select(m -> row(MaterialLines::new, m.material(),
        m.deliveries().orderBy(l -> l.delivery()).list(l -> row(Line::new, l.delivery(), l.quantity()))));
```

父查询只多取连接键，不连接子表；执行时用一条按键批量的查询取出子行，再分给各父行：

```sql
-- 父查询
SELECT MARA.MATNR AS "material", MARA.MATNR AS "material_2", MARA.MANDT AS "client"
FROM ERP.MARA MARA
ORDER BY MARA.MATNR ASC
-- 子查询（两个父行时；每 500 个父键一批）
SELECT LIPS.VBELN AS "delivery", LIPS.LFIMG AS "quantity", LIPS.MATNR AS "material", LIPS.MANDT AS "client"
FROM ERP.LIPS LIPS
WHERE ((LIPS.MATNR = ? AND LIPS.MANDT = ?) OR (LIPS.MATNR = ? AND LIPS.MANDT = ?))
ORDER BY LIPS.VBELN ASC
```

- N 个父行只多一条 SQL；单列键用 `IN (?, ...)`，多列键用 `(a = ? AND b = ?) OR ...`；重复的键只查一次，没有子行的父行得到空列表。
- 子投影里还可以再有 `list(...)`，每一层多一条语句。
- 带子列表的查询要读完全部父行才能加载子行：`db.stream` 抛出 `UnsupportedOperationException`，不能参与 UNION（`IllegalStateException`）；`list`、`page`、`one`、`first` 照常使用。`only(...)` 不会去掉子列表的键。

### 对象树

`selectAs` 的 VO 里，类型是另一个 VO 的属性取同名的多对一关系，`List<X>` 属性取同名的一对多关系（子行同样由 `list(...)` 批量加载），逐层按同样的规则映射：

```java
record Line(String delivery, BigDecimal quantity) { }          // 取 Lips 上的同名列
record Text(String text) { }                                    // 取 Makt 上的同名列
record MaterialTree(String material, Text text, List<Line> deliveries) { }   // text 对应 Mara.text()，deliveries 对应 Mara.deliveries()

Query<MaterialTree> q = from(Mara.class).orderBy(m -> m.material()).selectAs(MaterialTree.class);
```

多对一没有匹配时嵌套对象的属性为 null。缺少同名关系时报错（`Wrong.lines has no relation of the same name in the query's tables`）。属性对应的列、计算列或更深的关系，都按目标表接口上的同名方法查找。

## 7. 动态列、排序与条件对象

### 动态列与排序

同一个查询按用户的选择取不同的列、不同的排序，不必为每种组合另写一份：

```java
Query<Map<String, Object>> totals = from(Lips.class)
    .groupBy(l -> keys(l.material(), l.unit(), substring(l.delivery(), 1, 1)))
    .orderBy(l -> orders(l.material().asc(), l.unit().asc()))
    .select(l -> cols(l.material(), l.unit(), substring(l.delivery(), 1, 1).as("prefix"), sum(l.quantity()).as("total")));

totals.exclude("unit", "prefix")             // 这些列写成 NULL，分组键和按它的排序一起去掉
totals.excludeIf(!withUnit, "unit")           // 条件成立才排除
totals.only(req.columns())                    // 只取这些列；null 或空集合保持不变
totals.sortBy("total desc, material")         // 按排序串替换原来的 ORDER BY
```

- 被排除的列在 SQL 里是 `NULL`，结果的形态不变（`row` 的对应参数收到 null）；只被它们用到的关系不再连接。有分组时，与它们相同的分组键一并去掉，于是同一个查询可以按用户选的维度汇总。
- 列名就是 `q.labels()`，不分大小写和下划线；不存在的列名报错并列出可用的列。
- `sortBy` 只认结果里的列名和 `asc` / `desc`，等于白名单，所以可以直接传入用户的排序参数；未知列名、被排除的列或其他写法都会报错。`null` 或空白保持原来的排序。UNION 之后不能直接 `sortBy`，先包成 CTE。

### 条件对象

请求参数写成一个 record（或普通类），`where(search)` 把每个字段变成条件：

```java
public record DeliverySearch(
    List<String> material,                                                    // MATNR IN (...)
    @Where(value = "createdOn", op = Where.Op.GE) LocalDate from,             // ERDAT >= ?
    @Where(value = "createdOn", op = Where.Op.LT) LocalDate to,               // ERDAT < ?
    @Where(value = {"mara.partNumber", "text.text"}, op = Where.Op.CONTAINS) String keyword,   // 任一列包含
    @Where(op = Where.Op.NE) String unit,                                     // VRKME <> ?
    @Ignore String sort) { }                                                  // 不作为条件

from(Lips.class).where(search).select(l -> l.delivery());
```

- 没有 `@Where` 的字段与同名列比较相等，集合用 `IN`。`op` 取 `EQ`、`NE`、`GT`、`GE`、`LT`、`LE`、`CONTAINS`、`STARTS_WITH`、`ENDS_WITH`；集合值只能配 `EQ`（`IN`）和 `NE`（`NOT IN`）。
- `value` 是列路径，默认是字段名；可以经过多对一关系（`"mara.partNumber"`）；写多个时任一满足即可（OR）。
- 路径经过一对多关系时写成 EXISTS，例如物料查询里的 `@Where("deliveries.unit") String unit` 和 `@Where(value = "deliveries.quantity", op = Where.Op.GE) BigDecimal minQuantity`：只写一条路径的字段按同一个一对多关系合并进一个 EXISTS，即要求同一个交货行同时满足；这些字段的值都为空时整个 EXISTS 省略。
- 值为 null、空白字符串或空集合的字段不产生条件，对应的关系也不会连接；所以可以直接传入请求参数。
- 字段名在表里找不到、指向的是关系而不是列、或在多张表里重名时，无论有没有值都报错；不是条件的字段（分页、排序）标 `@Ignore`。
- 条件对象可以与 lambda 条件混用：`from(Lips.class).where(search).where(l -> l.quantity().gt(BigDecimal.ONE))`，多次 `where` 之间是 AND。

文本匹配也可以直接写在 lambda 里：`contains(e, text)`、`startsWith(e, text)`、`endsWith(e, text)` 渲染为 `LIKE ? ESCAPE '!'`，`text` 里的 `%`、`_`、`!` 按普通字符匹配，三个库的写法相同；`skipEmpty` 下 `text` 为空时没有条件。`like(e, pattern)` 的模式原样使用，`%` 和 `_` 是通配符。

## 8. CTE、派生表、UNION、窗口

CTE、派生表和常量行的行结构由一个普通类（通常是 record）描述；它们在 lambda 里是 `Ref`，用 `r.col(Row::x)` 取列。

```java
record MaterialScope(String client, String material, String partNumber) { }

Cte<MaterialScope> scope = cte("material_scope", from(Mara.class)
    .skipEmpty()
    .where(ma -> all(ma.materialType().eq("ZMAT"), ma.manufacturer().eq(maker)))
    .distinct()
    .select(ma -> cols(ma.client(), ma.material(), upper(ma.partNumber()).as("partNumber")))
    .into(MaterialScope.class));

from(scope)
    .join(Lips.class, (ms, l) -> l.client().eq(ms.col(MaterialScope::client)).and(l.material().eq(ms.col(MaterialScope::material))))
    .groupBy((ms, l) -> ms.col(MaterialScope::partNumber))
    .select((ms, l) -> cols(ms.col(MaterialScope::partNumber), sum(l.quantity()).as("quantity")));
```

- **CTE**：只有已映射到类的查询（`into(Row.class)`、`selectAs(Row.class)`、`values(...)`）能作为 CTE，其他的报错。无论被引用多深，渲染时都只在语句开头的 `WITH` 里出现一次；全局过滤器同样作用于 CTE 和子查询里的表。
- **派生表**：`from(query.asTable())`，同样要求先 `into(X.class)`。

```java
record OpenLine(String delivery, String material) { }

Query<String> parts = from(Mara.class).where(m -> m.materialType().eq("ZMAT")).select(m -> m.material());
Query<OpenLine> open = from(Lips.class).select(l -> cols(l.delivery(), l.material())).into(OpenLine.class);
from(open.asTable())
    .where(o -> all(o.col(OpenLine::material).in(parts),                       // IN 子查询
        notExists(from(Vbkd.class).where(v -> v.salesOrder().eq(o.col(OpenLine::delivery))))))
    .select(o -> o.col(OpenLine::delivery));
```

- **UNION**：`q1.unionAll(q2)` 或 `union`。两段的结果类型必须相同，否则编译失败；按名的查询（`cols`、`into`、`selectAs`）按列名对齐，其余按位置。各段不能自带 `orderBy`、`limit`，分页加在合并之后；合并后要排序，先包成 CTE（见 [迁移指南](migration.md#union-之后排序)）。右侧是混用 UNION 与 UNION ALL 的组合时自动加括号，保持原来的分组。
- **窗口**：`rowNumber().over(w -> w.partitionBy(a).orderBy(b.desc()))`；聚合也可以 `.over(...)`；ROWS 框架用 `w.rows(Bound.unboundedPreceding(), Bound.currentRow())`（`Bound` 在 `Window` 里，还有 `preceding(n)`、`following(n)`）。窗口函数也有 `rank`、`denseRank`、`lag`、`lead`（第三个参数是越界时的默认值）、`firstValue`、`lastValue`（取整个分区的末行要写 ROWS 框架）、`ntile(n)`。
- **窗口条件（QUALIFY）**：`qualify(l -> rowNumber().over(w -> w.partitionBy(l.material()).orderBy(l.createdOn().desc())).le(3L))` 每组取前 3 行。HANA 没有 QUALIFY 子句，三个库都渲染成派生表：窗口函数和不在结果里的排序项作为额外的列，外层过滤、排序和分页。条件只能比较窗口函数，其他条件写在 `where` 或 `having` 里，否则报错。
- **常量行**（码表）：`values(Shape.class, List.of(new Shape(...), ...))` 得到一个查询，每行渲染成一个字面量 SELECT，用 UNION ALL 连接，HANA 上加 `FROM DUMMY`，每个属性都要有值。用 `cte(...)` 包起来再参与连接。

```java
record Category(String zfl, String name, Integer sortNo) { }

Cte<Category> categories = cte("category_def", values(Category.class, List.of(new Category("1", "自制件", 1), new Category("2", "供应商件", 2))));
```

业务口径写成普通方法，用接口约束适用的表。`PeriodStock` 是几张库存表共同的接口，数量列由各表自己声明：

```java
static <E extends PeriodStock> Query<StockRow> stockArm(Class<E> table, String year) {
    return from(table)
        .where(h -> h.year().eq(year))
        .select(h -> row(StockRow::new, h.material(), h.year(), h.period(), h.quantity().coalesce(BigDecimal.ZERO).as("quantity")));
}

stockArm(Mardh.class, "2026").unionAll(stockArm(Mchbh.class, "2026"));
```

## 9. 表达式、函数与原生 SQL

**`Expr<T>` 的实例方法**（接收者固定 T，参数类型不符就编译失败）：

- 比较：`eq`、`ne`、`gt`、`ge`、`lt`、`le`。参数可以是值或另一个表达式。
- 区间与集合：`between`（含两端）、`range(from, until)`（半开区间 `>= from AND < until`，适合日期段；skipEmpty 下缺少的一端被忽略）、`in(Collection | Query)`、`notIn`、`isNull`、`isNotNull`。
- 同类型函数：`coalesce`（可以给多个后备值：`a.coalesce(b, c, val("EA"))`，渲染成一个 COALESCE）、`nullif`。
- 别名、排序与转换：`as("x")`、`asc()` / `desc()`（加 `.nullsFirst()` / `.nullsLast()`）、`cast(CastType.decimal(15, 3))`。
- 条件的组合：`cond.and(c)`、`cond.or(c)`、`cond.not()`。

**`Sql` 的静态函数**（各方言语义一致的部分）：

| 类别 | 函数 |
| --- | --- |
| 条件 | `all`、`any`、`not`、`onlyIf(test, () -> cond)`、`ifPresent(v, x -> cond)`、`exists`、`notExists`、`like`、`notLike`、`contains`、`startsWith`、`endsWith` |
| 值 | `val(x)`：常量值，默认用它，在 SELECT 列表等位置写成字面量、在条件里绑定为参数；`lit(x)`：始终写成字面量，只用于必须出现在 SQL 文本里的代码常量；`nullOf(Type.class)`：有类型的 NULL |
| 文本 | `upper`、`lower`、`trim`、`concat`（MySQL 自动渲染为 `CONCAT`）、`substring(e, start[, length])`、`left`、`right`、`replace(e, from, to)`、`locate(e, search)`；位置从 1 开始，`locate` 找不到时为 0。位置可以是整数常量，也可以是表达式，例如 `substring(e, plus(locate(e, ","), lit(1)))` |
| 数值 | `plus`（也接收 `List`）、`minus`、`times`、`divide`（仅 BigDecimal）、`negate`、`abs`、`round`、`floor`、`ceil` |
| 日期 | `year`、`month`、`day`（日期或时间戳）、`addDays`、`addMonths`、`addYears`（日期，个数可为负，月末对齐）、`daysBetween(from, to)` |
| 聚合 | `count()`、`count(e)`、`countDistinct`、`sum`、`avg`、`min`、`max`、`stringAgg(e, sep, order...)`（MySQL 写成 GROUP_CONCAT）、`grouping(e)`（汇总行为 1）；聚合加 `.filter(cond)` 只聚合满足条件的行：`sum(l.quantity()).filter(l.unit().eq("KG"))` 写成 `SUM(CASE WHEN ... THEN ... END)` |
| 窗口 | `rowNumber`、`rank`、`denseRank`、`lag`、`lead`、`firstValue`、`lastValue`、`ntile`，再加 `.over(...)` |
| 其他 | `caseWhen(cond, x).when(...).otherwise(...)`、`caseOf(e).when(值, x).otherwise(...)`（简单 CASE，适合内联的码表）、`cast`、`scalar(query)`、`currentDate`、`currentTimestamp` |

**方言专属能力**只在对应门面里提供，放到别的方言上渲染时会明确报错：

| HANA | PostgreSQL | MySQL |
| --- | --- | --- |
| `toVarchar(e, "YYYYMMDD")`、`toNvarchar`、`toDate` | `distinctOn(...)` 子句 | `dateFormat(e, "%Y%m")` |
| `hint(...)` | `pg(e).isDistinctFrom(...)` / `isNotDistinctFrom` | — |
| `likeRegexpr(e, pattern)` | — | — |
| `length`（按 UTF-16 单元计） | `ilike` | `maxExecutionTime(ms)` 优化器提示 |
| `cube(a, b)`、`groupingSets(keys(a, b), keys(a), keys())` | 同左 | — |
| `toInteger(e)`（即 TO_INT/TO_INTEGER） | — | — |
| `greatest(a, b, ...)`、`least`：有一个参数为 NULL 就返回 NULL | `greatest`、`least`：跳过 NULL 参数 | 同 HANA |

`greatest`、`least` 的常量参数写成 `val(x)`。三个库对 NULL 的处理不同，所以它们放在各自的方言门面里。`cube` 和 `groupingSets` 是分组键，写在 `groupBy` 里，空的 `keys()` 是总计。

**常量与参数**：SELECT 列表里的常量写成字面量；带 GROUP BY 或 DISTINCT 的查询，GROUP BY、HAVING、ORDER BY 里的常量也写成字面量；WHERE 和 ON 里的值仍然绑定为参数。这样分组表达式（例如按 CASE 分桶）在三个库上都能执行，HANA 也能确定 SELECT 列的类型。

### 原生 SQL 片段

库里没有的函数或写法，用模板嵌进查询。`{0}`、`{1}` 依次是参数：表达式和条件原位渲染，其他值绑定为参数（SELECT 列表里写成字面量），集合展开成逗号分隔的多个参数。

```java
sql(String.class, "LPAD({0}, {1}, '0')", m.material(), 10)           // 表达式，类型由第一个参数声明
sqlCond("{0} IN ({1})", l.unit(), List.of("EA", "KG"))                // 条件
sqlCond("{0} <> {1}", m.materialType(), "ZOTH").on(MysqlDialect.class, "{0} != {1}")   // 给某个方言另一种写法
```

渲染时用与当前方言最接近的模板，没有匹配的就用默认模板。`sql(...)` 和 `sqlCond(...)` 的 `.on(方言类, 模板)` 都可以这样用。

**原生数据源**：整段子查询用 `sqlTable(Row.class, 模板, 参数...)`，放进 `from` 或 `join`，列用 `t.col(Row::x)` 读取。模板里每一列要用 Row 的属性名作别名，并按方言加引号，所以列名、表名写成 `identifier(...)` 参数，同一份模板在三个库上都能用：

```java
record MaterialTotal(String material, BigDecimal total) { }

var totals = sqlTable(MaterialTotal.class, "SELECT {0} AS {1}, SUM({2}) AS {3} FROM {4}.{5} WHERE {6} IN ({7}) GROUP BY {0}",
    identifier("MATNR"), identifier("material"), identifier("LFIMG"), identifier("total"), identifier("ERP"), identifier("LIPS"),
    identifier("VRKME"), List.of("EA", "KG"));

from(totals)
    .join(Mara.class, (t, m) -> m.material().eq(t.col(MaterialTotal::material)))
    .where((t, m) -> t.col(MaterialTotal::total).gt(BigDecimal.ONE))
    .select((t, m) -> cols(m.partNumber(), t.col(MaterialTotal::total)));
```

模板参数为 null 或空集合时报错（NULL 直接写在模板里）。整条查询都没法表达时用 `db.sql("...")`，见第 10 节。

## 10. 执行

| 方法 | 说明 |
| --- | --- |
| `db.list(q)` | 全部行 |
| `db.one(q)` | 恰好一行，否则报错；最多读 2 行 |
| `db.first(q)` | `Optional`，最多读 1 行 |
| `db.stream(q)` / `db.forEach(q, …)` | 在一个游标上逐行读取；`stream` 要用 try-with-resources 关闭。PostgreSQL 在自动提交的连接上自动开临时事务；MySQL 要配置 `fetchSize(Integer.MIN_VALUE)` 才逐行读取 |
| `db.count(q)` | 外包 `COUNT(*)` |
| `db.page(q, page, size)` | 页号从 1 开始，返回 `Page`（`rows()`、`total()`、`page()`、`size()`、`totalPages()`）；已知总数时 `db.page(q, page, size, total)` 不再执行 COUNT |
| `db.sql("... :name IN (:ids)").bind("ids", ids).list(Type.class)` | 原生 SQL；集合参数自动展开；按列标签映射；另有 `first`、`one`、`bindNull`、`timeout`。引号、注释和方言的引用（PostgreSQL 的 `$$…$$`、`::` 类型转换、MySQL 的反引号）里的 `:name` 不是参数 |
| `q.cached(Duration.ofMinutes(5))` | 结果按 SQL 和参数缓存（需要先配置 `cache`）；缓存的是解码后的原始行 |
| `q.timeout(Duration.ofSeconds(10))`、`db.sql(...).timeout(...)` | 这条语句的时限，覆盖 `config.queryTimeout` |

**超时**：通过 JDBC 的 `setQueryTimeout` 交给驱动，三个库都在服务端取消语句，连接之后照常可用（实库测试验证过）。JDBC 按整秒计时，不足一秒的部分按一秒算。超时抛出 `QueryExecutionException`，它的 `isTimeout()` 为 true，`statement()` 是失败的那条语句。底层原因分别是 HANA 的 `SQLTimeoutException`（错误码 613）、PostgreSQL 的 SQLState `57014`、MySQL 的 `MySQLTimeoutException`。

**参数个数**：HANA 和 PostgreSQL 的一条语句最多绑定 65535 个参数。超过时在连接数据库之前就报错，而不是等数据库返回难懂的错误。很长的 IN 列表请拆成几次查询，或者改成子查询。MySQL Connector/J 默认在客户端把参数写进语句，所以不设这个上限。

**全局过滤器**按表接口的父接口匹配：FROM 中的表把条件加到 WHERE，JOIN 的表加到 ON（LEFT JOIN 语义不变）；关系连接的表、CTE 和子查询里的表同样生效。

## 11. 打印与测试 SQL

```java
Query<String> q = from(Lips.class)
    .where(l -> l.material().eq("O'Neil").and(l.createdOn().eq(LocalDate.of(2026, 1, 15))))
    .select(l -> lit("a?b").as("mark"));

Rendered r = db.render(q);      // 本数据库会为这条查询执行的 SQL，已套用全局过滤器；同一个查询只渲染一次
r.sql();                        // 带 ? 的语句
r.parameters();                 // 数据库侧的参数值（已经过转换器）
r.parameterText();              // O'Neil(String), 20260115(String)
r.debugSql();                   // 参数写回 SQL 的版本，可以直接粘到数据库客户端；只用于排查，程序执行时仍然绑定参数
```

`r.toString()` 是 SQL 加一行 `-- parameters: ...`。不带数据库实例、只按方言渲染用 `q.toSql(HanaDialect.INSTANCE)`（不含全局过滤器）。

**打印每条语句**：`Config.printSql()` 输出到 JDK 的 `System.Logger`（名为 `org.dpdns.zerodep.query.sql`，级别 INFO，接 SLF4J 时加 slf4j-jdk-platform-logging）；`printSql(out)` 输出到自己的 `Consumer<String>`，例如 `log::info`。一条语句一条消息：

```
==> Preparing: SELECT MARA.MFRPN AS "partNumber"
FROM ERP.MARA MARA
WHERE MARA.MATNR = ?
==> Parameters: M1(String)
<== Total: 0, Time: 3 ms
```

命中缓存时最后一行带 `(cached)`，失败时最后一行是 `<== Error: ...`。

**不连数据库测试代码发出的 SQL**：`ConnectionProvider.dryRun()` 不连接任何数据库，每条查询都得到空结果（`count` 为 0），但照常渲染、打印和触发监听器；`db.capture(work)` 返回 `work` 在当前线程里通过本数据库执行的全部语句（按执行顺序，含缓存命中，可以嵌套，内层的语句不计入外层）：

```java
Database db = Hana.database(ConnectionProvider.dryRun(), c -> c.printSql());
List<QueryListener.Event> events = db.capture(() -> service.load(request));
assertEquals(List.of("D1"), events.get(0).statement().parameters());
assertTrue(events.get(0).statement().sql().contains("LIPS"));
```

## 12. 扩展点

| 要扩展的 | 做法 |
| --- | --- |
| 值类型 | `Converter<J, D>` 加组合注解；或用 `config.type(Storage.class, reader, writer)` 覆盖某个存储类型的 JDBC 读写 |
| 函数 | 写一个静态方法：`call(FunctionDef.of("MY_FN"), Integer.class, args...)`。只属于某个方言的用 `FunctionDef.only(HanaDialect.class, "...")`；单个数据库实例需要改写时用 `config.function(def, renderer)` |
| 表达式节点 | 继承 `Expr<T>`，实现 `render(SqlWriter)` |
| 子句 | 实现 `Clause`，声明位置（`HINT` / `DISTINCT` / `END`）和所属方言 |
| 数据来源 | 实现 `Source<E>`，例如带参数的视图 |
| 方言 | 继承 `Dialect`（或某个现成方言），覆盖引号、字面量、分页、排序项、类型名、函数渲染和 JDBC 读写 |
| 连接与监听 | `ConnectionProvider`、`QueryListener`、`QueryCache` |

**用 SLF4J 记录语句**：本库不依赖任何日志框架，监听器里接上项目已有的日志即可。事件里有数据库名、SQL 与参数、耗时、行数、异常，以及是否命中缓存；监听器抛出的异常会被忽略：

```java
Logger log = LoggerFactory.getLogger("query");
Database db = Hana.database(dataSource, c -> c.listener(e -> {
    if (e.error() != null) log.warn("{} failed after {} ms: {}", e.database(), e.elapsed().toMillis(), e.statement(), e.error());
    else if (e.elapsed().toMillis() > 500) log.info("slow: {} rows in {} ms: {}", e.rows(), e.elapsed().toMillis(), e.statement());
}));
```

## 13. 编译期能拦住的错误

以下错误写法由 `CompileTimeSafetyTest` 在每次构建时用 javac 验证：lambda 里用了不在查询中的表的列；比较值、连接键或 `coalesce` 参数的类型与列不符（包括日期列传入字符串）；对文本列 `sum`；`row(...)` 的参数个数或类型与构造器不符（包括嵌套的 `row`）；UNION 两段的结果类型不同；泛型模板套用到缺少共享接口的表；CTE 的 `col(...)` 用了不属于它的行类的 getter；窗口函数没有 `over`。

名字类的错误在构造查询时立刻报错，不等到执行：`cols` 缺名字或重名、`selectAs` 找不到同名列、条件对象的字段对应不上、`exclude` / `only` / `sortBy` 的列名不存在、表接口里有既不是列也不是关系的抽象方法。

Java 不跟踪可空性，所以结果属性要用包装类型。

## 14. 安全与上线前检查

**值和 SQL 文本**：

- 条件里的值一律绑定为参数。写成字面量的只有 SELECT 列表、GROUP BY 等位置的常量（见第 9 节）、`values(...)` 的行和 `lit(...)`。
- 字面量由方言转义：单引号加倍；MySQL 的反斜杠加倍；PostgreSQL 的值含反斜杠时写成 `E'...'`，不受 `standard_conforming_strings` 设置的影响；HANA 的非 ASCII 文本加 `N` 前缀。三库实库测试用含引号、反斜杠、注释符和问号的文本验证过往返。
- `NaN`、`Infinity` 没有字面量形式：在 SELECT 列表里仍然绑定为参数，`lit(...)` 时直接报错。
- `sql(Type.class, "模板", ...)`、`sqlCond(...)`、`sqlTable(...)` 的模板和 `db.sql("...")` 的文本原样执行。这些文本里只放代码中的常量，用户输入一律作为参数传入（模板参数或 `bind`）。
- 标识符来自注解、方法名和 `as("x")`，渲染时加引号并转义；`sortBy`、`only`、`exclude` 只认查询自己的结果列名，用户传入的名字不会进入 SQL 文本。
- 用户输入的文本匹配用 `contains` / `startsWith` / `endsWith`（或条件对象的 `CONTAINS` 等），通配符自动转义；`like(e, pattern)` 的模式原样使用，来自用户输入时要先自行转义。

**上线前检查**：

1. **超时**：给数据库实例配置 `queryTimeout`，耗时的报表查询单独设置 `q.timeout(...)`。在 Spring 里也可以用事务超时。
2. **大结果集**：用 `stream` 或 `forEach` 逐行处理，并用 try-with-resources 关闭流。PostgreSQL 会自动用游标；MySQL 需要配置 `fetchSize(Integer.MIN_VALUE)`，读完之前同一连接不能执行别的语句。带子列表的查询不能流式读取。
3. **子列表与对象树**：每一层子列表多一条语句，父行很多时每 500 个键一批；父行数量没有上限时先分页。
4. **连接池**：库每条语句借一个连接、用完归还，不持有连接；连接池的大小、校验和泄漏检测仍由 Druid、HikariCP 等配置。
5. **日志**：用 `printSql()` 或监听器记录慢查询和失败（示例见第 12 节）。事件里有 SQL 和参数，参数可能含业务数据，按日志规范脱敏。
6. **缓存**：`cached(...)` 按 SQL 和参数缓存，适合多人同时看的大屏。内存缓存只在本进程有效，多实例部署时实现 `QueryCache` 接到 Redis。
7. **方言实例**：每个数据源一个 `Database`，作为单例长期复用。元数据、渲染结果都缓存在里面，并发使用是安全的。
8. **全局过滤器**：多租户、逻辑删除的条件配成 `filter(...)`，不要在每个查询里重复写；`unfiltered()` 只用于确实要跨租户的查询。

**兼容性承诺**：2.x 内不破坏这些公开 API：根包的 `Sql`、`Fn`；`table`、`expr`、`scope`、`select`、`annotation`、`type` 包里的公开类型；`meta` 包里的 `Getter`、`NamingStrategy`；`exec` 包里的 `Database`、`ConnectionProvider`、`QueryCache`、`QueryListener`、`QueryExecutionException`、`NativeQuery`、`Page`；各方言门面与数据库实例；`query-spring` 的 `SpringConnections`。

`dialect` 包是扩展点，方法签名可能在次版本中增加参数。`exec.JdbcExecutor`、`MappingPlan` 等其余公开类型只供库内部跨包使用，不在承诺范围内。

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
| `UNION ALL`、`INTERSECT`、`EXCEPT` | `q1.unionAll(q2)`、`q1.intersect(q2)`、`q1.except(q2)` |
| `ROW_NUMBER() OVER (PARTITION BY a ORDER BY b DESC)` | `rowNumber().over(w -> w.partitionBy(a).orderBy(b.desc()))` |
| 每组取前 N 行（`QUALIFY`） | `.qualify(l -> rowNumber().over(w -> w.partitionBy(a).orderBy(b.desc())).le(3L))` |
| 原生 SQL 片段 | `sql(String.class, "LPAD({0}, {1}, '0')", l.material(), 18)` |
| 执行 | `db.list(q)`、`db.one(q)`、`db.optional(q)`、`db.first(q)`、`db.page(q, 1, 20)`、`db.stream(q)` |
| `INSERT`、`UPDATE`、`DELETE` | `db.execute(insertInto(T.class).values(rows))`、`update(T.class).set(t -> t.x().to(v)).where(...)`、`deleteFrom(T.class).where(...)`；按主键 `.byId(row)`，见第 15 节 |
| 插入或更新、`FOR UPDATE` | `.onConflict().update(t -> t.x())`、`.with(forUpdate(Lock.SKIP_LOCKED))` |
| 联表 `UPDATE` / `DELETE` | 方言门面的 `updateJoin(T.class, S.class, (t, s) -> on).set(...).where(...)`、`deleteJoin(...)`，见第 15 节 |

## 1. 依赖与数据库实例

选定方言，引入对应的方言包。方言包会传递依赖核心包，JDBC 驱动由使用方自己提供。

```xml
<dependency>
    <groupId>org.dpdns.zerodep</groupId>
    <artifactId>query-hana</artifactId>      <!-- 或 query-postgres、query-mysql、query-sqlite -->
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
| SQLite 3.46+ | `org.dpdns.zerodep.query.sqlite.Sqlite` | `SqliteDatabase`（见第 18 节） |

`database(...)` 接收 `DataSource` 或自定义的 `ConnectionProvider`。`Config` 还有：

- `filter("tenant", Client.class, t -> ...)`：具名的全局过滤器，单个查询可以用 `unfiltered("tenant")` 只关掉它；过滤器每次渲染都调用，返回 `Cond.EMPTY` 就不加条件，所以"管理员看全部租户"这类动态开关写在回调里：`t -> currentTenant() == null ? Cond.EMPTY : t.tenant().eq(currentTenant())`。多个过滤器按添加顺序生效；
- `schema("ERP", "SAPHANADB")` 或 `schema(s -> s + "_TEST")`：表接口里写的 schema 换成实际的名字，SAP 各环境 schema 不同时不用改代码，查询、写语句和关系里的表都换，原生 SQL 文本不换；
- `onInsert`、`onUpdate`：审计字段自动填充；`generate(Keyed.class, k -> k.id(), ids::next)`：插入时逐行给值为 null 的列生成值（例如雪花主键），见第 15 节；
- `intercept(statement -> ...)`：语句执行前的钩子，可以改写 SQL 或抛出异常拦下语句，见第 12 节；
- `function`、`type`（覆盖函数渲染与类型读写，见第 12 节）、`fetchSize`、`name`。

**在 Spring 中使用**：引入 `query-spring`（版本同上），用 `SpringConnections.of(dataSource, transactionManager)` 取连接。多数据源的项目每个数据源一个数据库实例，各自传入管理这个数据源的事务管理器：

```java
@Bean
HanaDatabase hana(@Qualifier("sapHana") DataSource dataSource,
                  @Qualifier("sapHanaTransactionManager") PlatformTransactionManager transactionManager) {
    return Hana.database(SpringConnections.of(dataSource, transactionManager), c -> c.filter(...));
}
```

- 它通过 `DataSourceUtils` 取连接，所以在 `@Transactional` 方法里，查询使用事务的连接：能看到同一事务里 MyBatis、JdbcTemplate 尚未提交的写入，也随事务一起回滚（`QueryExecutionException` 是运行时异常）。不在事务里时，每条语句借一个连接，用完归还。
- 给了事务管理器时，`db.transaction(...)` 和多行写入的自动事务由它执行（传播行为 REQUIRED）：这个事务管理器已有事务（`@Transactional("sapHanaTransactionManager")` 或编程式的 `TransactionTemplate`）时加入，没有时开启。建在本库上的库（例如流程引擎）因此能自己划定事务边界，不依赖调用方有没有写 `@Transactional`。
- 跨数据源的事务用编程式事务把几个事务管理器的 `TransactionTemplate` 嵌套起来，各个数据库实例的 `db.transaction(...)` 分别加入自己那一层；这不是分布式事务，提交顺序由嵌套的先后决定。
- 只用 `SpringConnections.of(dataSource)` 时 `db.transaction(...)` 报错，事务只能用 `@Transactional`。
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

**枚举**：默认按名字存取。实现 `CodeEnum<C>` 的枚举按编码存取，不用给每列加注解：

```java
public enum Status implements CodeEnum<String> {
    OPEN("A"), CLOSED("C");
    private final String code;
    Status(String code) { this.code = code; }
    @Override public String code() { return code; }
}
@Column("STATUS") Col<Status> status();       // 读写和条件里都是 'A'、'C'
```

编码的类型取自泛型参数（`String`、`Integer`、`Long` 等）；同一个枚举里编码不能重复，读到表里没有的编码时报错。

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
| `join` / `leftJoin` / `rightJoin(表接口.class 或 Source, (…) -> cond)` | 连接条件里可以用到作用域内所有的表；`crossJoin(...)` 是笛卡尔积。`rightJoin` 时左侧各表的全局过滤条件放进它的 ON，补出来的行不会被 WHERE 过滤掉 |
| `fullJoin(...)` | HANA、PostgreSQL 的 FULL JOIN（MySQL 渲染时报错）。两侧都会补空行，过滤条件放在哪里都会改变语义，所以有全局过滤条件的表不能参与，要先 `unfiltered()`，再在 CTE 或派生表里各自过滤 |
| `where(… -> cond)`、`having(… -> cond)` | 条件；多次调用之间是 AND，空条件（`Cond.EMPTY`）自动丢弃 |
| `where(searchObject)` | 按条件对象筛选，见第 7 节 |
| `skipEmpty()` | 其后的 WHERE、HAVING、ON 里，与 null、空白字符串或空集合比较的条件直接省略；`between` 只缺一端时留下另一端 |
| `groupBy(… -> 键)` | 一个键直接写表达式；多个键写 `keys(a, b)` |
| `orderBy(… -> 排序)` | 一个表达式按升序排，`.desc()` 降序，`.nullsFirst()` / `.nullsLast()` 指定 NULL 的位置；多项写 `orders(a, b.desc())` |
| `groupByRollup(… -> 键)` | 分组并加上各级小计和总计；一次列出全部分组键，不能与 `groupBy` 同用 |
| `distinct()`、`limit(n)`、`offset(n)` | — |
| `unfiltered()`、`unfiltered("tenant")`、`withDeleted()` | 本查询不加全局过滤器和逻辑删除条件；只关掉这些名字的过滤器；只关掉逻辑删除（看已删除的行） |
| `with(clause)` / `with(… -> clause)` | 方言子句，例如 `Hana.hint(...)`、`Postgres.distinctOn(...)` |

- 与 null 比较在 `skipEmpty()` 之外是错误（比较永远不成立，多半是漏传了参数）；确实要判空用 `isNull()`。单个条件也可以不开 `skipEmpty`，写成 `ifPresent(x, v -> ...)` 或 `onlyIf(flag, () -> ...)`。
- 自连接时，同一个表接口再 `join` 一次就是下一个参数，别名自动唯一。
- 关联子查询直接捕获外层 lambda 的参数；`exists` / `notExists` 直接接收作用域，不用写 SELECT 列表。
- 两列可以直接比较：`a.eq(m.material())`，`ne`、`gt`、`ge`、`lt`、`le` 同样可以。
- NULL 也相等的比较：`a.isNotDistinctFrom(b)`、`a.isDistinctFrom(b)`，结果总是真或假；PostgreSQL 写成 `IS [NOT] DISTINCT FROM`，MySQL 写成 `<=>`，HANA 写成等价的 CASE。
- 子查询比较：多列 `keys(l.delivery(), l.material()).in(q)` / `notIn(q)`（子查询选同样多的列）；与任一行或每一行比较 `l.quantity().gt(allOf(q))`、`x.eq(anyOf(q))`（`ANY` 遇到空子查询为假，`ALL` 为真）。
- 条件作为列：`bool(l.quantity().gt(BigDecimal.ZERO)).as("positive")`，写成 `CASE WHEN ... THEN TRUE WHEN NOT (...) THEN FALSE END`，条件未知时为 NULL（HANA 不能把条件直接写在 SELECT 里）。

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
- 自定义的比较写成实现 `Where.Operator` 的类（要有无参构造方法），用 `using` 指定：`@Where(value = "createdOn", using = InMonth.class) YearMonth month`，`InMonth.apply(column, value)` 返回条件；字段为空时不调用。
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

- **UNION、INTERSECT、EXCEPT**：`q1.unionAll(q2)`、`union`、`intersect`、`except`（后两者去重）。两段的结果类型必须相同，否则编译失败；按名的查询（`cols`、`into`、`selectAs`）按列名对齐，其余按位置。各段不能自带 `orderBy`、`limit`，分页加在合并之后；合并后要排序，先包成 CTE（见 [迁移指南](migration.md#union-之后排序)）。调用顺序就是求值顺序：`a.union(b).intersect(c)` 是 `(a ∪ b) ∩ c`。PostgreSQL、MySQL 的 INTERSECT 优先于 UNION，HANA 从左到右，所以读法可能不同的地方都自动加括号。`INTERSECT ALL`、`EXCEPT ALL` 只有 PostgreSQL、MySQL 支持，在方言包。
- **窗口**：`rowNumber().over(w -> w.partitionBy(a).orderBy(b.desc()))`；聚合也可以 `.over(...)`；ROWS 框架用 `w.rows(Bound.unboundedPreceding(), Bound.currentRow())`（`Bound` 在 `Window` 里，还有 `preceding(n)`、`following(n)`）。窗口函数也有 `rank`、`denseRank`、`lag`、`lead`（第三个参数是越界时的默认值）、`firstValue`、`lastValue`、`nthValue(e, n)`（取整个分区里的行要写 ROWS 框架）、`ntile(n)`、`percentRank()`、`cumeDist()`。
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
| 文本 | `upper`、`lower`、`trim`、`concat`（MySQL 自动渲染为 `CONCAT`）、`substring(e, start[, length])`、`left`、`right`、`replace(e, from, to)`、`locate(e, search)`；位置从 1 开始，`locate` 找不到时为 0。位置可以是整数常量，也可以是表达式，例如 `substring(e, plus(locate(e, ","), lit(1)))`。`lpad(e, n, pad)`、`rpad(e, n, pad)` 补足到 n 个字符（物料号补零 `lpad(m.material(), 18, "0")`），过长时截断 |
| 数值 | `plus`（也接收 `List`）、`minus`、`times`、`divide`（仅 BigDecimal）、`negate`、`abs`、`round`、`floor`、`ceil`、`mod`（符号同被除数）、`power`、`sqrt`（结果是 Double）、`sign`。除数为 0 时 HANA、PostgreSQL 报错，MySQL 得到 NULL |
| 日期 | `year`、`month`、`day`（日期或时间戳）、`addDays`、`addMonths`、`addYears`（日期，个数可为负，月末对齐）、`monthStart(d)`（当月第一天，按月汇总用）、`daysBetween(from, to)`；时间戳的 `hour`、`minute`、`second`（整数，舍去不足一秒的部分）、`addHours`、`addMinutes`、`addSeconds`、`secondsBetween(from, to)`（整秒）。字符长度各库计法不同（MySQL 的 LENGTH 按字节），用方言函数，例如 `Hana.length` |
| 聚合 | `count()`、`count(e)`、`countDistinct`、`sum`、`sumDistinct`、`avg`、`avgDistinct`、`min`、`max`、`stringAgg(e, sep, order...)`（MySQL 写成 GROUP_CONCAT）、`grouping(e)`（汇总行为 1）；聚合加 `.filter(cond)` 只聚合满足条件的行：`sum(l.quantity()).filter(l.unit().eq("KG"))` 写成 `SUM(CASE WHEN ... THEN ... END)` |
| 统计 | `stddevSamp`、`stddevPop`、`varSamp`、`varPop`（Double；样本的只有一行时为 NULL）。参数先转成 DOUBLE，三库结果一致；不提供不带后缀的 `stddev`，因为 MySQL 的 STDDEV 是总体标准差 |
| 窗口 | `rowNumber`、`rank`、`denseRank`、`percentRank`、`cumeDist`、`lag`、`lead`、`firstValue`、`lastValue`、`nthValue`、`ntile`，再加 `.over(...)` |
| 子查询与条件 | `scalar(query)`、`anyOf(query)`、`allOf(query)`、`bool(cond)`、`keys(a, b).in(query)` |
| 其他 | `caseWhen(cond, x).when(...).otherwise(...)`、`caseOf(e).when(值, x).otherwise(...)`（简单 CASE，适合内联的码表）、`cast`、`currentDate`、`currentTimestamp` |

**方言专属能力**只在对应门面里提供，放到别的方言上渲染时会明确报错：

| HANA | PostgreSQL | MySQL |
| --- | --- | --- |
| `toVarchar(e, "YYYYMMDD")`、`toNvarchar`、`toDate` | `distinctOn(...)` 子句 | `dateFormat(e, "%Y%m")` |
| `hint(...)` | — | — |
| `likeRegexpr(e, pattern)` | — | — |
| `length`（按 UTF-16 单元计） | `ilike` | `maxExecutionTime(ms)` 优化器提示 |
| `cube(a, b)`、`groupingSets(keys(a, b), keys(a), keys())` | 同左 | — |
| `toInteger(e)`（即 TO_INT/TO_INTEGER） | — | — |
| `greatest(a, b, ...)`、`least`：有一个参数为 NULL 就返回 NULL | `greatest`、`least`：跳过 NULL 参数 | 同 HANA |
| `median(e)`、`percentileCont(p, e).over(...)`、`percentileDisc`（HANA 只把百分位数当窗口函数）、`corr(y, x)` | `percentileCont(p, e)`、`percentileDisc`、`mode(e)`（只作聚合）、`corr(y, x)` | — |
| — | `intersectAll(a, b)`、`exceptAll(a, b)` | 同左（8.0.31 起） |
| — | 窗口帧 `w.frame(range(daysPreceding(7), Bound.currentRow()))`，数值偏移用 `Bound.preceding(n)` | 同左 |
| — | `recursiveCte(name, anchor, self -> step)`，写成 `WITH RECURSIVE` | 同左（受 `cte_max_recursion_depth` 限制） |
| — | `forShare(lock)`、`forUpdateOf(lock, l)`（`FOR UPDATE OF 别名`） | 同左 |
| — | `db.returning(write, Row.class)`：插入、更新、删除后用 RETURNING 读回行 | — |
| 数据来源：`fromView(View.class, Map.of("P_YEAR", "2026"))`（计算视图的 PLACEHOLDER 参数）、`fromFunction(Fn.class, Map.of("P_UNIT", "EA"))`（表函数的命名实参） | 数组：`eqAny(e, values)`（`= ANY(?)`，一个数组参数）、`arrayContains`、`arrayOverlaps`、`cardinality`、`arrayAgg(e, String[].class, order...)`；数组列声明成 `Col<String[]>`（还有 Integer、Long、BigDecimal、LocalDate） | 优化器提示：`index(l, "idx")`、`noIndex(m)`，与 `maxExecutionTime` 合并进同一个 `/*+ ... */`（MySQL 只认第一个提示注释） |
| 层级：`from(hierarchy(Node.class, 来源))`、`descendants(..., List.of("B"))`、`ancestors`，来源查询要有 nodeId、parentId 两列 | JSONB：`jsonText(doc, "owner", "name")`、`jsonContains(doc, json)`、`jsonHasKey(doc, key)`、写入用 `jsonb(text)` | JSON：`jsonText(doc, "$.owner.name")`、`jsonContains(doc, json)` |
| 全文检索：`contains(term, cols...)`、`containsFuzzy(0.8, term, cols...)`、`score()`；JSON：`jsonValue(doc, "$.a")`（路径必须写在代码里） | 全文检索：`textMatches("simple", doc, q)`、`webMatches`、`textRank` | 全文检索：`match(q, cols...)`、`matchBoolean`、`matchScore`（列要正好是一个 FULLTEXT 索引） |

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
| `db.optional(q)` | `Optional`：零行或一行，多于一行报错；最多读 2 行 |
| `db.first(q)` | `Optional`，最多读 1 行 |
| `db.stream(q)` / `db.forEach(q, …)` | 在一个游标上逐行读取；`stream` 要用 try-with-resources 关闭。PostgreSQL 在自动提交的连接上自动开临时事务；MySQL 要配置 `fetchSize(Integer.MIN_VALUE)` 才逐行读取 |
| `db.count(q)` | 外包 `COUNT(*)` |
| `db.exists(q)` | 是否至少有一行：`SELECT CASE WHEN EXISTS (q) THEN 1 ELSE 0 END`，不读出行 |
| `db.page(q, page, size)` | 页号从 1 开始，返回 `Page`（`rows()`、`total()`、`page()`、`size()`、`totalPages()`）；已知总数时 `db.page(q, page, size, total)` 不再执行 COUNT |
| `db.sql("... :name IN (:ids)").bind("ids", ids).list(Type.class)` | 原生 SQL；集合参数自动展开；按列标签映射；另有 `first`、`one`、`bindNull`、`timeout`。引号、注释和方言的引用（PostgreSQL 的 `$$…$$`、`::` 类型转换、MySQL 的反引号）里的 `:name` 不是参数 |
| `db.attach(rows, key, ids -> query, keyOf, setter)` / `db.attachMany(...)` | 按键把另一个查询的结果装回已读出的行（一对一 / 一对多）：键去重后每批 1000 个查询，没有对应的写 null / 空列表；适合不便声明成关系的数据，例如别的库或别的模块的表 |
| `q.cached(Duration.ofMinutes(5))` | 结果按 SQL 和参数缓存（需要先配置 `cache`）；缓存的是解码后的原始行 |
| `q.timeout(Duration.ofSeconds(10))`、`db.sql(...).timeout(...)` | 这条语句的时限，覆盖 `config.queryTimeout` |

**超时**：通过 JDBC 的 `setQueryTimeout` 交给驱动，三个库都在服务端取消语句，连接之后照常可用（实库测试验证过）。JDBC 按整秒计时，不足一秒的部分按一秒算。超时抛出 `QueryExecutionException`，它的 `isTimeout()` 为 true，`statement()` 是失败的那条语句。底层原因分别是 HANA 的 `SQLTimeoutException`（错误码 613）、PostgreSQL 的 SQLState `57014`、MySQL 的 `MySQLTimeoutException`。

**参数个数**：HANA 和 PostgreSQL 的一条语句最多绑定 65535 个参数。超过时在连接数据库之前就报错，而不是等数据库返回难懂的错误。很长的 IN 列表请拆成几次查询，或者改成子查询。MySQL Connector/J 默认在客户端把参数写进语句，所以不设这个上限。

**全局过滤器**按表接口的父接口匹配：FROM 中的表把条件加到 WHERE，JOIN 的表加到 ON（LEFT JOIN 语义不变）；关系连接的表、CTE 和子查询里的表同样生效。`unfiltered("tenant")` 里的名字不是任何已配置的过滤器时报错，免得拼错的名字悄悄不起作用。

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
| 执行前改写或拦截 | `config.intercept(StatementInterceptor)`：每条语句（查询、写语句、计数、原生 SQL）执行前调用，返回要执行的 `Rendered`；抛出异常则不执行 |
| 条件对象的比较 | `@Where(using = MyOperator.class)`，见第 7 节 |
| 结果异常 | `db.one` 没有行时抛 `EmptyResultException`，多于一行（以及 `db.optional`）抛 `TooManyRowsException`，都继承 `IllegalStateException`，全局异常处理里可以分别映射成 404 和 500 |

**执行前钩子**：

```java
Database db = Hana.database(dataSource, c -> c.intercept(s -> {
    if (readOnlyMode() && !s.sql().startsWith("SELECT")) throw new IllegalStateException("read-only");
    return new Rendered(s.sql() + "\n-- app=report", s.bindings(), s.dialect());   // 只改文本，参数不变
}));
```

多个钩子按添加顺序调用。设置了 `cached(...)` 的查询用改写后的 SQL 作缓存键。`db.render(q)` 显示的是改写前的语句，监听器和 `capture` 看到的是改写后的。

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
8. **全局过滤器**：多租户的条件配成 `filter(...)`，逻辑删除用 `@LogicDelete`，不要在每个查询里重复写；`unfiltered()` 只用于确实要跨租户或看已删除行的查询。
9. **写入**：多行写入和 `byId` 已自动放进一个事务；跨多条语句的业务操作放进 `db.transaction(...)` 或 `@Transactional`。

**兼容性承诺**：2.x 内不破坏这些公开 API：根包的 `Sql`、`Fn`；`table`、`expr`、`scope`、`select`、`write`、`annotation`、`type` 包里的公开类型；`meta` 包里的 `Getter`、`NamingStrategy`；`exec` 包里的 `Database`、`ConnectionProvider`、`QueryCache`、`QueryListener`、`QueryExecutionException`、`UnexpectedRowCountException`、`NativeQuery`、`Page`；各方言门面与数据库实例；`query-spring` 的 `SpringConnections`。

`dialect` 包是扩展点，方法签名可能在次版本中增加参数。`exec.JdbcExecutor`、`MappingPlan` 等其余公开类型只供库内部跨包使用，不在承诺范围内。

## 15. 写入

写语句和查询一样是不可变的值：可以先拼好、复用，`toSql(dialect)` 或 `db.render(write)` 看 SQL，`db.execute(write)` 执行并返回影响的行数。设计取舍见 [0007](decisions/0007-writes.md)。

### 声明

```java
@Table(schema = "APP", value = "DOC")
public interface Doc extends Audited {             // Audited 声明审计列，见下文
    @Id @Column("ID") Col<String> id();            // 主键，可多列
    @Column("TITLE") Col<String> title();
    @Version @Column("VERSION") Col<Integer> version();      // 乐观锁版本，Integer 或 Long
    @LogicDelete @Column("DELETED") Col<Integer> deleted();  // 逻辑删除，默认 0 未删除、1 已删除
    @ReadOnly @Column("SOURCE") Col<String> source();        // 只读：数据库默认值或触发器维护，本库从不写
}

@Table("SEQ")
public interface Seq extends Entity {
    @Id @Generated @Column("ID") Col<Long> id();   // 数据库生成的主键：插入时不写
    @Column("NAME") Col<String> name();
}
```

`@LogicDelete(active = "N", deleted = "Y")` 按列的类型解析两个值（Boolean、整数、文本）；列是 `LocalDateTime` 时记录删除时间，未删除是 `IS NULL`，删除写 `CURRENT_TIMESTAMP`。`@LogicDelete(epochMillis = true)` 的列是 `Long`：未删除为 0，删除时写当前毫秒数，这样 `UNIQUE (业务键, 删除列)` 能拦住两条未删除的同键行，又允许同一个键删除后再建（NULL 互不相等，记录删除时间的写法做不到）。版本列、生成列、逻辑删除列每张表各至多一列。

| 写入策略 | 插入 | `byId` 更新、插入或更新的更新部分 | `update(...).set(...)` 显式写 |
| --- | --- | --- | --- |
| `@ReadOnly` | 不写 | 不写 | 报错 |
| `@WriteOnce`（例如创建时间） | 写 | 不写 | 可以 |
| `@Generated` | 不写 | 不写 | 可以 |

### 插入、更新、删除

```java
db.execute(insertInto(Doc.class).values(new DocRow("D1", "a", 1), new DocRow("D2", "b", 1)));
db.execute(insertInto(Note.class).from(from(Note.class).where(...).select(n -> cols(lit("N9").as("id"), n.title()))));

db.execute(update(Note.class)
    .set(n -> n.qty().to(plus(n.qty(), val(10))))
    .set(n -> n.status().to("closed"))
    .where(n -> all(n.id().eq(id), n.status().eq("open")))
    .expect(1));                                  // 影响的行数不是 1 时抛 UnexpectedRowCountException

db.execute(deleteFrom(Note.class).where(n -> n.status().eq("closed")));
```

- **行**：record 或 POJO，按属性名对应表的列，属性名找不到列时报错；所有属性都写，null 写成 NULL。多行插入按 JDBC 批量执行（每批 500 行），并自动放在一个事务里。
- **从查询插入**：查询的列名（`cols` 的名字或 `as(...)`）对应表的属性名。
- **SET**：`col.to(值)` 或 `col.to(表达式)`；`skipEmpty()` 下值为空的 SET 项省略，全部省略时不执行。
- **WHERE**：lambda 条件或条件对象（同第 7 节）。没有条件的 UPDATE、DELETE 直接报错，确实要改整张表时写 `.all()`。全局过滤器和逻辑删除的条件照常加进 WHERE，`unfiltered()`、`unfiltered("tenant")`、`withDeleted()` 同查询。
- **WHERE 里的导航**：`where(l -> l.mara().manufacturer().eq("MK1"))` 写成 `EXISTS (SELECT 1 FROM (SELECT 1) seed LEFT JOIN MARA ... WHERE ...)`，与查询里导航的 LEFT JOIN 语义相同；SET 里不能用导航，要读别的表时用标量子查询或联表更新。

### 按主键更新与删除、乐观锁

```java
db.execute(update(Doc.class).byId(new DocRow("D1", "b", 1)));
// UPDATE APP.DOC DOC SET TITLE = ?, VERSION = VERSION + 1 WHERE DOC.ID = ? AND DOC.VERSION = ?
db.execute(update(Doc.class).skipEmpty().byId(row));   // 只 SET 行里非空的属性
db.execute(deleteFrom(Doc.class).byId(row));           // WHERE 主键（和版本）
```

`byId` 的行必须带主键（有版本列时还要带版本），每行一条语句，每条期望改到 1 行，否则抛 `UnexpectedRowCountException`：版本不对就是被别人改过（乐观锁冲突）。

**差异更新**：`update(Doc.class).byId(edited).diffFrom(loaded)` 与修改前的行逐个属性比较（BigDecimal 按数值），只 SET 变了的；完全没变的行不发语句、版本不加、也不计入期望的行数。修改前的行与 byId 的行一一对应，主键和版本必须相等。`changedRows()` 返回会被写的那些行。

### 实体追踪

```java
db.tracking(t -> {
    DocBean d = t.one(Doc.class, from(Doc.class).where(x -> x.id().eq("D1")).selectAs(DocBean.class));
    d.setTitle("new");
});   // UPDATE APP.DOC DOC SET TITLE = ?, VERSION = VERSION + 1 WHERE DOC.ID = ? AND DOC.VERSION = ?

DocBean saved = db.tracked(t -> { ...; return d; });   // 要返回结果时用 tracked
```

- 范围里用 `t.one` / `t.optional` / `t.list(表, 查询)` 读，或 `t.track(表, 行)` 登记已有的行；登记时记下快照（浅拷贝）。范围正常结束时，变了的行按表分组写成 `update(表).byId(行).diffFrom(快照)`，在一个事务里执行；work 抛出异常时什么也不写。
- 同一张表、同一主键只登记一份，再次读到时返回先登记的对象（库里的新值不覆盖范围内的修改）；`t.forget(row)` 取消登记，例如已经删除了它。
- 写回成功后，变了的行的版本属性加一（与库里一致）并刷新快照；写回失败（例如乐观锁冲突抛出 `UnexpectedRowCountException`）时对象和快照都不动，可以重新读再试。
- 行必须是有无参构造器的可变类（可以用 Lombok），带表的主键属性（有版本列时还要带版本属性），属性都是表的列；record 不可变，不能登记。快照是浅拷贝，改值时换成新对象，不要原地改数组或集合。
- 范围绑定在当前线程上，嵌套调用加入外层，由最外层写回；在外层事务（`db.transaction`、`@Transactional`）里时写回加入那个事务，之后外层回滚不会撤销对象上已写回的版本。
- 不做级联、`save` 自动判断插入或更新、懒加载；插入和删除仍然显式写。

### 取回生成的主键

```java
List<Long> ids = db.insertKeys(insertInto(Seq.class).values(new SeqRow("a"), new SeqRow("b")));
```

按行的顺序返回 `@Generated` 列的值。PostgreSQL、MySQL 用 `getGeneratedKeys`；HANA 在同一连接上执行 `SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY`，所以逐行执行。

### 插入或更新

```java
insertInto(Doc.class).values(row).onConflict().update(d -> d.title());   // 主键冲突时只更新标题
insertInto(Doc.class).values(row).onConflict().update();                 // 更新键以外的全部列
insertInto(Doc.class).values(row).onConflict(d -> d.id()).doNothing();   // 冲突时保留原来的行
```

`onConflict()` 不写键时用 `@Id` 的列，键必须是插入的列。HANA 写成 `MERGE INTO ... USING (SELECT ? AS ... FROM DUMMY)`，PostgreSQL 写成 `ON CONFLICT (...) DO UPDATE SET c = EXCLUDED.c`（键上要有主键或唯一约束），MySQL 写成 `... AS new ON DUPLICATE KEY UPDATE c = new.c`。

### 加锁读

```java
from(Task.class).where(t -> t.assignee().isNull()).orderBy(t -> t.createTime()).limit(1)
    .with(forUpdate(Lock.SKIP_LOCKED))     // 或 forUpdate()、forUpdate(Lock.NOWAIT)
```

写在语句末尾；HANA 的 `SKIP_LOCKED` 写作 `IGNORE LOCKED`。锁只在事务里有意义，放进 `db.transaction(...)` 或 `@Transactional`。

### 事务

```java
db.transaction(() -> {
    db.execute(...);
    db.list(...);              // 同一个连接，看得到自己的写入
});                            // 正常返回时提交，抛出异常时回滚
```

不用 Spring 时 `db.transaction(work)` 把一个连接绑定到当前线程，嵌套调用加入外层事务。用 `SpringConnections.of(dataSource, transactionManager)` 时由那个事务管理器执行（已有事务时加入）；只给了 DataSource 时 `transaction(...)` 报错，事务交给 `@Transactional`。见第 1 节。

### 逻辑删除

带 `@LogicDelete` 的表：

- 查询、连接、关系、子查询和 UPDATE 里都自动加上"未删除"的条件（`DOC.DELETED = 0`），与全局过滤器一样由 `unfiltered()` 关闭；
- `deleteFrom(Doc.class).where(...)` 改写成 `UPDATE ... SET DELETED = 1 WHERE DELETED = 0 AND ...`，`byId` 删除时版本同时加一；`.physically()` 才真正删除；
- 插入时行里没有这一列，就写入未删除的值。

### 审计字段自动填充

```java
public interface Audited extends Entity {
    @Column("CREATED_AT") Col<LocalDateTime> createdAt();
    @Column("UPDATED_AT") Col<LocalDateTime> updatedAt();
    @Column("UPDATED_BY") Col<String> updatedBy();
}

Hana.database(dataSource, c -> c
    .onInsert(Audited.class, a -> a.createdAt(), LocalDateTime::now)
    .onUpdate(Audited.class, a -> a.updatedAt(), currentTimestamp())     // 也可以是表达式
    .onUpdate(Audited.class, a -> a.updatedBy(), currentUser::name));
```

逐行生成的列用 `generate(Keyed.class, k -> k.id(), ids::next)`：只填行里为 null（或行里没有这个属性）的行，每行调用一次；生成的值不写回行对象，之后还要用这个值时在代码里先生成。

按表接口的父接口匹配（同全局过滤器）。插入时填 `onInsert` 的列；更新、`byId` 更新、插入或更新的更新部分、逻辑删除都填 `onUpdate` 的列。填充的值每条语句取一次，总是覆盖行里或 SET 里的同名项；迁移数据要保留原值时，在语句上写 `.withoutFills()`。`toSql(dialect)` 没有数据库实例，所以不带填充；要看实际执行的 SQL 用 `db.render(write)`。

### 联表更新与删除（方言包）

```java
import static org.dpdns.zerodep.query.postgres.Postgres.*;   // 或 Mysql、Hana

db.execute(updateJoin(Note.class, Doc.class, (n, d) -> n.id().eq(d.id()))
    .set((n, d) -> n.title().to(d.title()))
    .where((n, d) -> d.version().gt(1)));
db.execute(deleteJoin(Note.class, Doc.class, (n, d) -> n.id().eq(d.id())).where((n, d) -> d.title().eq("gone")));
```

| 方言 | 联表更新 | 联表删除 |
| --- | --- | --- |
| PostgreSQL | `UPDATE 表 SET 列 = ... FROM 连接表 WHERE 连接条件 AND ...` | `DELETE FROM 表 USING 连接表 WHERE ...` |
| MySQL | `UPDATE 表 JOIN 连接表 ON ... SET 别名.列 = ... WHERE ...` | `DELETE 别名 FROM 表 JOIN 连接表 ON ... WHERE ...` |
| HANA | `MERGE INTO 表 USING (SELECT 主键, 新值 FROM 表 JOIN 连接表 ...) ON (主键相等) WHEN MATCHED THEN UPDATE SET ...`，被改表要有 `@Id` | `DELETE FROM 表 WHERE EXISTS (SELECT 1 FROM 连接表 WHERE ...)` |

- 连接是内连接；连接表的全局过滤器和逻辑删除条件并进连接条件，`unfiltered(...)`、`withDeleted()` 对两张表一起生效。
- **每个被改行至多匹配一个连接行**：多于一行时 PostgreSQL、MySQL 任取一行（结果不确定），HANA 报 689、整条语句不生效。三库语义不同，所以入口只在方言门面上。
- 没有 where 条件时报错（连接条件不算），要改所有连接得上的行写 `.all()`；不能经过导航关系，要用的表直接连接。
- 被改表有 `@LogicDelete` 时，`deleteJoin` 改写成联表更新，`.physically()` 才真正删除；SET 照常加上更新时的审计填充。
- 只是条件要看别的表时，核心的 `where(t -> exists(...))` 或一对多的 `any()` 三库都能用，不必用联表写入。

### 执行与测试

监听器、`printSql`、`db.capture(...)` 和空跑连接都覆盖写语句；空跑时影响的行数为 0，不检查 `expect`。

## 16. 结构变更

设计见 [0008](decisions/0008-schema-tracking-sharding.md)。表接口除了查询需要的列名，还可以声明建表需要的信息：

```java
@Table(schema = "APP", value = "S_ITEM", comment = "items")
@Index(name = "IX_S_ITEM_CODE", columns = "code", unique = true)
@ForeignKey(name = "FK_S_ITEM_OWNER", columns = "ownerId", references = Owner.class)
public interface Item extends Entity {
    @Id @Generated @Column("ID") Col<Long> id();
    @Column(value = "CODE", length = 20, nullable = false) Col<String> code();
    @Column(value = "PRICE", precision = 13, scale = 3) Col<BigDecimal> price();
    @Column(value = "STATUS", length = 10, nullable = false, defaultValue = "'open'") Col<String> status();
    @Column("OWNER_ID") Col<Long> ownerId();
}
```

- `@Column` 的 `length`（文本默认 255）、`precision` / `scale`（小数默认 `DECIMAL(18,2)`）、`nullable`（主键总是 NOT NULL）、`defaultValue`（SQL 文本）、`lob`（不限长度）、`comment`、`previousName`（改名前的列名）只用于结构变更，查询不读它们。
- 类型按存储类型（经过转换器之后）推断：文本、整数、长整数、小数、浮点、布尔、日期、时间戳、字节；`@Generated` 的列建成 IDENTITY / AUTO_INCREMENT。
- 不归本库管的表（SAP 表）标 `@Table(external = true)`：只核对已声明的列存在、类型相容，从不生成 DDL。

| 写法 | 说明 |
| --- | --- |
| `db.schema().check(Item.class, ...)` | 声明与实库的差异（`SchemaPlan`），`isEmpty()` 为真表示一致；适合在启动时或 CI 里调用 |
| `db.schema().plan(...)` | 差异和消除差异的语句：`statements()`、`toSql()`（每条以分号结尾，交给 DBA 或 Flyway）、`apply()` 执行 |
| `plan.allowDestructive().apply()` | 连有风险的语句一起执行 |
| `db.schema().createTable(Item.class)` | 只渲染建表语句，不连库 |
| `db.schema().read("APP", "S_ITEM")` | 读出实库的结构（`TableDef`） |

每项差异分三类：

- **安全的**，`apply()` 自动执行：建表（HANA 建列存表）、加列、放宽类型（同族加长、整数加宽、小数位数不减）、按 `previousName` 改名、允许 NULL、改默认值、加索引和外键（外键排在最后）。
- **有风险的**，`allowDestructive()` 之后才执行：缩短或改变类型、改成 NOT NULL、给已有的表加没有默认值的 NOT NULL 列（有行时会失败，应先加可空列、回填，再改 NOT NULL）。HANA 不能就地缩短列，这一项只报告。
- **只报告的**，从不生成语句：库里多出来的列和索引、主键或自增不同、外部表的问题。本库不删列。

一条语句执行一次。PostgreSQL 的整个计划在一个事务里执行；MySQL 的每条 DDL 隐式提交，HANA 的 DDL 默认自动提交，中途失败时已执行的语句不会回滚。

### 版本化迁移

```java
public final class V1 implements Migration {
    public String version() { return "2026.10.24.1"; }
    public String description() { return "create S_ITEM"; }
    public void up(MigrationSteps steps) {
        steps.createTable(TableBuilder.table("APP", "S_ITEM")
                .column("ID", ColumnType.of(Kind.BIGINT)).identity()
                .column("CODE", ColumnType.text(20)).notNull()
                .primaryKey("ID").index("IX_S_ITEM_CODE", true, "CODE").build())
            .sql(HanaDialect.class, "ALTER TABLE APP.S_ITEM ...")          // 只在 HANA 上执行
            .execute(insertInto(Code.class).values(seedRows));             // 数据变更用写语句
    }
}

Migrator migrator = db.migrator(List.of(new V1(), new V2())).historySchema("APP");
migrator.plan();             // 还没执行的迁移各自的语句，不执行
migrator.migrateToLatest();  // 执行，返回执行了的版本
migrator.verify();           // 已执行的迁移被改过、历史里有未知的版本、中间有空缺时报错
```

- 迁移用代码写，一份迁移在三个方言上各自渲染；表结构用 `TableBuilder` 手写，不引用表接口（表接口以后会改，旧迁移的含义不能跟着变）。也可以 `Migration.of(version, description, steps -> ...)`。
- 按列表顺序执行，每个版本一次，记进历史表 `QUERY_MIGRATION`（版本、说明、语句的 SHA-256、状态、时间）；锁表 `QUERY_MIGRATION_LOCK` 的一行让同时启动的实例互斥（`lockTimeout(...)`，默认一分钟）。
- 同一个库里有几套各自迁移的表时（例如建在本库上的库自带迁移），用 `historyTable("flow_migration")` 换历史表的名字，锁表跟着叫 `flow_migration_lock`（名字有大写时加 `_LOCK`），各记各的版本。
- PostgreSQL 每个迁移在一个事务里执行，失败时整体回滚。MySQL、HANA 的 DDL 逐条提交，失败的迁移记为 failed，修好数据库后 `repair(version)` 再重新执行；所以一个迁移最好只做一件事。
- `baseline(version)` 把这个版本之前的迁移记为已执行而不执行，用于接管手工建好的库；`migrateTo(version)` 只执行到这个版本。
- 只向前，没有回滚迁移；要撤销就再写一个新的迁移。迁移适合作为部署前的一次性任务运行，而不是在每个应用副本启动时运行。
- 已经在用 Flyway、Liquibase 的项目不必用它：`db.schema().plan(...).toSql()` 生成的脚本交给它们，`check(...)` 在启动时核对。

### 从数据库生成列（query:pull）

已有的表（SAP 宽表、别的系统建的表）不必手写每一列：Maven 插件从数据库读出结构，生成只有列的父接口，自己的表接口继承它，再写关系、过滤器和覆盖的注解。

```xml
<plugin>
  <groupId>org.dpdns.zerodep</groupId>
  <artifactId>query-maven-plugin</artifactId>
  <version>2.0.0</version>
  <configuration>
    <dialect>hana</dialect>                       <!-- hana、postgres 或 mysql -->
    <url>jdbc:sap://host:39041</url>
    <user>${env.HANA_USER}</user>
    <password>${env.HANA_PASSWORD}</password>   <!-- 用环境变量，不写明文 -->
    <schema>SAPHANADB</schema>
    <packageName>com.example.erp.generated</packageName>
    <tables>
      <table><name>LIPS</name><columns>VBELN,POSNR,MATNR,LFIMG</columns></table>
      <table><name>MARA</name><typeName>MaterialColumns</typeName></table>
    </tables>
  </configuration>
</plugin>
```

```java
// mvn query:pull 生成 src/generated/java/com/example/erp/generated/LipsColumns.java：
public interface LipsColumns extends Entity {
    /** VBELN：TEXT(10)，主键 */
    @Id @Column(value = "VBELN", length = 10) Col<String> vbeln();
    // ...
}

// 自己写的表接口
@Table(schema = "SAPHANADB", value = "LIPS", external = true)
public interface Lips extends LipsColumns {
    default One<Mara> mara() { return one(Mara.class, (l, m) -> m.matnr().eq(l.matnr())); }
}
```

- 插件带着三个 JDBC 驱动，只在运行 `query:pull` 时连库，构建本身不连库；生成的文件提交进版本库，生成目录加进编译的源码根目录。
- `columns` 只生成列出的列（宽表通常只用几列），不写时生成全部列；`typeName` 缺省由表名推导（`S_ITEM` → `SItemColumns`），列方法名是列名的驼峰写法，与 Java 关键字或 `Entity` 的方法重名时加 `Column`。
- `mvn query:pull -Dquery.only=LIPS` 只重新生成某几张表；内容没变的文件不重写。
- 生成的注解带长度、精度、可空、默认值和注释，所以继承它的表接口可以直接用 `db.schema().check(...)` 核对。三个库读回同一张表时生成的源码完全相同（默认值统一成 SQL 写法，例如 `'open'`）。

## 17. 分区与分表

设计见 [0008](decisions/0008-schema-tracking-sharding.md) 第 6 条。按时间或客户把大表拆开有三种做法，本库支持前两种：

### 原生分区

表还是一张，由数据库按分区键裁剪；查询和写入完全不变，只在建表时加上方言的分区子句：

```java
@Table(schema = "APP", value = "EVENT_LOG")
@TableOption(dialect = PostgresDialect.class, value = "PARTITION BY RANGE (\"DAY\")")
@TableOption(dialect = MysqlDialect.class, value = "PARTITION BY RANGE COLUMNS(DAY) (PARTITION p2026 VALUES LESS THAN ('2027-01-01'), PARTITION pmax VALUES LESS THAN (MAXVALUE))")
@TableOption(dialect = HanaDialect.class, value = "PARTITION BY RANGE (DAY) (PARTITION '2026-01-01' <= VALUES < '2027-01-01', PARTITION OTHERS)")
public interface EventLog extends Entity {
    @Id @Column("ID") Col<Long> id();
    @Id @Column("DAY") Col<LocalDate> day();      // 三个库都要求分区键在主键里
    @Column("AMOUNT") Col<BigDecimal> amount();
}
```

- `@TableOption` 的文本原样加在 CREATE TABLE 末尾，只在对应的方言（含子类）上生效；迁移里用 `TableBuilder.option(dialect, sql)`。比较实库时不核对它。
- PostgreSQL 的分区要另外建：`CREATE TABLE ... PARTITION OF ... FOR VALUES FROM (...) TO (...)` 或 `DEFAULT`，写在迁移的 `sql(PostgresDialect.class, ...)` 里。MySQL 分区表不能有外键。
- 按时间拆表时优先用它。

### 同库物理分表

已经拆成多张表（例如每月一张 `EVENT_202601`）时，在数据库实例上注册规则，查询和写入照常写逻辑表：

```java
Database db = Postgres.database(dataSource, c -> c
    .shard(Event.class, ShardRule.monthly("day", YearMonth.of(2025, 1), YearMonth.of(2026, 12)))   // EVENT_202501 .. EVENT_202612
    .shard(Order.class, ShardRule.modulo("customer", 8)));                                       // ORDER_0 .. ORDER_7

db.list(from(Event.class).where(e -> e.day().range(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 1))).select(...));
// FROM (SELECT 列 FROM EVENT_202601 UNION ALL SELECT 列 FROM EVENT_202602) event WHERE event.DAY >= ? AND event.DAY < ?
db.execute(insertInto(Event.class).values(rows));     // 每行写到它那个月的表
```

| 写法 | 说明 |
| --- | --- |
| `ShardRule.monthly(key, first, last)` | 每月一张 `表名_yyyyMM`；键是 `LocalDate` / `LocalDateTime`；等值、IN、范围都能选表 |
| `ShardRule.modulo(key, n)` | `表名_0` 到 `表名_(n-1)`；整数键取模；等值、IN 能选表 |
| `ShardRule.custom(key, tables, value -> table)` | 自己给出物理表和映射 |
| `from(...).allShards()` / `.onShards("EVENT_202601")` | 不按条件、读全部或指定的分表；子查询没有自己的选择时沿用 |
| `update(...)` / `deleteFrom(...)` 的 `allShards()`、`onShards(...)` | 同上，用于写语句 |

- **选表**：只认 WHERE 里分表键与常量或参数的 `=`、`IN`、`BETWEEN`、`<`、`<=`、`>`、`>=`，AND 取交、OR 取并。推不出来时报错，提示加分表键条件或 `allShards()`，免得悄悄扫全部分表。选中一张时直接写那张表，多张时写成 `UNION ALL` 派生表，聚合、排序、分页都交给数据库；三个库都会把外层的其余条件推进各分支（见 0008 的验证）。选不出任何表时写成取不到行的派生表。
- **写入**：插入按行的分表键写到对应的表；`update` / `deleteFrom` 按条件选表，每张表一条语句，期望行数按总数核对；`byId` 按行里分表键的值选表（行里没有分表键属性时要 `allShards()`）。改分表键报错（要先删再插）；`insertInto(...).from(query)` 和联表更新、删除不支持分表。
- **限制**：一段 SELECT 里最多一张分表（另一张放进 CTE 或单独查询）；所有分表在同一个库，事务不变。
- **建表**：`db.schema().plan(Event.class)` 按规则里的每张物理表比较和建表，索引和外键名加上同样的后缀；新的月份加进规则后再 `plan` 就会建出来。
- **跨库分片**交给 ShardingSphere-JDBC 之类的数据源，本库无感知。

## 18. SQLite

`query-sqlite` 用 `org.xerial:sqlite-jdbc`（使用方自己引入）。设计、实测的语义差异和取舍见 [0009](decisions/0009-sqlite-dialect.md)，TypeScript 端与浏览器里的用法见 [TypeScript 的第 19 节](api-typescript.md#19-sqlite-与浏览器)。

```java
import static org.dpdns.zerodep.query.sqlite.Sqlite.*;

SQLiteConfig config = new SQLiteConfig();
config.enforceForeignKeys(true);       // 每个连接打开外键检查
config.setBusyTimeout(5_000);          // 只有一个写者：等别的连接释放写锁
SQLiteDataSource dataSource = new SQLiteDataSource(config);
dataSource.setUrl("jdbc:sqlite:app.db");

SqliteDatabase db = Sqlite.database(dataSource, c -> c.schema("ERP", "main"));
db.list(from(Item.class).where(i -> glob(i.code(), "A*")).select(i -> median(i.price())));
```

- **schema** 是 ATTACH 进来的库名：要么在取连接时 ATTACH 同名的库，要么用 `schema("ERP", "main")` 映射到主库。
- **类型**：小数列按 NUMERIC 亲和存储，比较和排序是数值的；但有效数字超过 15 位或要精确求和（金额）时不精确，金额建议存整数的最小单位。日期和时间戳存成 `YYYY-MM-DD`、`YYYY-MM-DD HH:MM:SS.fff` 文本。
- **语义差异**：`LIKE` 不区分 ASCII 大小写（与 MySQL 默认相同），要区分时用 `glob(e, "A*")`；`currentTimestamp()` 是本地时间。其余函数与三库语义一致，写法见金样例 `spec/render/*.sqlite.sql`。
- **超时**：sqlite-jdbc 不理会 `setQueryTimeout`，所以 `queryTimeout(...)` 到时由本库调用 `Statement.cancel()` 中断语句。
- **需要编译选项的函数**：`sqrt`、`power`、`ceil`、`floor`、`mod` 和门面的 `median`、`percentileCont(p, e)`、`percentileDisc(p, e)` 照常渲染，每个数据库实例对每个函数第一次渲染时记一条警告（`System.Logger` `org.dpdns.zerodep.query.sqlite`）。sqlite-jdbc 的构建都带这些函数。
- **不支持的**：ROLLUP / GROUPING SETS、`grouping()`、`forUpdate()` 渲染时抛出 `UnsupportedOperationException`。SQLite 只有一个写者，在写事务里读写即可。
- **结构变更**：自增主键写成 `INTEGER PRIMARY KEY AUTOINCREMENT`；外键只能写在建表语句里；改列的类型、可空、默认值和给已有的表加外键都只报告（REBUILD：新建表、搬数据、改名）；没有注释，建表时略过并警告一次。`query:pull` 的 `dialect` 写 `sqlite`。
- 联表写入：`updateJoin` 写成 `UPDATE ... FROM`，`deleteJoin` 写成 `WHERE EXISTS`。

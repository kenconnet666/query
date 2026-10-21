# API 使用说明（Java）

本文是第一期 Java API 的用法。设计理由见 [0002](decisions/0002-api-architecture.md)。文中示例都能在测试里找到对应的可运行版本：`java/query-core/src/test/java/.../testing/Scenarios.java`，以及三个方言模块的 `*LiveTest`。

TypeScript 版本见 [API 使用说明（TypeScript）](api-typescript.md)。两端渲染出的 SQL 由 `spec/render/` 的金样例保证一致。

## 1. 依赖与数据库实例

选定方言，引入对应的方言包。方言包会传递依赖核心包，JDBC 驱动由使用方自己提供。

```xml
<dependency>
    <groupId>org.dpdns.zerodep.query</groupId>
    <artifactId>query-hana</artifactId>      <!-- 或 query-postgres、query-mysql -->
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

每个数据源创建一个方言实例，作为长期存在的单例使用：

```java
import static org.dpdns.zerodep.query.hana.Hana.*;   // 一次导入：核心函数加 HANA 函数

HanaDatabase db = Hana.database(dataSource, c -> c
    .filter(ClientEntity.class, r -> r.col(ClientEntity::getClient).eq("100"))  // 全局过滤器
    .cache(QueryCache.inMemory())                                               // 结果缓存（按查询开启）
    .listener(e -> log.debug("{} {}ms", e.statement().sql(), e.elapsed().toMillis()))
    .naming(NamingStrategy.UPPER_SNAKE));        // 没写 @Column 的属性按此推导列名
```

| 方言 | 门面（静态导入） | 实例 |
| --- | --- | --- |
| SAP HANA | `org.dpdns.zerodep.query.hana.Hana` | `HanaDatabase` |
| PostgreSQL | `org.dpdns.zerodep.query.postgres.Postgres` | `PostgresDatabase` |
| MySQL 8 | `org.dpdns.zerodep.query.mysql.Mysql` | `MysqlDatabase` |

`database(...)` 可以接收 `DataSource`，也可以接收自定义的 `ConnectionProvider`，例如加入外部事务时使用。

## 2. 声明实体

实体就是普通的类：Lombok `@Data` 类、JavaBean 或 record 都可以。只需声明查询用到的列。

```java
@Data
public abstract class ClientEntity {                 // 公共列放在基类
    @Column("MANDT") private String client;
}

@Data @EqualsAndHashCode(callSuper = true) @FieldNameConstants
@Table(schema = "ERP", value = "LIPS")
public class Lips extends ClientEntity {
    @Column("VBELN") private String delivery;
    @Column("MATNR") private String material;
    @Column("ERDAT") @SapDate private LocalDate createdOn;   // 组合注解挂载转换器
    @Column("LFIMG") private BigDecimal quantity;
}

public record MaterialScope(String client, String material, String partNumber) { }   // 临时实体：没有 @Table
```

| 注解 | 作用 |
| --- | --- |
| `@Table(value, schema)` | 标记表实体 |
| `@Column(value, converter)` | 列名；可选的转换器 |
| `@ConvertWith(Converter.class)` | 元注解，用来组合出自己的类型注解，例如 `@SapDate` |
| `@MapFrom(value = Lips.class, property = Lips.Fields.material)` | DTO 字段的值来自哪个实体的哪个属性；属性名用 Lombok 常量，改名后编译失败 |
| `@Ignore` | 不参与映射的字段 |

自定义类型：实现 `Converter<J, D>`，再声明一个组合注解。

```java
public final class SapDateConverter implements Converter<LocalDate, String> {
    public String toDatabase(LocalDate v) { return v.format(DateTimeFormatter.BASIC_ISO_DATE); }
    public LocalDate fromDatabase(String v) { return v.isBlank() || v.equals("00000000") ? null : LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE); }
}

@Retention(RUNTIME) @Target(FIELD) @ConvertWith(SapDateConverter.class)
public @interface SapDate { }
```

字段的值与比较时的参数都会经过转换器，例如 `l.col(Lips::getCreatedOn).between(start, end)` 绑定的是 `'20260101'`。

## 3. 写查询

`from` 开启一个作用域，每 `join` 一张表，lambda 就多一个类型化参数（最多 8 张表）。

```java
Query<Delivery> q = from(Lips.class)
    .join(Mara.class, (l, ma) -> ma.col(Mara::getMaterial).eq(l, Lips::getMaterial))
    .leftJoin(Makt.class, (l, ma, mk) -> mk.col(Makt::getMaterial).eq(l, Lips::getMaterial).and(mk.col(Makt::getLanguage).eq("1")))
    .where((l, ma, mk) -> all(
        l.col(Lips::getCreatedOn).between(start, end),
        ma.col(Mara::getManufacturer).eqIfPresent(maker),               // maker 为 null 时省略
        exists(from(Vbkd.class).where(v -> v.col(Vbkd::getSalesOrder).eq(l, Lips::getSalesOrder)).selectOne())))
    .groupBy((l, ma, mk) -> keys(ma.col(Mara::getPartNumber)))
    .orderBy((l, ma, mk) -> orders(ma.col(Mara::getPartNumber).asc()))
    .select((r, l, ma, mk) -> new Delivery(
        r.get(ma, Mara::getPartNumber),
        r.get(max(mk.col(Makt::getText).coalesce(ma.col(Mara::getPartNumber)))),
        r.get(sum(l.col(Lips::getQuantity)))));

List<Delivery> rows = db.list(q);
```

作用域上的方法：

| 方法 | 说明 |
| --- | --- |
| `join` / `leftJoin(Entity.class 或 Source, (…) -> cond)` | 连接条件里可以用到作用域内所有的表 |
| `crossJoin(...)` | 笛卡尔积 |
| `where`、`whereIf(test, …)`、`having` | 条件；`Cond.EMPTY` 会被自动丢弃 |
| `groupBy(… -> keys(...))`、`orderBy(… -> orders(...))` | 分组与排序 |
| `distinct()`、`limit(n)`、`offset(n)` | — |
| `unfiltered()` | 本查询不加全局过滤器 |
| `with(clause)` / `with(… -> clause)` | 方言子句，例如 `Hana.hint(...)` |

- 自连接时，第二个同名实体就是下一个参数，别名在整条语句里自动唯一。
- 关联子查询直接捕获外层 lambda 的参数。

## 4. 投影：三种写法

| 写法 | 结果 | 用途 |
| --- | --- | --- |
| `select((r, a, b) -> new X(...))` | `Query<X>` | 默认写法。lambda 里用 `r.get(expr)` 或 `r.get(ref, Getter)` 读值，构造任意对象：record、Builder、嵌套 DTO，也可以做 Java 计算 |
| `selectAs(VO.class)` | `Query<VO>` | 按同名属性从作用域里的表自动取列；改了名的属性用 `@MapFrom` 指定来源 |
| `selectAs(Shape.class, (a, b) -> keys(...))` | `Query<Shape>` | 显式列，每列用 `.as(Shape::getX)` 命名，或直接沿用属性名；必须恰好覆盖 Shape 的全部属性 |
| `selectValue(a -> expr)` / `selectOne()` | `Query<T>` | 单列查询；也可以作为标量子查询或 `IN` 子查询 |

函数式投影会先运行一次 lambda 记录读取顺序，然后每行重放一次。不要在 lambda 里根据某个列的值决定是否读取另一列，这种情况会被检测到并报错。

同一个查询可以映射到不同的类：

```java
Query<Delivery> q = ...;
q.mapTo(DeliveryVO.class);          // 按列标签映射：record、@Data（含链式 setter）、@Builder、标量
q.map(d -> d.partNumber());          // 在映射结果上再套一层 Java 函数
```

标签匹配时忽略大小写和下划线，所以 `PART_NUMBER` 能映射到 `partNumber`。查询多出来的列会被忽略；目标属性缺少对应列时报错。数值类型宽松转换，例如 BigDecimal 可以赋给 Double。

## 5. CTE、派生表、UNION、窗口

```java
Cte<MaterialScope> scope = cte("material_scope", from(Mara.class)
    .where(ma -> ma.col(Mara::getMaterialType).eq("ZMAT"))
    .distinct()
    .selectAs(MaterialScope.class, ma -> keys(
        ma.col(Mara::getClient), ma.col(Mara::getMaterial),
        normalizedPart(ma.col(Mara::getPartNumber)).as(MaterialScope::partNumber))));

from(scope).join(Lips.class, (ms, l) -> l.col(Lips::getMaterial).eq(ms, MaterialScope::material))...
```

- **CTE**：只有 `selectAs` 的查询（列有名字）能作为 CTE。无论被引用多深，渲染时都只在语句开头的 `WITH` 里出现一次。
- **派生表**：`from(query.asTable())`。
- **UNION**：`q1.unionAll(q2)` 或 `union`。两段的结果类型必须相同，否则编译失败；分页和排序加在合并之后。
- **窗口**：`rowNumber().over(w -> w.partitionBy(a).orderBy(b.desc()))`，聚合也可以 `.over(...)`，ROWS 框架用 `w.rows(Bound.unboundedPreceding(), Bound.currentRow())`。
- **常量行**（码表）：`values(Shape.class, List.of(new Shape(...), ...))` 得到一个查询，每行渲染成一个字面量 SELECT，用 UNION ALL 连接，HANA 上加 `FROM DUMMY`。可以用 `cte(...)` 包成 CTE，再参与连接。

```java
record Bucket(String name, Integer ord) { }
Cte<Bucket> buckets = cte("buckets", values(Bucket.class, List.of(new Bucket("未逾期", 1), new Bucket("逾期两个月内", 2))));
```

业务口径写成普通方法，用接口约束适用的表：

```java
interface PeriodStock { String getClient(); String getMaterial(); String getYear(); String getPeriod(); }

static <E extends PeriodStock> Query<StockRow> stockArm(Class<E> table, Fn.F1<Ref<E>, Expr<BigDecimal>> quantity, String year) {
    return from(table).where(h -> h.col(PeriodStock::getYear).eq(year))
        .select((r, h) -> new StockRow(r.get(h, PeriodStock::getMaterial), r.get(h, PeriodStock::getYear),
            r.get(h, PeriodStock::getPeriod), r.get(quantity.apply(h))));
}

stockArm(Mardh.class, h -> h.col(Mardh::getUnrestricted).coalesce(BigDecimal.ZERO), "2026")
    .unionAll(stockArm(Mchbh.class, h -> h.col(Mchbh::getBatchUnrestricted).coalesce(BigDecimal.ZERO), "2026"));
```

**两种常见写法**：

- **一组列求和**，例如十六个期间列：先把 getter 放进一个常量列表，再 `plus(PERIODS.stream().map(p -> f.col(p).coalesce(BigDecimal.ZERO)).toList())`。按期间展开成行，则是对同一个列表逐个生成 `select`，再用 `unionAll` 合并。
- **按条件追加的过滤**：需要连接另一张表才能判断时，写成可选的 EXISTS，不要按条件拼接 JOIN：`ifPresent(supplier, code -> exists(from(sup).where(s -> ...).selectOne()))`。这样作用域里的表不变，类型也不变。

## 6. 表达式与函数

**`Expr<T>` 的实例方法**（接收者固定 T，参数类型不符就编译失败）：

- 比较：`eq`、`ne`、`gt`、`ge`、`lt`、`le`。参数可以是值、表达式，或 `(ref, Getter)`。
- 区间与集合：`between`、`in(Collection | Query)`、`notIn`、`isNull`、`isNotNull`。
- 可选条件：`eqIfPresent` 等，以及 `betweenIfPresent`、`inIfPresent`。值为 null（集合为空）时省略该条件。
- 同类型函数：`coalesce`、`nullif`。
- 别名、排序与转换：`as("x")`、`as(VO::getX)`、`asc()` / `desc()`（加 `.nullsFirst()` / `.nullsLast()`）、`cast(CastType.decimal(15, 3))`。

**`Sql` 的静态函数**（各方言语义一致的部分）：

| 类别 | 函数 |
| --- | --- |
| 条件 | `all`、`any`、`not`、`onlyIf(test, () -> cond)`、`ifPresent(v, x -> cond)`、`exists`、`notExists`、`like`、`notLike`、`likeIfPresent` |
| 值 | `val(x)`（参数）、`lit(x)`（字面量，只用于固定代码值）、`nullOf(Type.class)` |
| 文本 | `upper`、`lower`、`trim`、`concat`（MySQL 自动渲染为 `CONCAT`）、`substring(e, start[, length])`、`left`、`right`、`replace(e, from, to)`；位置从 1 开始 |
| 数值 | `plus`（也接收 `List`）、`minus`、`times`、`divide`（仅 BigDecimal）、`negate`、`abs`、`round` |
| 聚合 | `count()`、`count(e)`、`countDistinct`、`sum`、`avg`、`min`、`max` |
| 窗口 | `rowNumber`、`rank`、`denseRank`、`lag`、`lead`，再加 `.over(...)` |
| 其他 | `caseWhen(cond, x).when(...).otherwise(...)`、`cast`、`scalar(query)`、`currentDate`、`currentTimestamp` |
| 兜底 | `sql(Integer.class, "DAYS_BETWEEN({0}, {1})", a, b)`、`sqlCond("{0} ~ {1}", a, b)` |

**方言专属能力**只在对应门面里提供，放到别的方言上渲染时会明确报错：

| HANA | PostgreSQL | MySQL |
| --- | --- | --- |
| `daysBetween`、`addDays`、`addMonths`、`addYears` | `distinctOn(...)` 子句 | `dateDiff`、`dateFormat` |
| `toVarchar(e, "YYYYMMDD")`、`toNvarchar`、`toDate` | `pg(e).isDistinctFrom(...)` / `isNotDistinctFrom` | `groupConcat(e, sep, order...)` |
| `likeRegexpr(e, pattern)` | — | — |
| `length`（按 UTF-16 单元计） | `ilike`、`filter(sum(x), cond)` | `maxExecutionTime(ms)` 优化器提示 |
| `stringAgg(e, sep, order...)`、`hint(...)` | `stringAgg(e, sep, order...)` | — |

**常量与参数**：

- SELECT 列表里的常量写成字面量；带 GROUP BY 或 DISTINCT 的查询，GROUP BY、HAVING、ORDER BY 里的常量也写成字面量。
- WHERE 和 ON 里的值仍然绑定为参数。
- 这样分组表达式（例如按 CASE 分桶）在三个库上都能执行，HANA 也能确定 SELECT 列的类型。
- 需要绑定的值写在 WHERE 中。

## 7. 执行

| 方法 | 说明 |
| --- | --- |
| `db.list(q)` | 全部行 |
| `db.one(q)` | 恰好一行，否则报错；最多读 2 行 |
| `db.first(q)` | `Optional`，最多读 1 行 |
| `db.stream(q)` / `db.forEach(q, …)` | 在一个游标上逐行读取；`stream` 要用 try-with-resources 关闭 |
| `db.count(q)` | 外包 `COUNT(*)` |
| `db.page(q, page, size)` | 页号从 1 开始，同时返回总数 |
| `db.sql("... :name IN (:ids)").bind(...).list(Type.class)` | 原生 SQL；集合参数自动展开；按列标签映射 |
| `db.render(q)` | 查看将要执行的 SQL 与参数 |
| `q.cached(Duration.ofMinutes(5))` | 结果按 SQL 和参数缓存（需要先配置 `cache`）；缓存的是解码后的原始行 |

**全局过滤器**按实体的父类型或接口匹配：FROM 中的表把条件加到 WHERE，JOIN 的表加到 ON（LEFT JOIN 语义不变）；CTE 和子查询里的表同样生效。

## 8. 扩展点

| 要扩展的 | 做法 |
| --- | --- |
| 值类型 | `Converter<J, D>` 加组合注解；或用 `config.type(Storage.class, reader, writer)` 覆盖某个存储类型的 JDBC 读写 |
| 函数 | 写一个静态方法：`call(FunctionDef.of("MY_FN"), Integer.class, args...)`。只属于某个方言的用 `FunctionDef.only(HanaDialect.class, "...")`；单个数据库实例需要改写时用 `config.function(def, renderer)` |
| 表达式节点 | 继承 `Expr<T>`，实现 `render(SqlWriter)` |
| 子句 | 实现 `Clause`，声明位置（`HINT` / `DISTINCT` / `END`）和所属方言 |
| 数据来源 | 实现 `Source<E>`，例如带参数的视图 |
| 方言 | 继承 `Dialect`（或某个现成方言），覆盖引号、字面量、分页、排序项、类型名、函数渲染和 JDBC 读写 |
| 连接与监听 | `ConnectionProvider`、`QueryListener`、`QueryCache` |

## 9. 编译期能拦住的错误

以下错误写法由 `CompileTimeSafetyTest` 在每次构建时用 javac 验证：

- 作用域里用了不在查询中的表的 getter；
- 比较值、连接键或 `coalesce` 参数的类型与列不符；
- 对文本列 `sum`；
- 投影构造器的参数类型不符；
- `.as(VO::getX)` 指向类型不同的属性；
- UNION 两段的结果类型不同；
- 泛型模板套用到缺少共享接口的表；
- 窗口函数没有 `over`。

Java 不跟踪可空性，所以结果属性要用包装类型。

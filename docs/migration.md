# 迁移指南

本文分两部分：[从手写 SQL 迁移](#从手写-sql-迁移)写给要把拼接的 SQL 文本（JdbcTemplate、MyBatis 注解、字符串模板）改写成本库查询的人；[从 1.x 升级到 2.0](#从-1x-升级到-20) 列出 2.0 删除和改名的 API。示例用 Java，TypeScript 的写法一一对应，见 [API 使用说明（TypeScript）](api-typescript.md)。

## 从手写 SQL 迁移

目标是**查询结果一致**，生成的 SQL 不必与原文相同。这里的写法来自对内部 ERP 分析大屏约 120 段 HANA SQL 的审计，以及 8 条代表性查询的结果对照（[0003](decisions/0003-road-to-1.0.md) 的 M1、M4）。

### 1. 步骤

1. **声明表**：给查询用到的表写表接口，只声明用到的列。各表共有的列（例如集团、公司代码）放进不带 `@Table` 的基接口，再配一个全局过滤器，查询里就不必每次都写。表之间常用的连接写成关系（`one(...)`、`many(...)`），查询里直接导航。
2. **按 CTE 逐段改写**：原 SQL 的每个 CTE 改成一个 `cte(name, ...selectAs(Shape.class))`，Shape 用 record 声明。复杂查询先改最里层的 CTE，每改完一段就和原 SQL 的同一段比较结果。
3. **对照结果**：原 SQL 和改写后的查询用同一组参数各执行一次，按主键排序后逐行比较；`db.render(q).debugSql()` 可以看到参数写回原处的 SQL，`db.capture(...)` 能拿到一段代码执行的全部语句。比较时要注意：
   - BigDecimal 用 `compareTo`，因为两边的小数位可能不同；
   - 原 SQL 里 `TRIM`、`NULLIF` 过的列，改写时用 `@Trimmed`、`@BlankAsNull` 或在表达式里同样处理；
   - 要覆盖每个条件分支，包括可选参数为空的情况。
4. **兜底**：暂时没法表达的片段用 `sql(Type.class, "模板", 参数...)`、`sqlCond(...)` 嵌入，一整段子查询用 `sqlTable(Row.class, "...")` 当作数据源；整条查询都没法表达时用 `db.sql("...").bind(...).list(Type.class)`。它们都按方言原样执行，用 `.on(方言类, 模板)` 可以给每个方言各写一份。

### 2. 一一对应的构造

下表的构造都有直接对应的写法，用法见 [API 使用说明](api.md)。

| 原 SQL | 改写 |
| --- | --- |
| `WITH x AS (...)` | `cte("x", query)`；被引用多次也只在 `WITH` 里出现一次 |
| `FROM (SELECT ...) t` | `from(query.asTable())` |
| `LEFT JOIN MARA m ON ...` 只为取几个描述列 | 表接口里声明 `default Mara mara() { return one(Mara.class, ...); }`，查询里写 `l.mara().partNumber()`，用到时才连接 |
| `EXISTS (SELECT 1 FROM 子表 WHERE 子表.键 = 父表.键 ...)` | 一对多关系：`h.items().any(i -> ...)`；或 `exists(from(Item.class).where(...))` |
| `(SELECT COUNT(*) FROM 子表 WHERE ...)`、`SUM` | `h.items().count()`、`h.items().sum(i -> i.quantity())` |
| 每组最新的一条（`ROW_NUMBER() ... = 1` 再连回来） | `h.items().first(i -> i.createdOn().desc()).delivery()` |
| `CASE col WHEN 'A' THEN ... END` | `caseOf(col).when("A", ...).otherwise(...)` |
| `CASE WHEN cond THEN ... END` | `caseWhen(cond, ...).when(...).otherwise(...)` |
| `COALESCE(a, b, c)` | `a.coalesce(b, c)` |
| `SUBSTRING(s, 1, LOCATE(s, ',') - 1)` | `substring(s, lit(1), minus(locate(s, ","), lit(1)))` |
| `col LIKE '%' \|\| :kw \|\| '%'` | `contains(col, kw)`，用户输入里的 `%`、`_` 按普通字符匹配 |
| `COUNT(*) OVER ()` 取分页总数 | `db.page(q, page, size)`，或 `count().over(...)` |
| `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)` | `rowNumber().over(w -> w.partitionBy(...).orderBy(...))` |
| `SELECT ... FROM DUMMY UNION ALL ...` 码表 | `values(Shape.class, List.of(...))`，HANA 上自动加 `FROM DUMMY` |
| `GREATEST`、`LEAST`、`TO_INT`、`DAYS_BETWEEN` 等方言函数 | 方言门面里的同名函数，例如 `Hana.greatest(a, val(0))` |
| 先查父行，再按父键查子行，在 Java 里拼成父项加子项列表 | `h.items().list(i -> row(Line::new, ...))`，或 `selectAs` 一个带 `List<X>` 属性的 VO；子行用一条按键批量的查询加载 |

### 3. 需要换一种写法的地方

#### UNION 之后排序

合并结果上不能直接排序。把 UNION 包成 CTE，再在外层排序：

```java
record Row(String material, BigDecimal quantity) { }

Cte<Row> rows = cte("rows", openOrders.unionAll(backorders));   // 两段都是 selectAs(Row.class) 或 .into(Row.class)
Query<Row> sorted = from(rows)
    .orderBy(r -> r.col(Row::material))
    .selectAs(Row.class);
```

#### 按请求参数拼接的条件、列和排序

- **条件**：请求参数写成一个 record，`where(search)` 把每个非空字段变成条件，字段用 `@Where` 指定列和比较方式；或者在作用域上开 `skipEmpty()`，与 null、空白字符串、空集合比较的条件自动省略。
- **"全部"选项**：在 Java 里把 `"ALL"` 换成 null，配合 `skipEmpty()` 或条件对象即可。
- **多分支的模式**：写成返回 `void` 的方法，在条件块里调用；`switch` 的各分支写各自的条件，不写就是没有条件。
- **用户选的列和维度**：查询里列出全部可选列，再用 `only(request.columns())` 或 `exclude(...)`，没选的列写成 NULL，相同的分组键一起去掉，只被它们用到的连接也不再出现。
- **用户给的排序**：`sortBy(request.sort())`，只认结果里的列名，不会拼进原样的文本。

```java
public record DeliverySearch(
    List<String> material,
    @Where(value = "createdOn", op = Where.Op.GE) LocalDate from,
    @Where(value = {"mara.partNumber", "text.text"}, op = Where.Op.CONTAINS) String keyword,  // 关系路径：方法名用点连接
    @Ignore String sort) { }
```

按状态的多分支模式：

```java
static void byStatus(Lips l, Mode mode) {
    switch (mode) {
        case OPEN -> l.status().ne("C");
        case CLOSED -> l.status().eq("C");
        case ALL -> { }
    }
}

from(Lips.class).where(l -> byStatus(l, mode))
```

#### 拼接出的列名

`"SUM(" + column + ")"` 这类按参数选择列的写法，改为在 Java 里选择列方法：

```java
Function<Mard, Expr<BigDecimal>> column = switch (kind) {
    case "unrestricted" -> Mard::unrestricted;
    case "blocked" -> Mard::blocked;
    default -> throw new IllegalArgumentException("Unknown stock kind: " + kind);
};
from(Mard.class)
    .groupBy(s -> s.material())
    .select(s -> row(Total::new, s.material(), column.apply(s).sum()));
```

#### 拼接出的表名

结构相同、表名不同的几张表（例如当期表和历史表），把共有的列放进基接口，每张表一个带 `@Table` 的子接口；列名不同的列在子接口里重新声明并带上自己的 `@Column`。查询写成泛型方法：

```java
public interface PeriodStock extends Entity {
    @Column("MATNR") ColText<String> material();
    @Column("LFGJA") ColText<String> year();
    ColDecimal<BigDecimal> quantity();
}
@Table("MARDH") public interface Mardh extends PeriodStock { @Override @Column("LABST") ColDecimal<BigDecimal> quantity(); }
@Table("MCHBH") public interface Mchbh extends PeriodStock { @Override @Column("CLABS") ColDecimal<BigDecimal> quantity(); }

static <E extends PeriodStock> Query<StockRow> stockArm(Class<E> table, String year) {
    return from(table)
        .where(h -> h.year().eq(year))
        .select(h -> row(StockRow::new, h.material(), h.year(), h.quantity().coalesce(BigDecimal.ZERO).as("quantity")));
}

stockArm(Mardh.class, "2026").unionAll(stockArm(Mchbh.class, "2026"));
```

#### 按条件拼接的 JOIN

"有供应商参数时才 JOIN 供应商表并加条件"，改成可选的 EXISTS。作用域里的表不变，类型也不变：

```java
from(Lips.class).skipEmpty().where(l -> {
    l.createdOn().between(start, end);
    if (vendor != null) exists(from(Ekko.class).where(e -> { e.order().eq(l.purchaseOrder()); e.vendor().eq(vendor); }));
});
```

只为取描述列的 JOIN 写成多对一关系就不必判断：没有用到时 SQL 里不会出现。

#### 模板拼出的 CTE

在多条 SQL 里用 `{{SCOPE}}` 之类的占位符复用同一段 CTE 的，改为返回 CTE 的方法：

```java
static Cte<MaterialScope> materialScope(String materialType) {
    return cte("material_scope", from(Mara.class)
        .where(ma -> ma.materialType().eq(materialType))
        .distinct()
        .selectAs(MaterialScope.class));
}
```

#### 运行时的值组成的常量行

在 Java 里拼出 `SELECT 'A' AS name, 1 AS ord FROM DUMMY UNION ALL ...` 的，改用 `values`，可以再包成 CTE 参与连接：

```java
record Bucket(String name, Integer ord) { }
Cte<Bucket> buckets = cte("buckets", values(Bucket.class, List.of(new Bucket("未逾期", 1), new Bucket("逾期两个月内", 2))));
```

#### 整数相除

整数相除时 PostgreSQL 取整（`7 / 2` 得 3），HANA 和 MySQL 得到小数（3.5），所以 `divide` 只接受 BigDecimal 的被除数。原 SQL 在 HANA 上依赖的是小数结果，改写时先把整数 CAST 成小数：

```java
l.delivery().count().cast(CastType.decimal(18, 4)).divide(count())
```

### 4. 已知的限制

- 一个作用域最多 16 张表（导航关系不占名额），`row(...)` 最多 22 个参数；超过时把一部分拆成 CTE 或嵌套的 `row(...)`。
- 带子列表（`list(...)`）的查询要读完全部父行才能加载子行，不能流式读取，也不能参与 UNION。
- 本库只做查询，写入继续用 JdbcTemplate 或原有的工具。

## 从 1.x 升级到 2.0

2.0 把表声明从带 getter 的类改成接口，列直接是方法，所以 lambda 里写 `l.material()` 而不是 `l.col(Lips::getMaterial)`；重复和复杂的写法删掉了，只留一种。TypeScript 的实体仍是带装饰器的类，小数和日期字段改成值对象，变化集中在下表的 TS 一列。

| 1.x | 2.0（Java） | 2.0（TypeScript） |
| --- | --- | --- |
| `@Table` 类加字段（Lombok `@Data`） | `@Table` 接口继承 `Entity`，列是返回列接口的抽象方法，接口按逻辑 SQL 类型选（`ColText<String>`、`ColDecimal<BigDecimal>`、以文本存的日期 `ColText<LocalDate>`），见 [API 第 2 节](api.md#2-声明表)；结果类、CTE 的行仍用 record 或类 | 实体写法不变；`decimal`、`date`、`timestamp` 列的字段从 `string` 改成 `Decimal`、`CalendarDate`、`CalendarDateTime`（新的 peerDependencies `decimal.js`、`@internationalized/date`），以文本存的日期写成 `Stored<CalendarDate, string>`，见 [TS API 第 2 节](api-typescript.md#2-声明实体) |
| `upper(e)`、`sum(e)`、`addDays(d, 3)` 等静态函数 | 主操作数的实例方法：`e.upper()`、`e.sum()`、`d.addDays(3)`；方言函数经由视图：`hana(e).toVarchar(fmt)` | 同 Java：`e.upper()`、`e.sum()`、`d.addDays(3)`、`hana(e).toVarchar(fmt)`（见 [0014](decisions/0014-typed-expressions.md)） |
| `all(a, b)`、`a.and(b)`、`any(a, b)`、`onlyIf`、`ifPresent` | 条件块一行一个条件：`where(l -> { a; b; })`、`any(() -> { a; b; })`，可选条件写 `if` | 同 Java：`where((l) => { a; b; })`、`any(() => { a; b; })`；返回 `Cond` 的辅助函数改成返回 `void` |
| `set(t -> t.x().to(v)).set(...)` | 赋值块：`set(t -> { t.x().to(v); t.y().to(w); })` | 不变：`set((t) => ({ x: v, y: t.y.plus(1) }))`，对象字面量本来就一次写多列 |
| `l.col(Lips::getMaterial)` | `l.material()`；CTE、派生表等行类来源仍是 `r.col(Row::x)` | 不变（`l.material`） |
| `a.eq(l, Lips::getMaterial)` 等简写 | `a.eq(l.material())` | 不变 |
| `eqIfPresent`、`likeIfPresent` 等 | 作用域上 `skipEmpty()`，或条件对象 `where(search)` | `skipEmpty()`，或 `where(search)` |
| `select((r, l) -> new X(r.get(...)))` 函数式投影 | `row(X::new, a, b, ...)`；按名取列用 `cols(...)`（Map 行）再 `.into(X.class)` | 对象投影不变 |
| `selectAs(X.class, l -> 额外列)` | `selectAs(X.class)` 只按同名取列；有计算列时用 `cols(...).into(X.class)` | `selectAs` 不变 |
| `@MapFrom` | 删除；改名用 `.as("name")` | 删除 |
| `mapTo(X.class)` | `into(X.class)` | `into(X)` |
| `selectValue(...)` | `select(l -> 表达式)` | `select((l) => 表达式)` |
| `Nest.into(...)` / `nest(...)` | `many.list(...)` 或带 `List<X>` 属性的 `selectAs` VO | `many.list(...)` |
| `SimpleCase` | `Case.Simple`（由 `caseOf(...)` 得到，写法不变） | 不变 |
| `coalesce(Expr)` 单参数重载 | `a.coalesce(b)`、`a.coalesce(b, c)` | 不变 |
| `Entity.all()` | `columns()` | 展开引用 `{ ...l }` |
| `sql(Type.class, 模板, Expr...)` | 参数可以混用表达式和值，集合展开；`.on(方言类, 模板)` | `sql(type)\`...\`` 支持数组展开和 `.on(方言类)\`...\`` |
| 作用域最多 8 张表 | 16 张，`row(...)` 22 个参数 | 不限 |

新增的能力：导航关系（`one`、`many`、`@ManyToOne`、`@OneToMany`，TS 的 `@one`、`@many`）、子列表与对象树、动态列与排序（`exclude`、`only`、`sortBy`）、条件对象、`contains` 等文本匹配、`sqlTable` 与 `identifier`、打印与测试 SQL（`debugSql`、`printSql`、`capture`、空跑）。见 [CHANGELOG](../CHANGELOG.md) 的 2.0.0 一节。

金样例（`spec/render/`）在 1.x 已有的场景上全部不变：同样的查询在 2.0 生成同样的 SQL 和参数。

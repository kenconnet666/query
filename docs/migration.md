# 从手写 SQL 迁移

本文写给要把服务里拼接的 SQL 文本（JdbcTemplate、MyBatis 注解、字符串模板）改写成本库查询的人。目标是**查询结果一致**，生成的 SQL 不必与原文相同。示例用 Java，TypeScript 的写法一一对应，见 [API 使用说明（TypeScript）](api-typescript.md)。

这里的写法来自对内部 ERP 分析大屏约 120 段 HANA SQL 的审计，以及 8 条代表性查询的结果对照（[0003](decisions/0003-road-to-1.0.md) 的 M1、M4）。

## 1. 步骤

1. **实体**：给查询用到的表写实体，只声明用到的列。各表共有的列（例如集团、公司代码）放进基类，再配一个全局过滤器，查询里就不必每次都写。
2. **按 CTE 逐段改写**：原 SQL 的每个 CTE 改成一个 `cte(name, ...selectAs(Shape.class, ...))`，Shape 用 record 声明。复杂查询先改最里层的 CTE，每改完一段就和原 SQL 的同一段比较结果。
3. **对照结果**：原 SQL 和改写后的查询用同一组参数各执行一次，按主键排序后逐行比较，`db.render(q)` 可以查看生成的 SQL。比较时要注意：
   - BigDecimal 用 `compareTo`，因为两边的小数位可能不同；
   - 原 SQL 里 `TRIM`、`NULLIF` 过的列，改写时用 `@Trimmed`、`@BlankAsNull` 或在表达式里同样处理；
   - 要覆盖每个条件分支，包括可选参数为空的情况。
4. **兜底**：暂时没法表达的片段用 `sql(Type.class, "模板", 参数...)` 嵌入表达式，整条查询都没法表达时用 `db.sql("...").bind(...).list(Type.class)`。两者都按方言原样执行。

## 2. 一一对应的构造

下表的构造都有直接对应的写法，用法见 [API 使用说明](api.md)。

| 原 SQL | 改写 |
| --- | --- |
| `WITH x AS (...)` | `cte("x", query)`；被引用多次也只在 `WITH` 里出现一次 |
| `FROM (SELECT ...) t` | `from(query.asTable())` |
| `SELECT p.*, 额外列` | `selectAs(Shape.class, p -> 额外列)`（多列用 `keys(...)`），没有列出的属性按同名列补齐 |
| `CASE col WHEN 'A' THEN ... END` | `caseOf(col).when("A", ...).otherwise(...)` |
| `CASE WHEN cond THEN ... END` | `caseWhen(cond, ...).when(...).otherwise(...)` |
| `COALESCE(a, b, c)` | `a.coalesce(b, c)` |
| `SUBSTRING(s, 1, LOCATE(s, ',') - 1)` | `substring(s, lit(1), minus(locate(s, ","), lit(1)))` |
| `COUNT(*) OVER ()` 取分页总数 | `db.page(q, page, size)`，或 `count().over(...)` |
| `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)` | `rowNumber().over(w -> w.partitionBy(...).orderBy(...))` |
| `SELECT ... FROM DUMMY UNION ALL ...` 码表 | `values(Shape.class, List.of(...))`，HANA 上自动加 `FROM DUMMY` |
| `GREATEST`、`LEAST`、`TO_INT`、`DAYS_BETWEEN` 等方言函数 | 方言门面里的同名函数，例如 `Hana.greatest(a, val(0))` |
| Java 里用 `LinkedHashMap` 把扁平结果分组成父项加子项 | `Nest.into(rows, key, child, parent)` |

## 3. 需要换一种写法的地方

### UNION 之后排序

合并结果上不能直接排序。把 UNION 包成 CTE，再在外层排序：

```java
record Row(String material, BigDecimal quantity) { }

Cte<Row> rows = cte("rows", openOrders.unionAll(backorders));   // 两段都是 selectAs(Row.class, ...)
Query<Row> sorted = from(rows)
    .orderBy(r -> r.col(Row::material))
    .selectAs(Row.class);
```

### 拼接出的列名

`"SUM(" + column + ")"` 这类按参数选择列的写法，改为在 Java 里选择 getter：

```java
Getter<Stock, BigDecimal> column = switch (kind) {
    case "unrestricted" -> Stock::getUnrestricted;
    case "blocked" -> Stock::getBlocked;
    default -> throw new IllegalArgumentException("Unknown stock kind: " + kind);
};
from(Stock.class)
    .groupBy(s -> s.col(Stock::getMaterial))
    .select((r, s) -> new Total(r.get(s, Stock::getMaterial), r.get(sum(s.col(column)))));
```

### 拼接出的表名

结构相同、表名不同的几张表（例如当期表和历史表），声明一个带列的基类，每张表一个空的子类，查询写成泛型方法：

```java
@Data
public abstract class StockBase {
    @Column("MATNR") private String material;
    @Column("LABST") private BigDecimal unrestricted;
}
@Table("MARD") public class Mard extends StockBase { }
@Table("MARDH") public class Mardh extends StockBase { }

static <E extends StockBase> Query<Total> totals(Class<E> table) {
    return from(table)
        .groupBy(s -> s.col(StockBase::getMaterial))
        .select((r, s) -> new Total(r.get(s, StockBase::getMaterial), r.get(sum(s.col(StockBase::getUnrestricted)))));
}

totals(Mard.class).unionAll(totals(Mardh.class));
```

列不完全相同时，把差异作为参数传进来，见 API 使用说明第 5 节的 `stockArm`。

### 按条件拼接的 JOIN

"有供应商参数时才 JOIN 供应商表并加条件"，改成可选的 EXISTS。作用域里的表不变，类型也不变：

```java
from(Lips.class).where(l -> all(
    l.col(Lips::getCreatedOn).between(start, end),
    ifPresent(vendor, v -> exists(from(Ekko.class)
        .where(e -> all(e.col(Ekko::getOrder).eq(l, Lips::getPurchaseOrder), e.col(Ekko::getVendor).eq(v)))))));
```

### `(:p = 'ALL' OR col = :p)` 与多分支的 `:mode`

"全部"在 Java 里换成 null，再用 `eqIfPresent`；多分支的模式用 `switch` 返回条件，`Cond.EMPTY` 会被自动丢弃：

```java
l.col(Lips::getPlant).eqIfPresent("ALL".equals(plant) ? null : plant)

static Cond byStatus(Ref<Lips> l, Mode mode) {
    return switch (mode) {
        case OPEN -> l.col(Lips::getStatus).ne("C");
        case CLOSED -> l.col(Lips::getStatus).eq("C");
        case ALL -> Cond.EMPTY;
    };
}
```

只在某个布尔条件下才加的条件用 `onlyIf(test, () -> cond)`。

### 模板拼出的 CTE

在多条 SQL 里用 `{{SCOPE}}` 之类的占位符复用同一段 CTE 的，改为返回 CTE 的方法：

```java
static Cte<MaterialScope> materialScope(String materialType) {
    return cte("material_scope", from(Mara.class)
        .where(ma -> ma.col(Mara::getMaterialType).eq(materialType))
        .distinct()
        .selectAs(MaterialScope.class, ma -> keys(ma.col(Mara::getClient), ma.col(Mara::getMaterial), ma.col(Mara::getPartNumber))));
}
```

### 运行时的值组成的常量行

在 Java 里拼出 `SELECT 'A' AS name, 1 AS ord FROM DUMMY UNION ALL ...` 的，改用 `values`，可以再包成 CTE 参与连接：

```java
record Bucket(String name, Integer ord) { }
Cte<Bucket> buckets = cte("buckets", values(Bucket.class, List.of(new Bucket("未逾期", 1), new Bucket("逾期两个月内", 2))));
```

### 整数相除

整数相除时 PostgreSQL 取整（`7 / 2` 得 3），HANA 和 MySQL 得到小数（3.5），所以 `divide` 只接受 BigDecimal 的被除数。原 SQL 在 HANA 上依赖的是小数结果，改写时先把整数 CAST 成小数：

```java
divide(count(l.col(Lips::getDelivery)).cast(CastType.decimal(18, 4)), count())
```

## 4. 已知的限制

- 一个作用域最多 8 张表，超过时把一部分连接拆成 CTE。
- 一个类里引用的不同 getter 超过约 500 个时，javac 会报 "code too large"，把查询分到多个类即可（原因见 API 使用说明第 3 节）。
- 本库只做查询，写入继续用 JdbcTemplate 或原有的工具。

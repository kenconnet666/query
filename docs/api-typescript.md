# API 使用说明（TypeScript）

本文是第二期 TypeScript API 的用法，概念与 [Java 版](api.md)一一对应。设计理由见 [0002](decisions/0002-api-architecture.md) 第 9 条。

文中示例在测试里都有可运行的版本：

- 场景：`typescript/test/src/scenarios.ts`；
- 实库测试：`typescript/test/src/live/`；
- 金样例的查询清单：`typescript/test/src/spec-cases.ts`。

## 1. 依赖与数据库实例

选定一个方言包。方言包会重新导出核心包，所以一个 import 就能拿到全部函数。驱动由使用方安装，方言包只引用驱动的类型。

```ts
import pg from "pg";
import { database, filter, from, inMemoryCache, NamingStrategy, sum } from "@query/postgres"; // 或 @query/hana、@query/mysql

const db = database(new pg.Pool({ /* ... */ }), {
  filters: [filter(ClientEntity, (r) => r.client.eq("100"))],   // 全局过滤器
  cache: inMemoryCache(),                                        // 结果缓存（按查询开启）
  listener: (e) => log.debug(`${e.statement.sql} ${e.elapsedMs}ms`),
  naming: NamingStrategy.UPPER_SNAKE,                            // 没写列名的字段按此推导
});
```

| 方言 | 包 | `database(...)` 接收 | 实例 |
| --- | --- | --- | --- |
| SAP HANA | `@query/hana` | `@sap/hana-client` 的连接池或连接 | `HanaDatabase` |
| PostgreSQL | `@query/postgres` | `pg` 的 `Pool`、`PoolClient` 或 `Client` | `PostgresDatabase` |
| MySQL 8 | `@query/mysql` | `mysql2/promise` 的连接池或连接 | `MysqlDatabase` |

- 传入单个连接时（例如事务中的连接），库直接使用这个连接，不会关闭它。
- 也可以实现 `Driver` 接口，然后 `new HanaDatabase(driver, options)`。

## 2. 声明实体

实体是带标准装饰器的普通类，只声明查询用到的列。列的类型由 `@column` 的第二个参数给出，默认为 `text`。装饰器会检查字段的声明类型：类型对不上就编译失败，字段可以带 `| null`。

```ts
import { blankAsNull, column, converted, decimal, mapFrom, ignore, table, text, trimmed } from "@query/hana";

export const sapDate = converted<string, string>(text, {     // 'yyyyMMdd' 文本 <-> 'YYYY-MM-DD'
  toDatabase: (day) => day.replaceAll("-", ""),
  fromDatabase: (s) => (s.trim() === "" || s === "00000000" ? null : `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`),
});

export abstract class ClientEntity {                            // 公共列放在基类
  @column("MANDT") client!: string;
}

@table("LIPS", { schema: "ERP" })
export class Lips extends ClientEntity {
  @column("VBELN") delivery!: string;
  @column("MATNR") material!: string;
  @column("ERDAT", sapDate) createdOn!: string | null;           // 转换器
  @column("LFIMG", decimal) quantity!: string;                   // 精确小数：字符串
}

@table("MARA", { schema: "ERP" })
export class MaraView extends ClientEntity {
  @column("MATNR") material!: string;
  @column("MFRPN") @trimmed partNumber!: string;                 // TRIM(MARAVIEW.MFRPN)
  @column("MFRNR") @blankAsNull maker!: string | null;           // NULLIF(MARAVIEW.MFRNR, '')
  @column("MFRNR") storedMaker!: string;                         // 同一列的原值
}

export class LineVO {                                           // DTO：不需要装饰器
  delivery!: string;
  @mapFrom(Lips, "material") materialNo!: string;               // 键和字段类型都检查
  createdOn!: string | null;
  @ignore computedLater?: string;
}
```

| 装饰器 | 作用 |
| --- | --- |
| `@table(name, { schema })` | 标记表实体 |
| `@column(name?, type?)` | 列名（不写时按命名策略推导）与列的类型 |
| `@mapFrom(Entity, "key")` | DTO 字段的值来自哪个实体的哪个字段 |
| `@ignore` | 不参与映射的字段 |
| `@readAs("NULLIF({0}, '')")` | SQL 级列转换：列在语句的每个位置都按模板读取，`{0}` 是带别名的列；返回的装饰器可以赋给常量复用 |
| `@blankAsNull`、`@trimmed` | 内置的列转换：空串读作 NULL（`NULLIF(col, '')`）、去掉首尾空格（`TRIM(col)`） |

**SQL 级列转换**与 `converted` 不同：`converted` 只在 JS 端改值，列转换写进 SQL，分组、聚合和条件看到的都是转换后的值。一个字段只能有一个读取模板；需要原值时再声明一个映射到同一列的字段，条件写在原值字段上才能用上索引。

**值类型**：TS 在运行时没有类型信息，所以非文本列必须声明类型。日期和精确小数用字符串表示，不经过 `Date`，因此不受进程时区影响。

| 类型 | TS 值 | 说明 |
| --- | --- | --- |
| `text`（默认） | `string` | |
| `integer` | `number` | 超出安全整数范围时报错 |
| `bigint` | `bigint` | |
| `double` | `number` | 也可用于 DECIMAL 列，按浮点数读取 |
| `decimal` | `string` | 精确，例如 `'3.000'` |
| `boolean` | `boolean` | 接受各库的 `1`/`0`、`t`/`f` |
| `date` | `string` | `'YYYY-MM-DD'`，并校验日期是否存在 |
| `timestamp` | `string` | `'YYYY-MM-DD HH:MM:SS[.fraction]'`，小数部分去掉末尾的零 |
| `bytes` | `Uint8Array` | |
| `converted(storage, converter)` | 自定义 | 相当于 Java 的组合注解 |

DTO 的字段来自类字段，所以 DTO 需要无参构造器，字段要用 `name!: T` 声明（ES2022 类字段语义），不要写成 `declare name: T`。

## 3. 写查询

`from` 开启一个作用域，每 `join` 一张表，回调就多一个参数。回调参数是 `Ref<E>`：实体的每个字段都是一个列表达式。用了不在作用域里的表的字段，或者类型不符，都会编译失败。

```ts
const q = from(Lips)
  .join(Mara, (l, ma) => ma.material.eq(l.material))
  .leftJoin(Makt, (l, ma, mk) => mk.material.eq(l.material).and(mk.language.eq("1")))
  .where((l, ma) => all(
    l.createdOn.between(start, end),                         // 参数经过转换器
    ma.manufacturer.eqIfPresent(maker),                      // maker 为 null/undefined 时省略
    exists(from(Vbkd).where((v) => v.salesOrder.eq(l.salesOrder)).selectOne())))
  .groupBy((l, ma) => ma.partNumber)
  .orderBy((l, ma) => ma.partNumber.asc())
  .select((l, ma, mk) => ({
    partNumber: ma.partNumber,
    name: max(mk.text.coalesce(ma.partNumber)),               // mk 是 LEFT JOIN 的表：mk.text 可能为 null
    quantity: sum(l.quantity),
  }));

const rows = await db.list(q);   // { partNumber: string; name: string | null; quantity: string | null }[]
```

作用域上的方法：

| 方法 | 说明 |
| --- | --- |
| `join` / `leftJoin(实体类或 Source, (…) => cond)` | 连接条件里可以用到作用域内所有的表；`leftJoin` 之后该参数的列都可能为 null |
| `crossJoin(...)` | 笛卡尔积 |
| `where`、`whereIf(test, …)`、`having` | 条件；`Cond.EMPTY` 会被自动丢弃 |
| `groupBy(… => expr 或 [expr, ...])` | 分组 |
| `groupByRollup(… => [expr, ...])` | 分组并加上各级小计和总计：HANA、PostgreSQL 渲染 `ROLLUP(a, b)`，MySQL 渲染 `a, b WITH ROLLUP`；一次列出全部分组键，不能与 `groupBy` 同用 |
| `orderBy(… => item 或 [item, ...])` | 排序项可以是 `expr`（升序）、`expr.desc()`、`.nullsLast()` 等 |
| `distinct()`、`limit(n)`、`offset(n)`、`unfiltered()` | — |
| `with(clause)` / `with((…) => clause)` | 方言子句，例如 `hint("NO_CS_JOIN")` |
| `pipe(fragment)` | 套用可复用的片段：`(scope) => scope.where(...)` |

- 表数不设上限。
- 自连接时，第二个同名实体就是下一个参数，别名在整条语句里自动唯一。
- 关联子查询直接捕获外层回调的参数。
- 泛型片段的回调要写成 `(...[l]) => ...`，这是 TS 的限制。

## 4. 投影

| 写法 | 结果 | 用途 |
| --- | --- | --- |
| `select((a, b) => ({ ... }))` | 对象 | 默认写法。键就是列标签；嵌套对象得到嵌套结果；`{ ...a }` 选出 a 的全部列 |
| `selectAs(VO)` | VO 实例 | 按同名字段从作用域里的表自动取列，改名的字段用 `@mapFrom` |
| `selectValue((a) => expr)` | 单值 | 值列表、标量子查询、`IN` 子查询 |
| `selectOne()` | `1` | `EXISTS` 子查询 |

同一个查询可以变换结果，不影响 SQL：

```ts
q.mapTo(DeliveryVO);                        // 按字段名复制进类的实例；行类型缺字段时编译失败
q.map((r) => ({ ...r, wan: Number(r.quantity) / 10_000 }));   // 在 JS 端计算
```

- 结果按列的位置读取，不依赖数据库返回的标签大小写。
- 扁平的对象投影可以作为 CTE 或派生表。

## 5. CTE、派生表、UNION、窗口

```ts
const scope = cte("material_scope", from(Mara)
  .where((ma) => ma.materialType.eq("ZMAT"))
  .distinct()
  .select((ma) => ({ client: ma.client, material: ma.material, partNumber: normalizedPart(ma.partNumber) })));

from(scope).join(Lips, (ms, l) => l.material.eq(ms.material))...   // ms 的字段就是 CTE 的列
```

- **CTE**：只有扁平对象投影的查询能作为 CTE。无论被引用多深，渲染时都只在语句开头的 `WITH` 里出现一次。
- **派生表**：`from(query.asTable())`。
- **UNION**：`q1.unionAll(q2)` 或 `union`。两段的行类型必须相同，否则编译失败。对象投影按键名对齐各段的列，键的书写顺序不同也不会错位。分页和排序加在合并之后。右侧是混用 UNION 与 UNION ALL 的组合时自动加括号，保持原来的分组。
- **窗口**：`rowNumber().over((w) => w.partitionBy(a).orderBy(b.desc()))`。聚合也可以 `.over(...)`，ROWS 框架写作 `w.rows(Bound.unboundedPreceding(), Bound.currentRow())`。
- **常量行**（码表）：`values([{ name: "未逾期", ord: 1 }, ...])` 得到一个查询，每行渲染成一个字面量 SELECT，用 UNION ALL 连接，HANA 上加 `FROM DUMMY`。列类型按值推断（文本、整数、小数），日期或精确小数用第二个参数指定，例如 `values(rows, { day: date })`。可以用 `cte(...)` 包成 CTE，再参与连接。

业务口径写成普通函数，用接口约束适用的表：

```ts
interface PeriodStock { client: string; material: string; year: string; period: string }

function stockArm<E extends PeriodStock>(table: Table<E>, quantity: (h: Ref<E>) => Expr<string>, year: string) {
  return from(table).where((h) => h.year.eq(year))
    .select((h) => ({ material: h.material, year: h.year, period: h.period, quantity: quantity(h) }));
}

stockArm(Mardh, (h) => h.unrestricted.coalesce("0"), "2026")
  .unionAll(stockArm(Mchbh, (h) => h.batchUnrestricted.coalesce("0"), "2026"));
```

**两种常见写法**：

- **一组列求和**，例如十六个期间列：键写成联合类型的数组，`plus(PERIODS.map((p) => f[p].coalesce("0")))`。按期间展开成行，则是对同一个数组逐个生成 `select`，再用 `unionAll` 合并。
- **按条件追加的过滤**：需要连接另一张表才能判断时，写成可选的 EXISTS，不要按条件拼接 JOIN：`ifPresent(supplier, (code) => exists(from(sup).where((s) => ...).selectOne()))`。这样作用域里的表不变，类型也不变。

## 6. 表达式与函数

**`Expr<T>` 的实例方法**：接收者先固定 T，参数类型不符就编译失败。

- 比较：`eq`、`ne`、`gt`、`ge`、`lt`、`le`，参数可以是值或表达式。
- 区间与集合：`between`、`in(数组 | 查询)`、`notIn`、`isNull`、`isNotNull`。
- 可选条件：`eqIfPresent` 等，以及 `betweenIfPresent`、`inIfPresent`；值为 null 或 undefined（数组为空）时省略。
- 同类型函数：`coalesce`、`nullif`。
- 排序与转换：`asc()` / `desc()`（加 `.nullsFirst()` / `.nullsLast()`）、`cast(CastType.decimal(15, 3))`、`as("label")`。

**核心函数**（各方言语义一致的部分）：

| 类别 | 函数 |
| --- | --- |
| 条件 | `all`、`any`、`not`、`onlyIf(test, () => cond)`、`ifPresent(v, (x) => cond)`、`exists`、`notExists`、`like`、`notLike`、`likeIfPresent` |
| 值 | `val(x, type?)`（参数）、`lit(x, type?)`（字面量，只用于固定代码值）、`nullOf(type)` |
| 文本 | `upper`、`lower`、`trim`、`concat`（MySQL 自动渲染为 `CONCAT`）、`substring(e, start, length?)`、`left`、`right`、`replace(e, from, to)`；位置从 1 开始 |
| 数值 | `plus`（也接收数组）、`minus`、`times`、`divide`（被除数须为 decimal 或 double）、`negate`、`abs`、`round` |
| 聚合 | `count()`、`count(e)`、`countDistinct`、`sum`、`avg`、`min`、`max`、`grouping(e)`（汇总行为 1） |
| 窗口 | `rowNumber`、`rank`、`denseRank`、`lag`、`lead`，再加 `.over(...)` |
| 其他 | `caseWhen(cond, x).when(...).otherwise(...)`、`cast`、`scalar(query)`、`currentDate`、`currentTimestamp` |
| 兜底 | `` sql(integer)`DAYS_BETWEEN(${a}, ${b})` ``、`` sqlCond`${a} ~ ${b}` ``：表达式原样渲染，普通值成为参数 |

`sum`、`avg` 等数值函数在构造时检查列的类型，用在文本列上会立刻报错。TS 的类型系统区分不了 decimal 字符串和文本，所以这一项只能在运行时检查。

**方言专属能力**只在对应的包里提供，放到别的方言上渲染时会明确报错：

| `@query/hana` | `@query/postgres` | `@query/mysql` |
| --- | --- | --- |
| `daysBetween`、`addDays`、`addMonths`、`addYears` | `distinctOn(...)` 子句 | `dateDiff`、`dateFormat` |
| `toVarchar(e, "YYYYMMDD")`、`toNvarchar`、`toDate` | `pg(e).isDistinctFrom(...)` / `isNotDistinctFrom` | `groupConcat(e, sep, ...order)` |
| `likeRegexpr(e, pattern)` | — | — |
| `length`（按 UTF-16 单元计） | `ilike`、`filterWhere(sum(x), cond)` | `maxExecutionTime(ms)` 优化器提示 |
| `stringAgg(e, sep, ...order)`、`hint(...)` | `stringAgg(e, sep, ...order)` | — |
| `cube(a, b)`、`groupingSets([a, b], [a], [])` | 同左 | — |

`cube` 和 `groupingSets` 是分组键，写在 `groupBy` 里：`groupBy((l) => groupingSets([l.material, l.unit], [l.material], []))`，空数组是总计。

PostgreSQL 的聚合过滤叫 `filterWhere`，用来和核心包里定义全局过滤器的 `filter` 区分。

**常量与参数**：

- SELECT 列表里的常量写成字面量；带 GROUP BY 或 DISTINCT 的查询，GROUP BY、HAVING、ORDER BY 里的常量也写成字面量。
- WHERE 和 ON 里的值仍然绑定为参数。
- 这样分组表达式（例如按 CASE 分桶）在三个库上都能执行，HANA 也能确定 SELECT 列的类型。
- 需要绑定的值写在 WHERE 中。

## 7. 执行

执行方法都返回 Promise。

| 方法 | 说明 |
| --- | --- |
| `db.list(q)` | 全部行 |
| `db.one(q)` | 恰好一行，否则报错；最多读 2 行 |
| `db.first(q)` | 第一行或 `undefined`，最多读 1 行 |
| `db.stream(q)` / `db.forEach(q, fn)` | 在一个游标上逐行读取：HANA 用 ResultSet，PostgreSQL 用服务端游标（需要连接池），MySQL 用流式查询（需要连接池）；提前 `break` 会释放游标 |
| `db.count(q)` | 外包 `COUNT(*)` |
| `db.page(q, page, size)` | 页号从 1 开始，同时返回总数和总页数 |
| `db.sql("... :name IN (:ids)").bind(...).list()` | 原生 SQL：数组参数自动展开，按标签返回对象；`list(VO)` 按标签映射（忽略大小写和下划线），`values()` 取第一列 |
| `db.render(q)` | 查看将要执行的 SQL 与参数 |
| `q.cached(60_000)` | 结果按 SQL 和参数缓存（需要先配置 `cache`）；缓存的是解码后的值；并发读取同一个键只查询一次 |

**全局过滤器**按实体的基类匹配：FROM 中的表把条件加到 WHERE，JOIN 的表加到 ON（LEFT JOIN 语义不变）；CTE 和子查询里的表同样生效。

**占位符**：PostgreSQL 写作 `$1, $2, ...`，在 CTE 提升之后按最终顺序编号；HANA 和 MySQL 写作 `?`。

## 8. 扩展点

| 要扩展的 | 做法 |
| --- | --- |
| 值类型 | 实现 `SqlType<T>`，或用 `converted(storage, converter)` 包装已有类型 |
| 函数 | 写一个普通函数：`call(FunctionDef.of("MY_FN"), integer, ...args)`。只属于某个方言的用 `FunctionDef.only(HanaDialect, "...")`；单个数据库实例需要改写时，用选项 `functions: new Map([[def, renderer]])` |
| 表达式与条件 | `expression(type, (w) => ...)`、`condition((w) => ...)`，或者继承 `Expr<T>` |
| 子句 | 实现 `Clause`，声明位置（`"hint"` / `"distinct"` / `"end"`）和所属方言 |
| 数据来源 | 实现 `Source<E>`，例如带参数的视图 |
| 方言 | 继承 `Dialect` 或某个现成方言，覆盖引号、字面量、占位符、分页、排序项和类型名 |
| 驱动、监听、缓存 | `Driver`、`listener`、`QueryCache` |

## 9. 编译期能拦住的错误

以下错误写法在 `typescript/test/src/unit/typing.test.ts` 里用 `@ts-expect-error` 逐条验证，构建时任何一条不报错都会失败：

- 用了不在作用域里的表的字段；回调参数比表多；
- 比较值、连接键、`coalesce` 参数、`in` 的值或子查询的类型与列不符；
- 把 LEFT JOIN 一侧的列当作非空使用；
- 窗口函数没有 `over`；
- UNION 两段的行类型不同；
- `mapTo` 的类有行里没有的字段；
- 泛型模板套用到缺少共享字段的表；
- `@column` 的类型与字段类型不符；`@mapFrom` 的键不存在，或字段装不下实体的值。

## 10. 与 Java 版的差异

| 方面 | Java | TypeScript |
| --- | --- | --- |
| 读列 | `l.col(Lips::getMaterial)` | `l.material` |
| 投影 | 函数式（记录后重放）、按名、单列三种 | 对象投影同时承担前两种，另有 `selectAs`、`selectValue` |
| 可空性 | 不跟踪 | LEFT JOIN 的一侧按位置变为可空 |
| 列的类型 | 来自 getter 的返回类型 | 由 `@column` 的类型参数声明 |
| 日期、精确小数 | `LocalDate`、`BigDecimal` | 字符串 |
| 表数 | 最多 8 张 | 不限 |
| 执行 | 同步，JDBC | Promise，各库的 Node 驱动 |
| 聚合过滤（PostgreSQL） | `filter(agg, cond)` | `filterWhere(agg, cond)` |

两端渲染出的 SQL 完全相同，由 `spec/render/` 的金样例保证。

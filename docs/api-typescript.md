# API 使用说明（TypeScript）

本文是 2.0 TypeScript API 的用法，概念与 [Java 版](api.md)一一对应。设计理由见 [0002](decisions/0002-api-architecture.md) 第 9 条和 [0006](decisions/0006-query-model-direction.md)；从手写 SQL 或 1.x 迁移见[迁移指南](migration.md)。

文中示例在测试里都有可运行的版本：

- 实体与场景：`typescript/test/src/fixtures/erp.ts`、`fixtures/scenarios.ts`；
- 金样例的查询清单：`typescript/test/src/fixtures/spec-cases.ts`；
- 各特性的单元测试：`typescript/test/src/unit/`（`relations`、`tree`、`dynamic`、`filter`、`native-sql`、`debug`、`typing`）；
- 实库测试：`typescript/test/src/live/`。

文中的 SQL 取自单元测试的输出：标识符大写、不加引号，接近 HANA；PostgreSQL 与 MySQL 的写法见 `spec/render/<场景>.<方言>.sql`。

## 速查

`l`、`m` 是作用域回调的参数，每张表一个；`l.material` 这样的字段就是列表达式。

| SQL | 写法 |
| --- | --- |
| `FROM LIPS l JOIN MARA m ON m.MATNR = l.MATNR` | `from(Lips).join(Mara, (l, m) => m.material.eq(l.material))` |
| `WHERE a = ? AND b BETWEEN ? AND ?` | `.where((l) => all(l.material.eq(x), l.createdOn.between(low, high)))` |
| 参数为空时不加这个条件 | `.skipEmpty()` 之后照常写 `l.material.eq(x)`；整个请求对象用 `.where(search)` |
| 经多对一关系取列（按需 LEFT JOIN） | `l.mara.partNumber` |
| 一对多：存在、行数、合计 | `m.deliveries.any(...)`、`m.deliveries.count()`、`m.deliveries.sum((l) => l.quantity)` |
| 一对多：每个父行带子行数组 | `m.deliveries.list((l) => ({ ... }))` |
| `EXISTS (SELECT 1 FROM VBKD v WHERE ...)` | `exists(from(Vbkd).where((v) => ...))` |
| `GROUP BY a, b`、`ORDER BY a, b DESC` | `.groupBy((l) => [l.material, l.unit])`、`.orderBy((l) => [l.material, l.delivery.desc()])` |
| `SELECT` 列 | `.select((l) => ({ material: l.material, total: sum(l.quantity) }))`；单列 `.select((l) => l.material)` |
| `SELECT` 同名列到类 | `.selectAs(Dto)`；改名或计算列用 `.select(...).into(Dto)` |
| 用户选择的列、排序 | `.exclude("unit")`、`.only(names)`、`.sortBy("total desc")` |
| `WITH x AS (...)` | `const x = cte("x", query)`，然后 `from(x)` |
| `UNION ALL` | `q1.unionAll(q2)` |
| `ROW_NUMBER() OVER (PARTITION BY a ORDER BY b DESC)` | `rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.delivery.desc()))` |
| 每组取前 N 行（`QUALIFY`） | `.qualify((l) => rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc())).le(3))` |
| 原生 SQL 片段 | `` sql(text)`LPAD(${l.material}, ${18}, '0')` ``、`` sqlCond`...` ``、`` sqlTable({ ... })`...` `` |
| 执行 | `await db.list(q)`、`db.one(q)`、`db.first(q)`、`db.page(q, 1, 20)`、`for await (const r of db.stream(q))` |

## 1. 依赖与数据库实例

要求 TypeScript 7（标准装饰器，不要开 `experimentalDecorators`）和 Node 22.18 及以上。选定一个方言包（版本 `2.0.0`）。方言包会重新导出核心包，所以一个 import 就能拿到全部函数。驱动由使用方安装，方言包只引用驱动的类型。

```ts
import pg from "pg";
import { database, filter, from, inMemoryCache, NamingStrategy, sum } from "@zerodep-dev/query-postgres"; // 或 @zerodep-dev/query-hana、@zerodep-dev/query-mysql

const db = database(new pg.Pool({ /* ... */ }), {
  filters: [filter(ClientEntity, (r) => r.client.eq("100"))],   // 全局过滤器
  cache: inMemoryCache(),                                        // 结果缓存（按查询开启）
  listener: (e) => log.debug(`${e.statement.sql} ${e.elapsedMs}ms`),
  printSql: true,                                                // 打印每条语句，见第 10 节
  queryTimeoutMs: 30_000,                                        // 每条语句的默认时限，超时由数据库取消
  naming: NamingStrategy.UPPER_SNAKE,                            // 没写列名的字段按此推导
});
```

| 方言 | 包 | `database(...)` 接收 | 实例 |
| --- | --- | --- | --- |
| SAP HANA | `@zerodep-dev/query-hana` | `@sap/hana-client` 的连接池或连接 | `HanaDatabase` |
| PostgreSQL | `@zerodep-dev/query-postgres` | `pg` 的 `Pool`、`PoolClient` 或 `Client` | `PostgresDatabase` |
| MySQL 8 | `@zerodep-dev/query-mysql` | `mysql2/promise` 的连接池或连接 | `MysqlDatabase` |

- 传入单个连接时（例如事务中的连接），库直接使用这个连接，不会关闭它。
- 也可以实现 `Driver` 接口，然后 `new HanaDatabase(driver, options)`。

## 2. 声明实体

实体是带标准装饰器的普通类，只声明查询用到的列。列的类型由 `@column` 的第二个参数给出，默认为 `text`。装饰器会检查字段的声明类型：类型对不上就编译失败，字段可以带 `| null`。关系字段（`@one`、`@many`）也写在实体上，见第 4 节。

```ts
import { all, blankAsNull, column, converted, decimal, ignore, many, one, table, text, trimmed, type Many, type One } from "@zerodep-dev/query-hana";

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
  @column("VRKME") unit!: string;
  @column("VGBEL") salesOrder!: string;

  @one(() => Mara, (l, m) => all(m.material.eq(l.material), m.client.eq(l.client))) mara!: One<Mara>;   // 多对一
  @many(() => Vbkd, (l, v) => v.salesOrder.eq(l.salesOrder)) salesData!: Many<Vbkd>;                     // 一对多
}

@table("MARA", { schema: "ERP" })
export class MaraView extends ClientEntity {
  @column("MATNR") material!: string;
  @column("MFRPN") @trimmed partNumber!: string;                 // TRIM(MARA.MFRPN)
  @column("MFRNR") @blankAsNull maker!: string | null;           // NULLIF(MARA.MFRNR, '')
  @column("MFRNR") storedMaker!: string;                         // 同一列的原值
}

export class DeliveryVO {                                       // DTO：不需要装饰器，但需要无参构造器
  partNumber!: string;
  name!: string | null;
  quantity!: string | null;
  @ignore computedLater?: string;                                // 不接收查询的值
}
```

| 装饰器 | 作用 |
| --- | --- |
| `@table(name, { schema })` | 标记表实体 |
| `@column(name?, type?)` | 列名（不写时按命名策略推导）与列的类型 |
| `@one(() => 目标, (self, target) => cond)` | 多对一或一对一关系，字段类型 `One<T>` |
| `@many(() => 目标, (self, target) => cond)` | 一对多关系，字段类型 `Many<T>` |
| `@where(op, ...columns)` | 条件对象字段的比较方式和列，见第 6 节 |
| `@ignore` | 不参与映射的字段；在条件对象里表示不作为条件 |
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

DTO 的字段来自类字段（加上被装饰的字段，去掉 `@ignore` 的），所以 DTO 需要无参构造器，字段要用 `name!: T` 声明（ES2022 类字段语义），不要写成 `declare name: T`。getter 和方法不参与映射。

## 3. 写查询

`from` 开启一个作用域，每 `join` 一张表，回调就多一个参数。回调参数是 `Ref<E>`：实体的每个字段都是一个列表达式。用了不在作用域里的表的字段，或者类型不符，都会编译失败。

```ts
const q = from(Lips)
  .join(Mara, (l, ma) => ma.material.eq(l.material))
  .leftJoin(Makt, (l, ma, mk) => mk.material.eq(l.material).and(mk.language.eq("1")))
  .skipEmpty()                                               // 之后与空值比较的条件省略
  .where((l, ma) => all(
    l.createdOn.between(start, end),                         // 参数经过转换器；start 或 end 为空时只留下另一边
    ma.manufacturer.eq(maker),                               // maker 为 null/undefined 时省略
    exists(from(Vbkd).where((v) => v.salesOrder.eq(l.salesOrder)))))
  .groupBy((l, ma) => ma.partNumber)
  .orderBy((l, ma) => ma.partNumber)
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
| `where((…) => cond)` | 条件；多次调用之间是 AND，值为空而省略的条件（`Cond.EMPTY`）会被自动丢弃 |
| `where(条件对象)` | 按请求对象筛选，见第 6 节 |
| `skipEmpty()` | 之后写的 WHERE、HAVING、连接条件里，与 `null`、`undefined`、空白字符串、空数组比较的条件直接省略 |
| `having`、`groupBy(… => expr 或 [expr, ...])` | 分组与分组后的条件 |
| `groupByRollup(… => [expr, ...])` | 分组并加上各级小计和总计：HANA、PostgreSQL 渲染 `ROLLUP(a, b)`，MySQL 渲染 `a, b WITH ROLLUP`；一次列出全部分组键，不能与 `groupBy` 同用 |
| `orderBy(… => item 或 [item, ...])` | 排序项可以是 `expr`（升序）、`expr.desc()`、`.nullsLast()` 等 |
| `distinct()`、`limit(n)`、`offset(n)`、`unfiltered()` | `unfiltered()` 跳过全局过滤器 |
| `with(clause)` / `with((…) => clause)` | 方言子句，例如 `hint("NO_CS_JOIN")` |
| `pipe(fragment)` | 套用可复用的片段：`(scope) => scope.where(...)` |

**可选条件**：不开 `skipEmpty()` 时，`eq(null)` 会抛 `TypeError`（与 NULL 比较永远不成立，多半是漏了值），要判空用 `isNull()`。开启后，`eq`、`ne`、`gt`、`ge`、`lt`、`le`、`in`、`notIn`、`like`、`contains` 等遇到空值就不产生条件，`between` 缺一边时只留下另一边。需要自己控制时用 `ifPresent(x, (v) => cond)` 或 `onlyIf(flag, () => cond)`。

- 表数不设上限。
- 自连接时，第二个同名实体就是下一个参数，别名在整条语句里自动唯一。
- 关联子查询直接捕获外层回调的参数。`exists` / `notExists` 直接接收作用域，不用写 SELECT 列表。
- 泛型片段的回调要写成 `(...[l]) => ...`，这是 TS 的限制。

## 4. 关系与导航

关系声明在实体上（第 2 节），查询里像普通字段一样使用，不必手写 JOIN。

### 多对一：`@one`

`l.mara` 是目标表的引用，列可能为 null（`l.mara.partNumber` 的类型是 `Expr<string | null>`），可以继续往下取关系。只有被用到的关系才会 LEFT JOIN，同一个关系在一条语句里只连接一次；全局过滤器放进它的 ON。

```ts
from(Lips)
  .where((l) => l.mara.manufacturer.eq("MK1"))
  .orderBy((l) => l.mara.partNumber)
  .select((l) => ({ delivery: l.delivery, partNumber: l.mara.partNumber, text: l.text.text }))
```

```sql
SELECT LIPS.VBELN AS "delivery", MARA.MFRPN AS "partNumber", MAKT.MAKTX AS "text"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
WHERE MARA.MFRNR = ?
ORDER BY MARA.MFRPN ASC
```

展开引用（`{ ...l }`）和对象投影里直接写关系字段（`m.deliveries`、`l.mara`）都只取本表的列，关系被略过。

### 一对多：`@many`

`m.deliveries` 是 `Many<Lips>`：目标表里满足连接条件的行。它不会 JOIN 进主查询（那样父行会重复），只能通过下面的操作使用：

| 操作 | 结果 | SQL |
| --- | --- | --- |
| `any(cond?)`、`none(cond?)` | `Cond` | `EXISTS` / `NOT EXISTS` 子查询 |
| `count()`、`countDistinct(f)` | `Expr<number>` | 相关标量子查询 `(SELECT COUNT(*) ...)` |
| `stringAgg(f, sep)` | `Expr<string | null>` | 相关标量子查询拼接文本，顺序取 `orderBy(...)` |
| `sum(f)`、`max(f)`、`min(f)`、`avg(f)` | 可空的值 | 相关标量子查询；没有行时为 null |
| `where(cond)` | `Many` | 先筛选目标行，再用上面的操作 |
| `orderBy(f)` | `Many` | 决定 `list`、`stringAgg` 的顺序 |
| `limit(n)` | `Many` | `list` 每个父行只取按顺序的前 n 行（批量查询里用 ROW_NUMBER 按父键分区），须先 `orderBy` |
| `first(order)` | 目标表的引用（列可为 null） | 每组按 `order` 取第一行 |
| `list(projection)` | 子行数组 | 批量查询，见下 |

```ts
from(Mara)
  .where((m) => all(m.deliveries.any((l) => l.quantity.gt("1")), m.deliveries.where((l) => l.unit.eq("KG")).none()))
  .select((m) => ({ material: m.material, lines: m.deliveries.count(), total: m.deliveries.sum((l) => l.quantity) }))
```

渲染出的 SQL 摘录（`any` 与 `none` 是带连接条件的 EXISTS，`count` 与 `sum` 是相关标量子查询）：

```sql
... (SELECT COUNT(*)
FROM QUERY_IT.LIPS LIPS
WHERE LIPS.MATNR = MARA.MATNR AND LIPS.MANDT = MARA.MANDT) AS "lines" ...
WHERE EXISTS (SELECT 1
FROM QUERY_IT.LIPS LIPS3
WHERE LIPS3.MATNR = MARA.MATNR AND LIPS3.MANDT = MARA.MANDT AND LIPS3.LFIMG > ?) AND NOT EXISTS (...)
```

**`first(order)`**：例如每个物料最新的交货单，`m.deliveries.first((l) => l.createdOn.desc()).delivery`。连接条件里"目标列 = 所属表的列"的部分作为分组键，其余条件只筛选目标行；渲染为 ROW_NUMBER 派生表再 LEFT JOIN 回来（三个库写法相同），没有匹配行时各列为 null。每次调用 `first` 都会产生一个新的派生表，要取多列时先存进变量。连接条件里没有这样的等值时报错。

**`list(projection)`**：每个父行得到一个子行数组，放进对象投影。投影可以是一个表达式（得到值数组）或对象（得到对象数组），子投影里还可以再有 `list(...)`。

```ts
const tree = from(Mara)
  .orderBy((m) => m.material)
  .select((m) => ({
    material: m.material,
    text: { text: m.text.text },                              // 嵌套对象
    lines: m.deliveries.orderBy((l) => l.delivery).list((l) => ({ delivery: l.delivery, quantity: l.quantity })),
  }));
const rows = await db.list(tree);   // { material: string; text: { text: string | null }; lines: { delivery: string; quantity: string }[] }[]
```

- 父查询只多取连接键，列标签以字段名开头（复合键依次为 `lines`、`lines.2`）：`SELECT MARA.MATNR AS "material", MARA.MATNR AS "lines", MARA.MANDT AS "lines.2" ...`。
- 子行用一条按键批量的查询取出，N 个父行只多一条 SQL；每个嵌套层级各一条。单列键用 `IN (?, ...)`，复合键用 `(a = ? AND b = ?) OR ...`；重复的键只查一次，键为 null 的父行得到空数组。

  ```sql
  SELECT LIPS.VBELN AS "value.delivery", LIPS.LFIMG AS "value.quantity", LIPS.MATNR AS "key0", LIPS.MANDT AS "key1"
  FROM QUERY_IT.LIPS LIPS
  WHERE ((LIPS.MATNR = ? AND LIPS.MANDT = ?) OR (LIPS.MATNR = ? AND LIPS.MANDT = ?) OR (LIPS.MATNR = ? AND LIPS.MANDT = ?))
  ORDER BY LIPS.VBELN ASC
  ```

- 每批最多 500 个父键（`Children.BATCH`），父行更多时分批查询。子查询沿用父查询的 `.cached(...)`、`.timeout(...)` 和全局过滤器。
- 带 `list` 的查询要读完父行才能组装，所以 `stream` 会先读完全部行再逐行交付；它也不能参与 UNION。`only(...)` 不会去掉子数组需要的键。

## 5. 投影与结果

| 写法 | 结果 | 用途 |
| --- | --- | --- |
| `select((a, b) => ({ ... }))` | 对象 | 默认写法。键就是列标签；嵌套对象得到嵌套结果，标签用点连接（`stock.quantity`）；`{ ...a }` 选出 a 的全部列 |
| `select((a) => expr)` | 单值 | 值列表、标量子查询、`IN` 子查询：`db.list(from(Lips).select((l) => l.delivery))` 得到 `string[]` |
| `selectAs(VO)` | VO 实例 | 类的每个字段取自作用域里恰好一个来源的同名列，找不到或多于一个时立刻报错 |

同一个查询可以变换结果，不影响 SQL：

```ts
q.into(DeliveryVO);                                           // 按字段名复制进类的实例；行类型缺字段时编译失败，多出来的列被忽略
q.map((r) => ({ ...r, wan: Number(r.quantity) / 10_000 }));   // 在 JS 端计算
```

- 列名与字段名不同、或要加计算列时，在 `select` 里把键写成字段名，再 `.into(VO)`。
- 结果按列的位置读取，不依赖数据库返回的标签大小写。
- 只有扁平的对象投影（没有嵌套、没有 `list`，也没有 `map`/`into`）可以作为 CTE 或派生表。

### 动态列与排序

按用户选择的维度汇总、按用户的排序参数排序，不必拼接 SQL：

```ts
const totals = from(Lips)
  .groupBy((l) => [l.material, l.unit, substring(l.delivery, 1, 1)])
  .orderBy((l) => [l.material.asc(), l.unit.asc()])
  .select((l) => ({ material: l.material, unit: l.unit, prefix: substring(l.delivery, 1, 1), total: sum(l.quantity) }));

totals.exclude("unit", "prefix");
```

```sql
SELECT LIPS.MATNR AS "material", NULL AS "unit", NULL AS "prefix", SUM(LIPS.LFIMG) AS "total"
FROM QUERY_IT.LIPS LIPS
GROUP BY LIPS.MATNR
ORDER BY LIPS.MATNR ASC
```

| 方法 | 说明 |
| --- | --- |
| `exclude(...names)` | 不取这些列：SQL 写成 `NULL`，结果的形态不变，类型变为可空（`Excluded<T>`）。只被它们用到的关系不再连接；有分组时，同样的分组键和按它们的排序一起去掉 |
| `excludeIf(flag, ...names)` | `flag` 为真时才排除 |
| `only(names)` | 只取这些列，其余按 `exclude` 处理；`names` 为 `null`、`undefined` 或空数组时保持不变，所以可以直接传入用户选的列 |
| `sortBy("total desc, material")` | 按结果列排序并替换原来的 ORDER BY；每项是结果列名，可跟 `asc`/`desc`；空串保持不变 |

- 名字是结果的键，不分大小写和下划线（`only(["PREFIX"])` 能匹配 `prefix`）；嵌套对象的键代表它下面的全部列；不存在的名字报错并列出可用的列。
- `only` 与 `sortBy` 只认结果里的列，相当于白名单，用户输入可以直接传入；排序串写法不对、按被排除的列排序、对 UNION 使用 `sortBy`（先包成 CTE）都会报错。

## 6. 条件对象

`where(search)` 把请求对象直接变成条件：每个字段一个条件，值为空（`null`、`undefined`、空白字符串、空数组）的字段不产生条件，也不会连接只被它用到的关系。

```ts
class DeliverySearch {
  material?: string[];                                           // 默认：与同名列比较，数组用 IN
  @where("ge", "createdOn") from?: string;                       // 比较方式 + 列
  @where("lt", "createdOn") to?: string;
  @where("contains", "mara.partNumber", "text.text") keyword?: string;   // 经关系取列；多列任一满足（OR）
  @where("ne") unit?: string;                                    // 只指定比较方式，列取字段名
  @ignore sort?: string;                                         // 不作为条件
}

from(Lips).where(search).select((l) => l.delivery)   // search: DeliverySearch 的实例
```

传入 `{ material: ["M1", "M2"], from: "2026-01-01", to: "2026-07-01", keyword: "50%", unit: "KG" }` 得到：

```sql
SELECT LIPS.VBELN AS "delivery"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
WHERE LIPS.MATNR IN (?, ?) AND LIPS.ERDAT >= ? AND LIPS.ERDAT < ? AND ((MARA.MFRPN LIKE ? ESCAPE '!') OR (MAKT.MAKTX LIKE ? ESCAPE '!')) AND LIPS.VRKME <> ?
```

- 比较方式 `eq`（默认）、`ne`、`gt`、`ge`、`lt`、`le`、`contains`、`startsWith`、`endsWith`；后三个转义 `%` 和 `_`。数组只能用 `eq`（IN）和 `ne`（NOT IN）。
- 列写成字段名（作用域里恰好一张表有这一列）或沿 `@one` 关系的路径（`"mara.partNumber"`、`"mara.text.text"`）；同名列出现在多张表里时报错，要求写出关系路径。
- 路径经过 `@many` 关系时写成 EXISTS，例如 `@where("eq", "deliveries.unit") unit` 和 `@where("ge", "deliveries.quantity") minQuantity`：只写一条路径的字段按同一个一对多关系合并进一个 EXISTS，即要求同一个子行同时满足；这些字段的值都为空时整个 EXISTS 省略。
- 普通对象也可以：`from(Lips).where({ unit: ["EA", "KG"], salesOrder: "S1" })` 按同名列比较，并可与回调条件、其他 `where` 一起使用（AND）。
- 字段名找不到列时无论有没有值都报错（写错的字段不会悄悄放过）；路径指向关系而不是列也报错。

## 7. CTE、派生表、UNION、窗口

```ts
const scope = cte("material_scope", from(Mara)
  .where((ma) => ma.materialType.eq("ZMAT"))
  .distinct()
  .select((ma) => ({ client: ma.client, material: ma.material, partNumber: normalizedPart(ma.partNumber) })));

from(scope).join(Lips, (ms, l) => l.material.eq(ms.material))...   // ms 的字段就是 CTE 的列
```

- **CTE**：只有扁平对象投影的查询能作为 CTE。无论被引用多深，渲染时都只在语句开头的 `WITH` 里出现一次。
- **派生表**：`from(query.asTable())`。
- **UNION**：`q1.unionAll(q2)` 或 `union`。两段的行类型必须相同，否则编译失败。对象投影按键名对齐各段的列，键的书写顺序不同也不会错位。各段不能自带排序或分页，分页和排序加在合并之后（要排序先包成 CTE）。右侧是混用 UNION 与 UNION ALL 的组合时自动加括号，保持原来的分组。
- **窗口**：`rowNumber().over((w) => w.partitionBy(a).orderBy(b.desc()))`。聚合也可以 `.over(...)`，ROWS 框架写作 `w.rows(Bound.unboundedPreceding(), Bound.currentRow())`。另有 `lag`、`lead`（第三个参数是越界时的默认值）、`firstValue`、`lastValue`（取整个分区的末行要写 ROWS 框架）、`ntile(n)`。
- **窗口条件（QUALIFY）**：`qualify((l) => rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc())).le(3))` 每组取前 3 行。HANA 没有 QUALIFY 子句，三个库都渲染成派生表：窗口函数和不在结果里的排序项作为额外的列，外层过滤、排序和分页。条件只能比较窗口函数，其他条件写在 `where` 或 `having` 里，否则报错。
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
- **按条件追加的过滤**：需要连接另一张表才能判断时，写成可选的 EXISTS，不要按条件拼接 JOIN：`ifPresent(supplier, (code) => exists(from(sup).where((s) => ...)))`；能用关系表达的，直接用第 4 节的 `l.mara.xxx`（没用到就不连接）。

## 8. 表达式与函数

**`Expr<T>` 的实例方法**：接收者先固定 T，参数类型不符就编译失败。

- 比较：`eq`、`ne`、`gt`、`ge`、`lt`、`le`，参数可以是值或表达式；值为空时的行为见第 3 节的 `skipEmpty()`。
- 区间与集合：`between`（含两端）、`range(from, until)`（半开区间 `>= from AND < until`，适合日期段；skipEmpty 下缺少的一端被忽略）、`in(数组 | 查询)`、`notIn`、`isNull`、`isNotNull`。
- 同类型函数：`coalesce`（可以给多个后备值：`a.coalesce(b, c, "EA")`，渲染成一个 COALESCE）、`nullif`。
- 排序与转换：`asc()` / `desc()`（加 `.nullsFirst()` / `.nullsLast()`）、`cast(CastType.decimal(15, 3))`、`as("label")`。

**核心函数**（各方言语义一致的部分）：

| 类别 | 函数 |
| --- | --- |
| 条件 | `all`、`any`、`not`、`onlyIf(test, () => cond)`、`ifPresent(v, (x) => cond)`、`exists`、`notExists`、`like`、`notLike`（模式原样，`%` `_` 是通配符）、`contains`、`startsWith`、`endsWith`（转义通配符） |
| 值 | `val(x, type?)`（参数）、`lit(x, type?)`（字面量，只用于固定代码值）、`nullOf(type)`；文本、数字、布尔的类型放宽为 `string`、`number`、`boolean`，所以只差一个常量的 UNION 各段类型相同 |
| 文本 | `upper`、`lower`、`trim`、`concat`（MySQL 自动渲染为 `CONCAT`）、`substring(e, start, length?)`、`left`、`right`、`replace(e, from, to)`、`locate(e, search)`；位置从 1 开始，`locate` 找不到时为 0。位置可以是数字，也可以是表达式，例如 `substring(e, plus(locate(e, ","), lit(1)))` |
| 数值 | `plus`（也接收数组）、`minus`、`times`、`divide`（被除数须为 decimal 或 double）、`negate`、`abs`、`round`、`floor`、`ceil` |
| 日期 | `year`、`month`、`day`（日期或时间戳）、`addDays`、`addMonths`、`addYears`（日期，个数可为负，月末对齐）、`daysBetween(from, to)` |
| 聚合 | `count()`、`count(e)`、`countDistinct`、`sum`、`avg`、`min`、`max`、`stringAgg(e, sep, ...order)`（MySQL 写成 GROUP_CONCAT）、`grouping(e)`（汇总行为 1）；聚合加 `.filter(cond)` 只聚合满足条件的行：`sum(l.quantity).filter(l.unit.eq("KG"))` 写成 `SUM(CASE WHEN ... THEN ... END)` |
| 窗口 | `rowNumber`、`rank`、`denseRank`、`lag`、`lead`、`firstValue`、`lastValue`、`ntile`，再加 `.over(...)` |
| 其他 | `caseWhen(cond, x).when(...).otherwise(...)`、`caseOf(e).when(值, x).otherwise(...)`（简单 CASE，适合内联的码表）、`cast`、`scalar(query)`、`currentDate`、`currentTimestamp` |
| 兜底 | `sql(type)` 与 `sqlCond`，见第 9 节 |

`sum`、`avg` 等数值函数和 `year`、`addDays` 等日期函数在构造时检查列的类型，用在文本列上会立刻报错。TS 的类型系统区分不了 decimal 字符串和文本，所以这一项只能在运行时检查。

**方言专属能力**只在对应的包里提供，放到别的方言上渲染时会明确报错：

| `@zerodep-dev/query-hana` | `@zerodep-dev/query-postgres` | `@zerodep-dev/query-mysql` |
| --- | --- | --- |
| `toVarchar(e, "YYYYMMDD")`、`toNvarchar`、`toDate` | `distinctOn(...)` 子句 | `dateFormat(e, "%Y%m")` |
| `hint(...)` | `pg(e).isDistinctFrom(...)` / `isNotDistinctFrom` | — |
| `likeRegexpr(e, pattern)` | — | — |
| `length`（按 UTF-16 单元计） | `ilike` | `maxExecutionTime(ms)` 优化器提示 |
| `cube(a, b)`、`groupingSets([a, b], [a], [])` | 同左 | — |
| `toInteger(e)`（即 TO_INT/TO_INTEGER） | — | — |
| `greatest(a, b, ...)`、`least`：有一个参数为 NULL 就返回 NULL | `greatest`、`least`：跳过 NULL 参数 | 同 HANA |

三个库对 `greatest`、`least` 的 NULL 处理不同，所以它们放在各自的方言包里。`cube` 和 `groupingSets` 是分组键，写在 `groupBy` 里：`groupBy((l) => groupingSets([l.material, l.unit], [l.material], []))`，空数组是总计。

**常量与参数**：SELECT 列表里的常量写成字面量；带 GROUP BY 或 DISTINCT 的查询，GROUP BY、HAVING、ORDER BY 里的常量也写成字面量。WHERE 和 ON 里的值仍然绑定为参数。这样分组表达式（例如按 CASE 分桶）在三个库上都能执行，HANA 也能确定 SELECT 列的类型。需要绑定的值写在 WHERE 中。

## 9. 原生 SQL 片段

构建器表达不了的部分，用模板字符串嵌入。模板里的表达式（列、其他片段、`identifier(...)`）原位渲染，其他值（字符串、数字、布尔、字节）成为绑定参数（出现在 SELECT 列表里时写成字面量），数组展开成逗号分隔的多个参数；空数组和 `null` 报错，要 NULL 就写在模板里。

```ts
sql(text)`LPAD(${l.material}, ${18}, '0')`            // 带类型的表达式；不写类型 sql`...` 时值按驱动返回的样子读取
sqlCond`${l.unit} IN (${["EA", "KG"]})`                // 条件 -> LIPS.VRKME IN (?, ?)
```

- **按方言改写**：`.on(方言类)` 给某个方言另一种写法，渲染时取与当前方言最接近的（继承层次最深的匹配项），都不匹配时用默认写法。`sql(...)`、`sqlCond`、`sqlTable` 都支持。

  ```ts
  sql(integer)`DAYS_BETWEEN(${a}, ${b})`.on(MysqlDialect)`DATEDIFF(${b}, ${a})`   // MysqlDialect 来自 @zerodep-dev/query-mysql
  ```

- **`identifier(name)`**：按方言加引号的标识符，让同一段模板在三个库上都能用。
- **`sqlTable({ 列: 类型 })`**：原生查询作为数据源，放进 `from` 或 `join`。查询的每一列要用声明里的列名作别名并按方言加引号，因为外层按这些名字引用列；类型决定外层看到的列类型。

```ts
const totals = sqlTable({ material: text, total: decimal })`SELECT ${identifier("MATNR")} AS ${identifier("material")}, SUM(LFIMG) AS ${identifier("total")} FROM QUERY_IT.LIPS WHERE VRKME = ${"EA"} GROUP BY ${identifier("MATNR")}`;

from(totals)
  .join(Mara, (t, m) => m.material.eq(t.material))
  .where((t) => t.total.gt("1"))
  .select((t, m) => ({ partNumber: m.partNumber, total: t.total }))
```

```sql
SELECT MARA.MFRPN AS "partNumber", T."total" AS "total"
FROM (SELECT MATNR AS "material", SUM(LFIMG) AS "total" FROM QUERY_IT.LIPS WHERE VRKME = ? GROUP BY MATNR) T
JOIN QUERY_IT.MARA MARA ON MARA.MATNR = T."material"
WHERE T."total" > ?
```

整条语句都表达不了时用 `db.sql("... :name IN (:ids)").bind("ids", [...]).list()`，见第 11 节。

## 10. 打印与测试 SQL

| 手段 | 说明 |
| --- | --- |
| `db.render(q)` / `q.toSql(dialect)` | 返回 `Rendered`，不执行。前者含该数据库的过滤器和函数改写，后者不含；`dialect` 例如 `HanaDialect.instance` |
| `Rendered`：`sql`、`parameters` | 最终的 SQL 文本和按占位符顺序排列的参数（已编码） |
| `r.parameterText()` | 带类型的参数文本：`O'Neil(text), 20260115(converted text)` |
| `r.debugSql()` | 把参数写成字面量，可以直接粘到数据库客户端执行；只用于排查，程序仍然绑定参数 |
| `String(r)` | SQL 加一行 `-- parameters: ...` |
| 选项 `printSql: true` 或 `(text) => logger.info(text)` | 打印每条执行过的语句；命中缓存时标 `(cached)`，失败时最后一行是错误信息 |
| `db.capture(async () => {...})` | 返回这段代码（含其中的异步调用）通过本数据库执行的全部语句（`QueryEvent[]`，含缓存命中），可以嵌套；需要 Node 的 `AsyncLocalStorage` |
| `dryRunDriver()` | 不连数据库的驱动：每条查询得到空结果（`count` 为 0），但照常渲染、打印和触发监听器 |

`printSql` 的输出格式（MyBatis 风格）：

```
==> Preparing: SELECT MARA.MFRPN AS "partNumber"
FROM QUERY_IT.MARA MARA
WHERE MARA.MATNR = ?
==> Parameters: M1(text)
<== Total: 0, Time: 3 ms
```

不连数据库地测试代码发出的 SQL：

```ts
const db = new PostgresDatabase(dryRunDriver());
const events = await db.capture(() => service.load(request));
assert.deepEqual(events.map((e) => e.statement.parameters), [["D1"]]);
console.log(events[0]!.statement.debugSql());
```

## 11. 执行

执行方法都返回 Promise。

| 方法 | 说明 |
| --- | --- |
| `db.list(q)` | 全部行；带 `list(...)` 子数组时，每一层再多一条批量查询 |
| `db.one(q)` | 恰好一行，否则报错；最多读 2 行 |
| `db.first(q)` | 第一行或 `undefined`，最多读 1 行 |
| `db.stream(q)` / `db.forEach(q, fn)` | 在一个游标上逐行读取：HANA 用 ResultSet，PostgreSQL 用服务端游标（需要连接池），MySQL 用流式查询（需要连接池）；提前 `break` 会释放游标。带 `list(...)` 的查询先读完全部行再交付 |
| `db.count(q)` | 外包 `COUNT(*)` |
| `db.page(q, page, size, total?)` | 页号从 1 开始，同时返回总数和总页数；传入已知的 `total` 时不再执行 COUNT |
| `db.sql("... :name IN (:ids)").bind(...).list()` | 原生 SQL：数组参数自动展开，按标签返回对象；`list(VO)` 按标签映射（忽略大小写和下划线），`values()` 取第一列，另有 `first()`、`one()`。引号、注释和方言的引用（PostgreSQL 的 `$$…$$`、MySQL 的反引号）里的 `:name` 不是参数 |
| `q.cached(60_000)` | 结果按 SQL 和参数缓存（需要先配置 `cache`）；缓存的是解码后的值；并发读取同一个键只查询一次 |
| `q.timeout(10_000)`、`db.sql(...).timeout(...)` | 这条语句的时限（毫秒），覆盖选项 `queryTimeoutMs` |

**超时**：由驱动适配器交给数据库，三个库都在服务端取消语句，之后的查询照常进行（实库测试验证过）。HANA 用 `Statement.setTimeout`（按整秒计，不足一秒按一秒）；PostgreSQL 在语句前后设置并恢复 `statement_timeout`（流式读取在自己的事务里用 `SET LOCAL`）；MySQL 设置并恢复会话的 `max_execution_time`（只对 SELECT 生效）。传入连接池时借一个连接来做，恢复失败的连接会被丢弃或销毁。超时抛出的 `QueryExecutionError` 的 `timedOut` 为 true（HANA 613、PostgreSQL `57014`、MySQL 3024）。自己实现的 `Driver` 从第三个参数 `options.timeoutMs` 拿到时限，不支持时可以忽略。

**参数个数**：三个库的一条语句最多绑定 65535 个参数（mysql2 用服务端预处理语句，同样受这个限制）。超过时在连接数据库之前就报错；很长的 IN 列表请拆成几次查询，或者改成子查询。

**全局过滤器**按实体的基类匹配：FROM 中的表把条件加到 WHERE，JOIN 的表和 `@one` 关系的表加到 ON（LEFT JOIN 语义不变）；CTE 和子查询里的表同样生效。

**占位符**：PostgreSQL 写作 `$1, $2, ...`，在 CTE 提升之后按最终顺序编号；HANA 和 MySQL 写作 `?`。

## 12. 扩展点

| 要扩展的 | 做法 |
| --- | --- |
| 值类型 | 实现 `SqlType<T>`，或用 `converted(storage, converter)` 包装已有类型 |
| 函数 | 写一个普通函数：`call(FunctionDef.of("MY_FN"), integer, ...args)`。只属于某个方言的用 `FunctionDef.only(HanaDialect, "...")`；单个数据库实例需要改写时，用选项 `functions: new Map([[def, renderer]])` |
| 表达式与条件 | `expression(type, (w) => ...)`、`condition((w) => ...)`，或者继承 `Expr<T>` |
| 子句 | 实现 `Clause`，声明位置（`"hint"` / `"distinct"` / `"end"`）和所属方言 |
| 数据来源 | 实现 `Source<E>`，例如带参数的视图 |
| 方言 | 继承 `Dialect` 或某个现成方言，覆盖引号、字面量、占位符、分页、排序项和类型名 |
| 驱动、监听、缓存 | `Driver`、`listener`、`QueryCache` |

**记录语句**：只想看 SQL 时用 `printSql`；要接入项目已有的日志（pino、winston 或 console），用监听器。事件里有数据库名、SQL 与参数、耗时、行数、异常，以及是否命中缓存：

```ts
const db = new HanaDatabase(driver, {
  listener: (e) => (e.error !== undefined
    ? logger.warn({ sql: e.statement.sql, params: e.statement.parameters, err: e.error })
    : logger.debug({ sql: e.statement.sql, rows: e.rows, ms: e.elapsedMs, cached: e.cached })),
});
```

监听器抛出的异常会被忽略，不影响查询。`printSql` 与监听器可以同时使用。

## 13. 编译期能拦住的错误

以下错误写法在 `typescript/test/src/unit/typing.test.ts` 里用 `@ts-expect-error` 逐条验证，构建时任何一条不报错都会失败：

- 用了不在作用域里的表的字段；回调参数比表多；
- 比较值、连接键、`coalesce` 参数、`in` 的值或子查询的类型与列不符；
- 把 LEFT JOIN 一侧的列、或经 `@one` 关系取得的列当作非空使用；
- 窗口函数没有 `over`；
- UNION 两段的行类型不同；
- `into` 的类有行里没有的字段；
- `exclude` 的名字不是结果的键；
- 把一对多关系（`m.deliveries`）或多对一关系的引用（`l.mara`）当作列比较；
- 泛型模板套用到缺少共享字段的表；
- `@column` 的类型与字段类型不符。

## 14. 安全与上线前检查

安全规则与 Java 版相同（见 [Java 版第 14 节](api.md#14-安全与上线前检查)）：

- 条件里的值一律绑定为参数；字面量由方言转义，PostgreSQL 的值含反斜杠时写成 `E'...'`；
- `NaN`、`Infinity` 不会写成字面量；数值文本只有符合 `decimal` 格式时才写成字面量；
- `sql(...)`、`sqlCond(...)`、`sqlTable(...)` 的模板和 `db.sql(...)` 的文本原样执行，用户输入一律作为模板参数或 `bind` 传入；
- `like` 的模式原样使用，不转义 `%` 和 `_`；用户输入的关键字用 `contains`、`startsWith`、`endsWith` 或条件对象；
- 条件对象的字段名、`only` 的列名、`sortBy` 的排序串都按结果或实体里已有的名字校验，未知名字报错，用户输入可以直接传入。

上线前：

1. 配置 `queryTimeoutMs`；耗时的报表单独设置 `q.timeout(...)`。
2. 大结果集用 `stream` 或 `forEach`（带 `list(...)` 的查询除外）。PostgreSQL 和 MySQL 需要传入连接池，才能借出一个连接做流式读取。
3. 连接池大小由驱动的连接池决定；每条语句借一个连接，用完归还。`list(...)` 的子查询是额外的语句，嵌套层数越多语句越多。
4. 监听器事件和 `printSql` 的输出里有 SQL 和参数，参数可能含业务数据，记录日志时脱敏；生产环境不要无条件打开 `printSql`。
5. 多实例部署时，实现 `QueryCache` 接到 Redis 一类的共享缓存。

## 15. 与 Java 版的差异

概念一一对应，两端渲染出的 SQL 完全相同，由 `spec/render/` 的金样例保证。

| 方面 | Java | TypeScript |
| --- | --- | --- |
| 可空性 | 不跟踪 | LEFT JOIN 的一侧和 `@one` 关系的列按类型变为可空 |
| 表与列 | 继承 `Entity` 的接口，列是返回 `Col<T>` 的方法，类型来自泛型 | 类的字段，类型由 `@column` 的类型参数声明 |
| 关系 | 表接口的 default 方法 `one(...)`、`many(...)`，或 `@ManyToOne`、`@OneToMany` | `@one`、`@many` 字段 |
| 对象树 | `selectAs(VO.class)` 按属性类型自动映射嵌套 VO 与 `List<X>` | 对象投影里写 `list(...)` 和嵌套对象 |
| 日期、精确小数 | `LocalDate`、`BigDecimal` | 字符串 |
| 表数 | 最多 16 张，`row(...)` 最多 22 个参数 | 不限 |
| 执行 | 同步，JDBC | Promise，各库的 Node 驱动 |

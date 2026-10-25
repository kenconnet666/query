# API 使用说明（TypeScript）

本文是 2.0 TypeScript API 的用法，概念与 [Java 版](api.md)一一对应。设计理由见 [0002](decisions/0002-api-architecture.md) 第 9 条、[0006](decisions/0006-query-model-direction.md) 和 [0014](decisions/0014-typed-expressions.md)（按逻辑 SQL 类型分的列、语句式条件）；从手写 SQL 或 1.x 迁移见[迁移指南](migration.md)。

文中示例在测试里都有可运行的版本：

- 实体与场景：`typescript/test/src/fixtures/erp.ts`、`fixtures/scenarios.ts`；
- 金样例的查询清单：`typescript/test/src/fixtures/spec-cases.ts`；
- 各特性的单元测试：`typescript/test/src/unit/`（`relations`、`tree`、`dynamic`、`filter`、`native-sql`、`debug`、`typing`）；
- 实库测试：`typescript/test/src/live/`；浏览器里的测试：`typescript/test/src/browser/`。

浏览器里的轻量本地存储（IndexedDB）是另一个包，见 [IndexedDB 本地存储](api-idb.md)。

文中的 SQL 取自单元测试的输出：标识符大写、不加引号，接近 HANA；PostgreSQL 与 MySQL 的写法见 `spec/render/<场景>.<方言>.sql`。

## 速查

`l`、`m` 是作用域回调的参数，每张表一个；`l.material` 这样的字段是按字段类型选的列接口（`ColText`、`ColDecimal` 等，见第 2 节），文本、数值、日期上的函数直接点出来。条件写成一条条语句，见第 3 节。

| SQL | 写法 |
| --- | --- |
| `FROM LIPS l JOIN MARA m ON m.MATNR = l.MATNR` | `from(Lips).join(Mara, (l, m) => m.material.eq(l.material))` |
| `WHERE a = ? AND b BETWEEN ? AND ?` | `.where((l) => { l.material.eq(x); l.createdOn.between(low, high); })` |
| `WHERE (a = ? OR b = ?)` | `.where((l) => any(() => { l.unit.eq("EA"); l.unit.eq("KG"); }))` |
| 可选条件 | 条件块里直接写 `if (maker != null) l.mara.manufacturer.eq(maker);` |
| 参数为空时不加这个条件 | `.skipEmpty()` 之后照常写 `l.material.eq(x)`；整个请求对象用 `.where(search)` |
| 经多对一关系取列（按需 LEFT JOIN） | `l.mara.partNumber` |
| 一对多：存在、行数、合计 | `m.deliveries.any(...)`、`m.deliveries.count()`、`m.deliveries.sum((l) => l.quantity)` |
| 一对多：每个父行带子行数组 | `m.deliveries.list((l) => ({ ... }))` |
| `EXISTS (SELECT 1 FROM VBKD v WHERE ...)` | `exists(from(Vbkd).where((v) => ...))` |
| `GROUP BY a, b`、`ORDER BY a, b DESC` | `.groupBy((l) => [l.material, l.unit])`、`.orderBy((l) => [l.material, l.delivery.desc()])` |
| `SELECT` 列 | `.select((l) => ({ material: l.material, total: l.quantity.sum() }))`；单列 `.select((l) => l.material)` |
| 函数 | `l.unit.upper()`、`l.quantity.plus(1)`、`l.createdOn.addDays(3)`；方言函数 `hana(l.createdOn).toVarchar("YYYYMMDD")` |
| `SELECT` 同名列到类 | `.selectAs(Dto)`；改名或计算列用 `.select(...).into(Dto)` |
| 用户选择的列、排序 | `.exclude("unit")`、`.only(names)`、`.sortBy("-total,material")` |
| 前端的表格请求（排序、表头筛选、分页） | `toWire(await db.table(q, parseTableRequest(getQuery(event))))`，见第 21 节 |
| 多对多 | `@manyThrough((u) => u.userRoles, (ur) => ur.role) roles!: Many<Role>` |
| `WITH x AS (...)` | `const x = cte("x", query)`，然后 `from(x)` |
| `UNION ALL`、`INTERSECT`、`EXCEPT` | `q1.unionAll(q2)`、`q1.intersect(q2)`、`q1.except(q2)` |
| `ROW_NUMBER() OVER (PARTITION BY a ORDER BY b DESC)` | `rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.delivery.desc()))` |
| 每组取前 N 行（`QUALIFY`） | `.qualify((l) => rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc())).le(3))` |
| 原生 SQL 片段 | `` sql(text)`LPAD(${l.material}, ${18}, '0')` ``、`` sqlCond`...` ``、`` sqlTable({ ... })`...` `` |
| 执行 | `await db.list(q)`、`db.one(q)`、`db.optional(q)`、`db.first(q)`、`db.page(q, 1, 20)`、`for await (const r of db.stream(q))` |
| `INSERT`、`UPDATE`、`DELETE` | `db.execute(insertInto(T).values(rows))`、`update(T).set((t) => ({ x: v, n: t.n.plus(1) })).where(...)`、`deleteFrom(T).where(...)`；按主键 `.byId(row)`，见第 15 节 |
| 联表 `UPDATE` / `DELETE` | 方言包的 `updateJoin(T, S, (t, s) => on).set(...).where(...)`、`deleteJoin(...)`，见第 15 节 |

## 1. 依赖与数据库实例

要求 TypeScript 7（标准装饰器，不要开 `experimentalDecorators`）和 Node 22.18 及以上。选定一个方言包（版本 `2.0.0`）。方言包会重新导出核心包，所以一个 import 就能拿到全部函数。驱动由使用方安装，方言包只引用驱动的类型。

小数和日期的值类型是两个 peerDependencies，与前端组件（vue-tsx-pro）用的相同，要一起安装：`decimal.js`（`Decimal`）和 `@internationalized/date`（`CalendarDate`、`CalendarDateTime`）。

```ts
import pg from "pg";
import { database, filter, from, inMemoryCache, NamingStrategy } from "@zerodep-dev/query-postgres"; // 或 @zerodep-dev/query-hana、@zerodep-dev/query-mysql、@zerodep-dev/query-sqlite

const db = database(new pg.Pool({ /* ... */ }), {
  filters: [filter("tenant", ClientEntity, (r) => r.client.eq("100"))], // 全局过滤器（条件块）；有名字时可以用 unfiltered("tenant") 单独关掉；块里不写条件就不加
  schemas: { ERP: "SAPHANADB" },                                 // 表声明里的 schema 换成实际名字，也可以是 (s) => ...
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
| PolarDB for MySQL | `@zerodep-dev/query-polardb`（重新导出 MySQL 包的全部内容） | 同 MySQL | `PolardbDatabase`（也是 `MysqlDatabase`，见第 20 节） |
| SQLite 3.46+ | `@zerodep-dev/query-sqlite` | `node:sqlite` 的 `DatabaseSync`，或 `@sqlite.org/sqlite-wasm` 的 oo1 `DB`（见第 19 节） | `SqliteDatabase` |

- 传入单个连接时（例如事务中的连接），库直接使用这个连接，不会关闭它。
- 也可以实现 `Driver` 接口，然后 `new HanaDatabase(driver, options)`。
- **通用门面**：只用核心包写法的代码（例如建在本库上的流程引擎）可以只依赖核心包，由驱动决定方言（[0013](decisions/0013-portable-facade-and-live-tests.md)）：`import { database } from "@zerodep-dev/query-core"`，再 `database(mysqlDriver(pool), options)`。
  - 方言包的驱动适配器（`hanaDriver`、`pgDriver`、`mysqlDriver`、`nodeSqliteDriver`、`wasmDriver`）都带着自己的方言（`Driver.dialect`）；手写的驱动没有方言时报错。
  - 返回的是核心包的 `Database`，没有 `PostgresDatabase.returning` 这类方言实例上的方法。
  - PolarDB 用 mysql2 的驱动，得到 MySQL 方言；要用闪回查询、库存提示时用 `@zerodep-dev/query-polardb` 的 `database(...)`。
  - 方言包导出的 `database(pool)`（接收驱动库的连接池）与核心包的 `database(driver)` 同名，从哪个包导入就是哪一个。

## 2. 声明实体

实体是带标准装饰器的普通类，只声明查询用到的列。列的类型由 `@column` 的第二个参数给出，默认为 `text`。装饰器会检查字段的声明类型：类型对不上就编译失败，字段可以带 `| null`。关系字段（`@one`、`@many`）也写在实体上，见第 4 节。查询回调里每个字段按它的类型成为对应的列接口，见本节末尾。

```ts
import { CalendarDate } from "@internationalized/date";
import { blankAsNull, column, converted, decimal, ignore, many, one, table, text, trimmed, type Many, type One, type Stored } from "@zerodep-dev/query-hana";
import type { Decimal } from "decimal.js";

export const sapDate = converted(text, {                     // 'yyyyMMdd' 文本 <-> CalendarDate
  toDatabase: (day: CalendarDate) => day.toString().replaceAll("-", ""),
  fromDatabase: (s: string) => (s.trim() === "" || s === "00000000" ? null : new CalendarDate(+s.slice(0, 4), +s.slice(4, 6), +s.slice(6, 8))),
});

export abstract class ClientEntity {                            // 公共列放在基类
  @column("MANDT") client!: string;
}

@table("LIPS", { schema: "ERP" })
export class Lips extends ClientEntity {
  @column("VBELN") delivery!: string;
  @column("MATNR") material!: string;
  @column("ERDAT", sapDate) createdOn!: Stored<CalendarDate, string> | null;   // 转换器：值是日期，库里存文本
  @column("LFIMG", decimal) quantity!: Decimal;                  // 精确小数
  @column("VRKME") unit!: string;
  @column("VGBEL") salesOrder!: string;

  @one(() => Mara, (l, m) => { m.material.eq(l.material); m.client.eq(l.client); }) mara!: One<Mara>;   // 多对一，连接条件是条件块
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
  quantity!: Decimal | null;
  @ignore computedLater?: string;                                // 不接收查询的值
}
```

| 装饰器 | 作用 |
| --- | --- |
| `@table(name, { schema })` | 标记表实体 |
| `@column(name?, type?)` | 列名（不写时按命名策略推导）与列的类型 |
| `@one(() => 目标, (self, target) => { 条件 })` | 多对一或一对一关系，字段类型 `One<T>` |
| `@many(() => 目标, (self, target) => { 条件 })` | 一对多关系，字段类型 `Many<T>` |
| `@where(op, ...columns)` | 条件对象字段的比较方式和列，见第 6 节 |
| `@ignore` | 不参与映射的字段；在条件对象里表示不作为条件 |
| `@readAs("NULLIF({0}, '')")` | SQL 级列转换：列在语句的每个位置都按模板读取，`{0}` 是带别名的列；返回的装饰器可以赋给常量复用 |
| `@blankAsNull`、`@trimmed` | 内置的列转换：空串读作 NULL（`NULLIF(col, '')`）、去掉首尾空格（`TRIM(col)`） |

**SQL 级列转换**与 `converted` 不同：`converted` 只在 JS 端改值，列转换写进 SQL，分组、聚合和条件看到的都是转换后的值。一个字段只能有一个读取模板；需要原值时再声明一个映射到同一列的字段，条件写在原值字段上才能用上索引。

**值类型**：TS 在运行时没有类型信息，所以非文本列必须声明类型。小数是 decimal.js 的 `Decimal`，日期和时间戳是 @internationalized/date 的 `CalendarDate`、`CalendarDateTime`；驱动按文本交接，不经过 `Date`，因此不受进程时区影响。

| 类型 | TS 值 | 说明 |
| --- | --- | --- |
| `text`（默认） | `string` | |
| `integer` | `number` | 超出安全整数范围时报错 |
| `smallint` | `number` | 与 `integer` 相同，只在结构变更里建成 SMALLINT |
| `bigint` | `bigint` | |
| `double` | `number` | 也可用于 DECIMAL 列，按浮点数读取 |
| `decimal` | `Decimal` | 精确；`Decimal` 不保留末尾的零，`new Decimal("3.000")` 与 `new Decimal(3)` 相同，写出 3 |
| `boolean` | `boolean` | 接受各库的 `1`/`0`、`t`/`f` |
| `date` | `CalendarDate` | 以 `'YYYY-MM-DD'` 交接，并校验日期是否存在 |
| `timestamp` | `CalendarDateTime` | 以 `'YYYY-MM-DD HH:MM:SS[.fraction]'` 交接；精确到毫秒，HANA 等更细的部分读出来时舍去 |
| `bytes` | `Uint8Array` | |
| `converted(storage, converter)` | 自定义 | 相当于 Java 的组合注解；字段类型与存储类型不是同一类时写成 `Stored<字段类型, 存储类型>`，见下 |

绑定参数时，小数、日期、时间戳也接受它们的文本形式（按格式校验），所以条件对象和原生 SQL 的参数可以直接用请求里的文本。

**列接口**：查询回调里实体的每个字段按字段类型成为一种列接口，类名表示库里怎么存（与 Java 的逻辑 SQL 类型同名），泛型参数是字段的 TS 类型；能点出哪些函数由它决定，文本列点不出 `sum()`，日期列点不出 `hour()`：

| 字段类型 | 列接口 | 表达式接口 | 主要的函数 |
| --- | --- | --- | --- |
| `string`（含字面量联合、字符串枚举） | `ColText<T>` | `ExprText<T>` | `like`、`contains`、`upper`、`substring`、`concat`、`stringAgg` 等 |
| `number`（整数和浮点数） | `ColNumber<T>` | `ExprNumber<T>` | `plus`、`minus`、`times`、`divide`、`sum`、`avg`、`round` 等 |
| `bigint` | `ColBigint<T>` | `ExprBigint<T>` | 同上，没有 `divide` |
| `Decimal` | `ColDecimal<T>` | `ExprDecimal<T>` | 同 `number` |
| `CalendarDate` | `ColDate<T>` | `ExprDate<T>` | `year`、`month`、`day`、`addDays`、`addMonths`、`monthStart`、`daysBetween` |
| `CalendarDateTime` | `ColTimestamp<T>` | `ExprTimestamp<T>` | `year` … `second`、`addHours`、`addMinutes`、`addSeconds`、`secondsBetween` |
| 其他（布尔、字节、JSON） | `Col<T>` | `Expr<T>` | 只有共有的方法 |

- **以另一种类型存储的字段**：SAP 以 'yyyyMMdd' 文本存的日期，字段写成 `Stored<CalendarDate, string>`，列就是 `ColText`：比较时传 `CalendarDate`（经转换器绑定成文本），文本函数作用在库里的文本上，日期函数点不出来（那样会生成作用在文本上的 `ADD_DAYS`）。忘了写 `Stored` 时 `@column` 在编译期报错。标记只在类型里，值就是 `CalendarDate`，查询结果的类型也是它。
- 写辅助函数时用这些接口作参数：收"任意文本"写 `ExprText<string | null>`，收小数写 `ExprDecimal<Decimal | null>`；计算的结果都按值类型给出接口（`ExprOf<T>`），所以 `l.unit.coalesce("EA").upper()`、`lag(l.quantity).over(...).divide(2)` 能接着点。
- `number` 在 TS 里不分整数和浮点数，所以 `divide` 在 `ExprNumber` 上，被除数是整数列时运行时报错（整数相除各库有的取整、有的不取整，先 `cast`）。

DTO 的字段来自类字段（加上被装饰的字段，去掉 `@ignore` 的），所以 DTO 需要无参构造器，字段要用 `name!: T` 声明（ES2022 类字段语义），不要写成 `declare name: T`。getter 和方法不参与映射。

## 3. 写查询

`from` 开启一个作用域，每 `join` 一张表，回调就多一个参数。回调参数是 `Ref<E>`：实体的每个字段都是一个列（第 2 节的列接口）。用了不在作用域里的表的字段，或者类型不符，都会编译失败。

**条件写成一条条语句**：`eq`、`in`、`isNull`、`exists` 这些条件方法不返回值，而是写进当前的条件块；块里的条件用 AND 连起来，可选条件就是 `if`。`where`、`having`、`qualify`、连接的 `on`、关系的 `where`/`any`/`none`、全局过滤器、`@one`/`@many` 的连接条件都是条件块；`any(() => { ... })` 的块里每个条件是一个 OR 选项，`all(() => { ... })` 把几个条件合成一个选项，`not(() => ...)` 取反。

```ts
const q = from(Lips)
  .join(Mara, (l, ma) => ma.material.eq(l.material))
  .leftJoin(Makt, (l, ma, mk) => {
    mk.material.eq(l.material);
    mk.language.eq("1");
  })
  .skipEmpty()                                               // 之后与空值比较的条件省略
  .where((l, ma) => {
    l.createdOn.between(start, end);                         // CalendarDate，经转换器绑定成文本；start 或 end 为空时只留下另一边
    ma.manufacturer.eq(maker);                               // maker 为 null/undefined 时省略
    if (onlyOrdered) exists(from(Vbkd).where((v) => v.salesOrder.eq(l.salesOrder)));
    any(() => {                                              // OR：块里每行一个选项
      l.unit.eq("EA");
      all(() => { l.unit.eq("KG"); l.quantity.lt(new Decimal(10)); });
    });
  })
  .groupBy((l, ma) => ma.partNumber)
  .orderBy((l, ma) => ma.partNumber)
  .select((l, ma, mk) => ({
    partNumber: ma.partNumber,
    name: mk.text.coalesce(ma.partNumber).max(),             // mk 是 LEFT JOIN 的表：mk.text 可能为 null
    quantity: l.quantity.sum(),
  }));

const rows = await db.list(q);   // { partNumber: string; name: string | null; quantity: Decimal | null }[]
```

作用域上的方法：

| 方法 | 说明 |
| --- | --- |
| `fullJoin(...)` | HANA、PostgreSQL 的 FULL JOIN（MySQL 渲染时报错），所有参数的列都变成可空；有全局过滤条件的表不能参与，要先 `unfiltered()` |
| `join` / `leftJoin` / `rightJoin(实体类或 Source, (…) => { 条件 })` | 连接条件里可以用到作用域内所有的表；`leftJoin` 之后该参数的列都可能为 null，`rightJoin` 之后之前各参数的列都可能为 null。`rightJoin` 时左侧各表的全局过滤条件放进它的 ON，补出来的行不会被 WHERE 过滤掉 |
| `crossJoin(...)` | 笛卡尔积 |
| `where((…) => { 条件 })` | 条件块；多次调用之间是 AND，块里没写条件（或都因 skipEmpty 省略）就没有条件 |
| `where(条件对象)` | 按请求对象筛选，见第 6 节 |
| `skipEmpty()` | 之后写的 WHERE、HAVING、连接条件里，与 `null`、`undefined`、空白字符串、空数组比较的条件直接省略 |
| `having`、`groupBy(… => expr 或 [expr, ...])` | 分组与分组后的条件 |
| `groupByRollup(… => [expr, ...])` | 分组并加上各级小计和总计：HANA、PostgreSQL 渲染 `ROLLUP(a, b)`，MySQL 渲染 `a, b WITH ROLLUP`；一次列出全部分组键，不能与 `groupBy` 同用 |
| `orderBy(… => item 或 [item, ...])` | 排序项可以是 `expr`（升序）、`expr.desc()`、`.nullsLast()` 等 |
| `distinct()`、`limit(n)`、`offset(n)` | — |
| `unfiltered()`、`unfiltered("tenant")`、`withDeleted()` | 不加全局过滤器和逻辑删除条件；只关掉这些名字的过滤器（名字没配置过时报错）；只关掉逻辑删除（看已删除的行） |
| `with(clause)` / `with((…) => clause)` | 方言子句，例如 `hint("NO_CS_JOIN")` |
| `pipe(fragment)` | 套用可复用的片段：`(scope) => scope.where(...)` |

**比较的其他写法**：

- NULL 也相等：`a.isNotDistinctFrom(b)`、`a.isDistinctFrom(b)`，结果总是真或假；PostgreSQL 写成 `IS [NOT] DISTINCT FROM`，MySQL 写成 `<=>`，HANA 写成等价的 CASE。
- 子查询：多列 `keys(l.delivery, l.material).in(q)` / `notIn(q)`（子查询选同样多的列）；与任一行或每一行比较 `l.quantity.gt(allOf(q))`、`x.eq(anyOf(q))`（`ANY` 遇到空子查询为假，`ALL` 为真）。
- 条件作为列：`bool(() => l.quantity.gt(new Decimal(0)))`，写成 `CASE WHEN ... THEN TRUE WHEN NOT (...) THEN FALSE END`，类型是 `boolean | null`（HANA 不能把条件直接写在 SELECT 里）。

**可选条件**：不开 `skipEmpty()` 时，`eq(null)` 会抛 `TypeError`（与 NULL 比较永远不成立，多半是漏了值），要判空用 `isNull()`。开启后，`eq`、`ne`、`gt`、`ge`、`lt`、`le`、`in`、`notIn`、`like`、`contains` 等遇到空值就不产生条件，`between` 缺一边时只留下另一边。需要自己控制时在块里写 `if`。

**复用条件**写成返回 `void` 的函数，在条件块里调用：`const activeMaterial = (m: Ref<Mara>): void => m.materialType.eq("ZMAT")`，`where((m) => { activeMaterial(m); ... })`。条件不能存进变量，也不能用 `&&` 拼（编译报错）；在条件块外写条件立即报错，投影、分组、排序的回调里写条件也报错，而不是悄悄并进外层的块。`range` 和缺一边的 `between` 虽然写两个比较，在 `any` 的块里仍是一个选项。

- 表数不设上限。
- 自连接时，第二个同名实体就是下一个参数，别名在整条语句里自动唯一。
- 关联子查询直接捕获外层回调的参数。`exists` / `notExists` 直接接收作用域，不用写 SELECT 列表。
- 泛型片段的回调要写成 `(...[l]) => ...`，这是 TS 的限制。

## 4. 关系与导航

关系声明在实体上（第 2 节），查询里像普通字段一样使用，不必手写 JOIN。

### 多对一：`@one`

`l.mara` 是目标表的引用，列可能为 null（`l.mara.partNumber` 的类型是 `ColText<string | null>`），可以继续往下取关系。只有被用到的关系才会 LEFT JOIN，同一个关系在一条语句里只连接一次；全局过滤器放进它的 ON。

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
| `any((t) => { 条件 }?)`、`none(...)` | 写进当前的条件块 | `EXISTS` / `NOT EXISTS` 子查询 |
| `count()`、`countDistinct(f)` | `ExprNumber<number>` | 相关标量子查询 `(SELECT COUNT(*) ...)` |
| `stringAgg(f, sep)` | `ExprText<string | null>` | 相关标量子查询拼接文本，顺序取 `orderBy(...)` |
| `sum(f)`、`max(f)`、`min(f)`、`avg(f)` | 可空的值，接口按值类型 | 相关标量子查询；没有行时为 null |
| `where((t) => { 条件 })` | `Many` | 先筛选目标行，再用上面的操作 |
| `where(filter)` | `Many` | 用条件对象筛选目标行（规则同第 6 节，路径从目标表开始），前端的条件于是能筛子集合 |
| `through((ur) => ur.role)` | 目标表的 `Many` | 多对多：到中间表的一对多再沿中间表的 `@one` 走到目标表，见下 |
| `orderBy(f)` | `Many` | 决定 `list`、`stringAgg` 的顺序 |
| `limit(n)` | `Many` | `list` 每个父行只取按顺序的前 n 行（批量查询里用 ROW_NUMBER 按父键分区），须先 `orderBy` |
| `first(order)` | 目标表的引用（列可为 null） | 每组按 `order` 取第一行 |
| `list(projection)` | 子行数组 | 批量查询，见下 |

```ts
from(Mara)
  .where((m) => {
    m.deliveries.any((l) => l.quantity.gt(new Decimal(1)));
    m.deliveries.where((l) => l.unit.eq("KG")).none();
  })
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

**多对多**：先声明到中间表的 `@many`，再用 `@manyThrough` 沿中间表的 `@one` 走到目标表：

```ts
@table("USER_ROLE") class UserRole {
  @column("USER_ID", integer) userId!: number;
  @column("ROLE_ID", integer) roleId!: number;
  @one(() => Role, (ur, r) => r.id.eq(ur.roleId)) role!: One<Role>;
}
// User 里：
@many(() => UserRole, (u, ur) => ur.userId.eq(u.id)) userRoles!: Many<UserRole>;
@manyThrough((u) => u.userRoles, (ur) => ur.role) roles!: Many<Role>;

from(User).where((u) => u.roles.any((r) => r.name.eq("admin"))).select((u) => ({ name: u.name, roles: u.roles.orderBy((r) => r.sortNo).list((r) => r.name) }));
```

查询的是中间表，回调拿到的是导航过去的目标行，用到的目标列在子查询里 LEFT JOIN 过去；所以 `any`、`count`、`sum`、`list`、`first`、`groupJoin` 和条件对象的路径（`@where("eq", "roles.name")`）都照常可用。`count()` 是中间表的行数，用不到目标列时不连接目标表。查询时也可以直接写 `u.userRoles.through((ur) => ur.role)`；`through` 要紧跟在到中间表的关系后面，`where`、`orderBy`、`limit` 写在它之后。

**合成一个分组连接（`groupJoin`）**：默认每个 `count`、`sum`、`any` 等都是一个相关子查询。同一关系上的运算较多、父表较大时，在作用域上写 `.groupJoin((m) => m.deliveries)`，这些运算合成一个按连接键分组的派生表，只 LEFT JOIN 一次；关系上的 `where` 筛选写成各自聚合的 `CASE WHEN`，行数和 `any`/`none` 在没有子行时按 0 处理，`sum` 等仍为 null；`stringAgg`、`first`、`list` 不参与合并。写法与 Java 相同，金样例 `group-join` 保证两端 SQL 一致。

**`list(projection)`**：每个父行得到一个子行数组，放进对象投影。投影可以是一个表达式（得到值数组）或对象（得到对象数组），子投影里还可以再有 `list(...)`。

```ts
const tree = from(Mara)
  .orderBy((m) => m.material)
  .select((m) => ({
    material: m.material,
    text: { text: m.text.text },                              // 嵌套对象
    lines: m.deliveries.orderBy((l) => l.delivery).list((l) => ({ delivery: l.delivery, quantity: l.quantity })),
  }));
const rows = await db.list(tree);   // { material: string; text: { text: string | null }; lines: { delivery: string; quantity: Decimal }[] }[]
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
q.map((r) => ({ ...r, wan: r.quantity?.div(10_000).toNumber() }));   // 在 JS 端计算
```

- 列名与字段名不同、或要加计算列时，在 `select` 里把键写成字段名，再 `.into(VO)`。
- 结果按列的位置读取，不依赖数据库返回的标签大小写。
- 只有扁平的对象投影（没有嵌套、没有 `list`，也没有 `map`/`into`）可以作为 CTE 或派生表。

### 动态列与排序

按用户选择的维度汇总、按用户的排序参数排序，不必拼接 SQL：

```ts
const totals = from(Lips)
  .groupBy((l) => [l.material, l.unit, l.delivery.substring(1, 1)])
  .orderBy((l) => [l.material.asc(), l.unit.asc()])
  .select((l) => ({ material: l.material, unit: l.unit, prefix: l.delivery.substring(1, 1), total: l.quantity.sum() }));

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
| `sortBy("-total,material")` | 按结果列排序并替换原来的 ORDER BY；每项是结果列名，前面带减号是倒序（同 JSON:API，前端 `useTableQuery` 发的就是这种写法），也认 `total desc`；空串保持不变 |
| `filterBy({ unit: ["EA", "KG"] })` | 结果列 IN (...)：表头的多选筛选；值是地址栏里的文字，按列的类型读，空白的值跳过 |
| `searchBy({ material: "泵" })` | 结果列 LIKE %泵%：表头的关键字筛选，只能用在文字列上 |

- 名字是结果的键，不分大小写和下划线（`only(["PREFIX"])` 能匹配 `prefix`）；嵌套对象的键代表它下面的全部列；不存在的名字报错并列出可用的列。
- `only`、`sortBy`、`filterBy`、`searchBy` 只认结果里的列，相当于白名单，用户输入可以直接传入；排序串写法不对、按被排除的列排序、对 UNION 使用 `sortBy`（先包成 CTE）都会报错。
- `filterBy`、`searchBy` 的条件在渲染时决定位置：逐行计算的列写进 WHERE，聚合的列（`sum()`、`stringAgg` 等）写进 HAVING；窗口函数的列不能这样筛选。

## 6. 条件对象

`where(search)` 把请求对象直接变成条件：每个字段一个条件，值为空（`null`、`undefined`、空白字符串、空数组）的字段不产生条件，也不会连接只被它用到的关系。

```ts
class DeliverySearch {
  material?: string[];                                           // 默认：与同名列比较，数组用 IN
  @where("ge", "createdOn") from?: CalendarDate;                 // 比较方式 + 列
  @where("lt", "createdOn") to?: CalendarDate;
  @where("contains", "mara.partNumber", "text.text") keyword?: string;   // 经关系取列；多列任一满足（OR）
  @where("ne") unit?: string;                                    // 只指定比较方式，列取字段名
  @where("range", "createdOn") created?: Range<CalendarDate>;    // 两端都含的区间，{ start, end } 或 [start, end]
  @ignore sort?: string;                                         // 不作为条件
  @where((c, v: string) => c.range(parseDate(`${v}-01`), parseDate(`${v}-01`).add({ months: 1 })), "createdOn") month?: string;  // 自定义比较（条件块），值为空时不调用
}

from(Lips).where(search).select((l) => l.delivery)   // search: DeliverySearch 的实例
```

传入 `{ material: ["M1", "M2"], from: parseDate("2026-01-01"), to: parseDate("2026-07-01"), keyword: "50%", unit: "KG" }` 得到（日期、小数字段也可以直接是请求里的文本，绑定时按格式校验）：

```sql
SELECT LIPS.VBELN AS "delivery"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
WHERE LIPS.MATNR IN (?, ?) AND LIPS.ERDAT >= ? AND LIPS.ERDAT < ? AND ((MARA.MFRPN LIKE ? ESCAPE '!') OR (MAKT.MAKTX LIKE ? ESCAPE '!')) AND LIPS.VRKME <> ?
```

- 比较方式 `eq`（默认）、`ne`、`gt`、`ge`、`lt`、`le`、`contains`、`startsWith`、`endsWith`、`range`；`contains` 等三个转义 `%` 和 `_`。数组只能用 `eq`（IN）、`ne`（NOT IN）和 `range`。
- `range` 是两端都含的区间：值是 `{ start, end }`（vue-tsx-pro 的 DateRangePicker 给的就是它）或两项的数组（地址栏里两个同名参数），空的一端不加条件。日期的一端（`CalendarDate` 或 `YYYY-MM-DD`）落在时间戳列上时，起端是那天的零点，止端写成"早于后一天的零点"，这一整天都算在内。
- 字段的值是文字（地址栏参数）时按列的类型读（`parseColumnText`：整数、bigint、小数、日期、时间戳、布尔）；空白的文字不加条件，数组里的空白值也去掉。转换器列的字段类型无从得知，文字原样交给绑定。
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
- **UNION、INTERSECT、EXCEPT**：`q1.unionAll(q2)`、`union`、`intersect`、`except`（后两者去重）。两段的行类型必须相同，否则编译失败。对象投影按键名对齐各段的列，键的书写顺序不同也不会错位。各段不能自带排序或分页，分页和排序加在合并之后（要排序先包成 CTE）。调用顺序就是求值顺序：`a.union(b).intersect(c)` 是 `(a ∪ b) ∩ c`。PostgreSQL、MySQL 的 INTERSECT 优先于 UNION，HANA 从左到右，所以读法可能不同的地方都自动加括号。`INTERSECT ALL`、`EXCEPT ALL` 只有 PostgreSQL、MySQL 支持，在方言包。
- **窗口**：`rowNumber().over((w) => w.partitionBy(a).orderBy(b.desc()))`。聚合也可以 `.over(...)`，ROWS 框架写作 `w.rows(Bound.unboundedPreceding(), Bound.currentRow())`。另有 `lag`、`lead`（第三个参数是越界时的默认值）、`firstValue`、`lastValue`、`nthValue(e, n)`（取整个分区里的行要写 ROWS 框架）、`ntile(n)`、`percentRank()`、`cumeDist()`。
- **窗口条件（QUALIFY）**：`qualify((l) => rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc())).le(3))` 每组取前 3 行。HANA 没有 QUALIFY 子句，三个库都渲染成派生表：窗口函数和不在结果里的排序项作为额外的列，外层过滤、排序和分页。条件只能比较窗口函数，其他条件写在 `where` 或 `having` 里，否则报错。
- **常量行**（码表）：`values([{ name: "未逾期", ord: 1 }, ...])` 得到一个查询，每行渲染成一个字面量 SELECT，用 UNION ALL 连接，HANA 上加 `FROM DUMMY`。列类型按值推断（文本、整数、小数），日期或精确小数用第二个参数指定，例如 `values(rows, { day: date })`。可以用 `cte(...)` 包成 CTE，再参与连接。

业务口径写成普通函数，用接口约束适用的表：

```ts
interface PeriodStock { client: string; material: string; year: string; period: string }

function stockArm<E extends PeriodStock>(table: Table<E>, quantity: (h: Ref<E>) => Expr<Decimal>, year: string) {
  return from(table).where((h) => h.year.eq(year))
    .select((h) => ({ material: h.material, year: h.year, period: h.period, quantity: quantity(h) }));
}

stockArm(Mardh, (h) => h.unrestricted.coalesce(new Decimal(0)), "2026")
  .unionAll(stockArm(Mchbh, (h) => h.batchUnrestricted.coalesce(new Decimal(0)), "2026"));
```

**两种常见写法**：

- **一组列求和**，例如十六个期间列：键写成联合类型的数组，`PERIODS.map((p) => f[p].coalesce(new Decimal(0))).reduce((a, b) => a.plus(b))`；接着写的 `plus` 并成一个括号 `(a + b + ...)`。按期间展开成行，则是对同一个数组逐个生成 `select`，再用 `unionAll` 合并。
- **按条件追加的过滤**：需要连接另一张表才能判断时，写成可选的 EXISTS，不要按条件拼接 JOIN：`if (supplier != null) exists(from(Sup).where((s) => ...));`；能用关系表达的，直接用第 4 节的 `l.mara.xxx`（没用到就不连接）。

## 8. 表达式与函数

**有主操作数的运算是它的实例方法**，按第 2 节的列接口点出来；接收者先固定类型，参数类型不符就编译失败。函数名沿用 SQL 的叫法，与 Java 相同。

**所有表达式**（`Expr<T>`）：

- 条件（写进当前的条件块）：`eq`、`ne`、`gt`、`ge`、`lt`、`le`，参数可以是值或表达式；`between`（含两端）、`range(from, until)`（半开区间 `>= from AND < until`，适合日期段）、`in(数组 | 查询)`、`notIn`、`isNull`、`isNotNull`、`isDistinctFrom`、`isNotDistinctFrom`。值为空时的行为见第 3 节的 `skipEmpty()`。
- 值不变的函数，结果仍是本类型：`coalesce`（可以给多个后备值：`a.coalesce(b, c, "EA")`，渲染成一个 COALESCE）、`nullif`、`max()`、`min()`；聚合上的 `filter(() => { 条件 })`（只聚合满足条件的行：`l.quantity.sum().filter(() => l.unit.eq("KG"))` 写成 `SUM(CASE WHEN ... THEN ... END)`）和 `over(...)`，接在非聚合上立即报错。
- `count()`（不计 NULL）、`countDistinct()`；COUNT(*) 是静态的 `count()`。
- 排序与转换：`asc()` / `desc()`（加 `.nullsFirst()` / `.nullsLast()`）、`as("label")`、`cast(CastType.decimal(15, 3))`（结果的接口按目标类型，这里是 `ExprDecimal`）。

| 类型 | 实例方法 |
| --- | --- |
| 文本 `ExprText` | `like`、`notLike`（模式原样，`%` `_` 是通配符）、`contains`、`startsWith`、`endsWith`（转义通配符）；`upper`、`lower`、`trim`、`concat(...)`（MySQL 自动渲染为 `CONCAT`）、`substring(start, length?)`、`left`、`right`、`replace(from, to)`、`locate(search)`（位置从 1 开始，找不到时为 0；位置可以是表达式：`e.substring(e.locate(",").plus(1))`）、`lpad(n, pad)`、`rpad(n, pad)`（补足到 n 个字符，物料号补零 `m.material.lpad(18, "0")`，过长时截断）、`stringAgg(sep, ...order)`（MySQL 写成 GROUP_CONCAT） |
| 数值 `ExprNumber`、`ExprBigint`、`ExprDecimal` | `plus`、`minus`、`times`（参数是常量或表达式；`a.plus(b).plus(c)` 并成 `(a + b + c)`）、`divide`（只在 `number`、`Decimal` 上；被除数是整数列时运行时报错）、`mod`（符号同被除数）、`negate`、`abs`、`round(n)`、`floor`、`ceil`、`power`、`sqrt`（结果是 number）、`sign`；聚合 `sum`、`sumDistinct`、`avg`、`avgDistinct`（结果是 `Decimal`）、`stddevSamp`、`stddevPop`、`varSamp`、`varPop`（`number \| null`，参数先转成 DOUBLE，各库结果一致；不提供不带后缀的 `stddev`，因为 MySQL 的 STDDEV 是总体标准差）。除数为 0 时 HANA、PostgreSQL 报错，MySQL 得到 null |
| 日期 `ExprDate` | `year`、`month`、`day`、`addDays`、`addMonths`、`addYears`（个数可为负，月末对齐）、`monthStart()`（当月第一天）、`daysBetween(to)` |
| 时间戳 `ExprTimestamp` | `year`、`month`、`day`、`hour`、`minute`、`second`（整数）、`addHours`、`addMinutes`、`addSeconds`、`secondsBetween(to)`（整秒） |

**数值的类型提升**：两边类型不同时取"宽"的一方：小数 > 大整数 > 数字（整数列加小数列是 `ExprDecimal`），与 Java 一致；`number` 是浮点数而另一边是 `bigint` 时运行时报错，先 `cast`。算出新值的运算回到库里的类型：以文本存的日期（`Stored`）`upper()` 之后是 `ExprText<string>`。

**静态函数**只留没有主操作数的：

| 类别 | 函数 |
| --- | --- |
| 条件块 | `any(() => { ... })`、`all(() => { ... })`、`not(() => ...)`、`exists(q)`、`notExists(q)`、`bool(() => ...)`、`sqlCond` |
| 值 | `val(x, type?)`（参数）、`lit(x, type?)`（字面量，只用于固定代码值）、`nullOf(type)`；结果按值的类型给出接口（`val(new Decimal(1))` 是 `ExprDecimal`），文本、数字、布尔的类型放宽为 `string`、`number`、`boolean`，所以只差一个常量的 UNION 各段类型相同 |
| 聚合与分组 | `count()`（COUNT(*)）、`grouping(e)`（汇总行为 1） |
| 窗口 | `rowNumber`、`rank`、`denseRank`、`percentRank`、`cumeDist`、`ntile(n)`、`lag(e, n, 默认值?)`、`lead`、`firstValue`、`lastValue`、`nthValue(e, n)`，再加 `.over(...)`；结果与 e 同一类（`lag(l.quantity).over(...)` 仍是小数） |
| 子查询 | `scalar(query)`、`anyOf(query)`、`allOf(query)`、`keys(a, b).in(query)` |
| CASE | `caseWhen(() => 条件, x).when(() => 条件, y).otherwise(z)`、`caseOf(e).when(值, x).otherwise(...)`（简单 CASE，适合内联的码表） |
| 其他 | `currentDate()`、`currentTimestamp()`；兜底的 `sql(type)` 与 `sqlCond`，见第 9 节 |

文本、数值、日期上的函数在构造时还会按列的存储种类检查一次，绕过类型检查（`any`、JavaScript）调用时立刻报错，例如 `SUM needs a numeric expression, got text`。

**方言专属能力**只在对应的包里提供，放到别的方言上渲染时会明确报错。有主操作数的经由视图点出来，视图按参数的类型给出方法，结果回到核心的类型接口：`hana(l.createdOn).toVarchar("YYYYMMDD").upper()`、`hana(l.delivery).toDate("YYYYMMDD").addDays(1)`、`postgres(l.unit).eqAny(units)`（条件写进当前的块）、`mysql(e.onDay).dateFormat("%Y%m")`、`sqlite(l.text).glob("A*")`。

| 视图 | 文本上 | 数值上 | 任意表达式上 |
| --- | --- | --- | --- |
| `hana(e)` | `toDate(fmt)`、`toInteger()`、`length()`（按 UTF-16 单元计）、`likeRegexpr(p)`、`jsonValue("$.a")`（路径必须写在代码里） | `toInteger()`、`median()` | `toVarchar(fmt)`、`toNvarchar(fmt?)` |
| `postgres(e)` | `ilike(p)`、`jsonText("owner", "name")`、`jsonContains(json)`、`jsonHasKey(key)` | — | `eqAny(values)`（`= ANY(?)`，一个数组参数） |
| `mysql(e)` | `jsonText("$.owner.name")`、`jsonContains(json)` | — | `dateFormat(fmt)` |
| `sqlite(e)` | `glob(p)` | `median()` | — |

没有主操作数的照旧是方言包的静态函数：

| `@zerodep-dev/query-hana` | `@zerodep-dev/query-postgres` | `@zerodep-dev/query-mysql` |
| --- | --- | --- |
| `hint(...)` | `distinctOn(...)` 子句 | `maxExecutionTime(ms)` 优化器提示 |
| `cube(a, b)`、`groupingSets([a, b], [a], [])` | 同左 | — |
| `greatest(a, b, ...)`、`least`：有一个参数为 NULL 就返回 NULL | `greatest`、`least`：跳过 NULL 参数 | 同 HANA |
| `percentileCont(p, e).over(...)`、`percentileDisc`（HANA 只把百分位数当窗口函数）、`corr(y, x)` | `percentileCont(p, e)`、`percentileDisc`、`mode(e)`（只作聚合）、`corr(y, x)` | — |
| — | `intersectAll(a, b)`、`exceptAll(a, b)` | 同左（8.0.31 起） |
| — | 窗口帧 `w.frame(range(daysPreceding(7), Bound.currentRow()))`，数值偏移用 `Bound.preceding(n)` | 同左 |
| — | `recursiveCte(name, anchor, (self) => step)`，写成 `WITH RECURSIVE` | 同左（受 `cte_max_recursion_depth` 限制） |
| — | `forShare(lock)`、`forUpdateOf("skipLocked", l)`（`FOR UPDATE OF 别名`） | 同左 |
| — | `db.returning(write)`：插入、更新、删除后用 RETURNING 读回表的全部列 | — |
| 数据来源：`fromView(View, { P_YEAR: "2026" })`（计算视图的 PLACEHOLDER 参数）、`fromFunction(Fn, { P_UNIT: "EA" })`（表函数的命名实参） | 数组：`arrayContains`、`arrayOverlaps`（条件）、`cardinality`、`arrayAgg(e, ...order)`；数组列声明成 `@column("TAGS", arrayOf(text))` | 优化器提示：`index(l, "idx")`、`noIndex(m)`，与 `maxExecutionTime` 合并进同一个 `/*+ ... */`（MySQL 只认第一个提示注释） |
| 层级：`from(hierarchy(来源))`、`descendants(来源, ["B"])`（每行带上 hierarchyRank、hierarchyLevel 等列）、`ancestors`，来源查询要有 nodeId、parentId 两列 | JSONB 写入用 `jsonb(text)` | — |
| 全文检索：`contains(term, cols...)`、`containsFuzzy(0.8, term, cols...)`（条件）、`score()` | 全文检索：`textMatches("simple", doc, q)`、`webMatches`（条件）、`textRank` | 全文检索：`match(q, cols...)`、`matchBoolean`（条件）、`matchScore`（列要正好是一个 FULLTEXT 索引） |
| — | — | SQLite（`@zerodep-dev/query-sqlite`）：`percentileCont(p, e)`、`percentileDisc` |

各库对 `greatest`、`least` 的 NULL 处理不同，所以它们放在各自的方言包里。`cube` 和 `groupingSets` 是分组键，写在 `groupBy` 里：`groupBy((l) => groupingSets([l.material, l.unit], [l.material], []))`，空数组是总计。

**常量与参数**：SELECT 列表里的常量写成字面量；带 GROUP BY 或 DISTINCT 的查询，GROUP BY、HAVING、ORDER BY 里的常量也写成字面量。WHERE 和 ON 里的值仍然绑定为参数。这样分组表达式（例如按 CASE 分桶）在三个库上都能执行，HANA 也能确定 SELECT 列的类型。需要绑定的值写在 WHERE 中。

## 9. 原生 SQL 片段

构建器表达不了的部分，用模板字符串嵌入。模板里的表达式（列、其他片段、`identifier(...)`）原位渲染，其他值（字符串、数字、布尔、字节、`Decimal`、日期）成为绑定参数（出现在 SELECT 列表里时写成字面量），数组展开成逗号分隔的多个参数；空数组和 `null` 报错，要 NULL 就写在模板里。

```ts
sql(text)`LPAD(${l.material}, ${18}, '0')`            // 带类型的表达式，按类型给出接口，可以接着点 .upper()；不写类型 sql`...` 时值按驱动返回的样子读取
sqlCond`${l.unit} IN (${["EA", "KG"]})`                // 条件，写进当前的条件块 -> LIPS.VRKME IN (?, ?)
```

- **按方言改写**：`.on(方言类)` 给某个方言另一种写法，渲染时取与当前方言最接近的（继承层次最深的匹配项），都不匹配时用默认写法。`sql(...)`、`sqlCond`、`sqlTable` 都支持；`sqlCond` 一写下就在条件块里，`.on(...)` 改的是块里的这个条件。

  ```ts
  sql(integer)`DAYS_BETWEEN(${a}, ${b})`.on(MysqlDialect)`DATEDIFF(${b}, ${a})`   // MysqlDialect 来自 @zerodep-dev/query-mysql
  ```

- **`identifier(name)`**：按方言加引号的标识符，让同一段模板在三个库上都能用。
- **`sqlTable({ 列: 类型 })`**：原生查询作为数据源，放进 `from` 或 `join`。查询的每一列要用声明里的列名作别名并按方言加引号，因为外层按这些名字引用列；类型决定外层看到的列类型。

```ts
const totals = sqlTable({ material: text, total: decimal })`SELECT ${identifier("MATNR")} AS ${identifier("material")}, SUM(LFIMG) AS ${identifier("total")} FROM QUERY_IT.LIPS WHERE VRKME = ${"EA"} GROUP BY ${identifier("MATNR")}`;

from(totals)
  .join(Mara, (t, m) => m.material.eq(t.material))
  .where((t) => t.total.gt(new Decimal(1)))
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
| `db.capture(async () => {...})` | 返回这段代码（含其中的异步调用）通过本数据库执行的全部语句（`QueryEvent[]`，含缓存命中），可以嵌套；需要 Node 的 `AsyncLocalStorage`，浏览器里报错 |
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
| `db.optional(q)` | 零行或一行：没有时为 `undefined`，多于一行报错；最多读 2 行 |
| `db.first(q)` | 第一行或 `undefined`，最多读 1 行 |
| `db.stream(q)` / `db.forEach(q, fn)` | 在一个游标上逐行读取：HANA 用 ResultSet，PostgreSQL 用服务端游标（需要连接池），MySQL 用流式查询（需要连接池）；提前 `break` 会释放游标。带 `list(...)` 的查询先读完全部行再交付 |
| `db.count(q)` | 行数。单段、没有分组、DISTINCT、HAVING、QUALIFY、LIMIT 和方言子句、投影里没有聚合时直接 `SELECT COUNT(*) FROM ... WHERE ...`：投影里的标量子查询、窗口函数和只为投影连接的导航关系都不写；其余情况外包 `COUNT(*)` |
| `db.exists(q)` | 是否至少有一行：`SELECT CASE WHEN EXISTS (q) THEN 1 ELSE 0 END`，不读出行 |
| `db.page(q, page, size, total?)` | 页号从 1 开始，同时返回总数和总页数；传入已知的 `total` 时不再执行 COUNT |
| `db.table(q, parseTableRequest(params))` | 表格查询：依次 `filterBy`、`searchBy`、`sortBy`、分页，请求带了已知总数时不 COUNT，见第 21 节 |
| `db.withAncestors(Dept, { key, parent, hits, select })` | 命中的行加上全部祖先（树的服务端搜索），交给 `buildTree` 组装；PostgreSQL、MySQL、SQLite 一条递归 CTE，HANA 一层一层按键查，见第 21 节 |
| `db.attach(rows, key, (ids) => query, keyOf, set)` / `db.attachMany(...)` | 按键把另一个查询的结果装回已读出的行（一对一 / 一对多）：键去重后每批 1000 个查询，没有对应的写 null / 空数组 |
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
| 值类型 | 实现 `SqlType<T, 种类>`，或用 `converted(storage, converter)` 包装已有类型；字段类型与存储类型不是同一类时字段写成 `Stored<J, D>` |
| 函数 | 写一个普通函数：`call(FunctionDef.of("MY_FN"), integer, ...args)`。只属于某个方言的用 `FunctionDef.only(HanaDialect, "...")`；单个数据库实例需要改写时，用选项 `functions: new Map([[def, renderer]])` |
| 表达式与条件 | `expression(type, (w) => ...)`（结果按 T 给出接口）、`condition((w) => ...)` 再 `addCondition(c)` 写进条件块，或者继承 `ExprNode<T>` |
| 子句 | 实现 `Clause`，声明位置（`"hint"` / `"distinct"` / `"end"`）和所属方言 |
| 数据来源 | 实现 `Source<E>`，例如带参数的视图 |
| 方言 | 继承 `Dialect` 或某个现成方言，覆盖引号、字面量、占位符、分页、排序项和类型名 |
| 驱动、监听、缓存 | `Driver`、`listener`、`QueryCache` |
| 执行前改写或拦截 | 选项 `interceptors: [(statement) => statement]`：每条语句（查询、写语句、计数、原生 SQL）执行前依次调用，返回要执行的 `Rendered`；抛出异常则不执行。`cached(...)` 的查询用改写后的 SQL 作缓存键 |
| 条件对象的比较 | `@where((column, value) => { 条件 }, "列")`，见第 6 节 |
| 结果错误 | `db.one` 没有行时抛 `EmptyResultError`，多于一行（以及 `db.optional`）抛 `TooManyRowsError` |

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
- `@column` 的类型与字段类型不符；以另一种类型存的转换器列没写 `Stored<J, D>`；
- 文本列点数值函数、以文本存的日期点日期函数、日期点时分秒、`bigint` 相除；
- 条件当值用（`.and(...)`、`&&`）；以文本存的日期赋日期表达式；审计填充的列不是列而是计算出的表达式。

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

## 15. 写入

写语句是不可变的值，用法与 Java 版第 15 节相同；`db.render(write)` 看 SQL，`await db.execute(write)` 执行并返回影响的行数。设计取舍见 [0007](decisions/0007-writes.md)。

```ts
abstract class Audited {
  @column("CREATED_AT", timestamp) createdAt!: CalendarDateTime | null;
  @column("UPDATED_AT", timestamp) updatedAt!: CalendarDateTime | null;
  @column("UPDATED_BY") updatedBy!: string | null;
}

@table("DOC", { schema: "APP" })
class Doc extends Audited {
  @id @column("ID") id!: string;                          // 主键，可多列
  @column("TITLE") title!: string | null;
  @version @column("VERSION", integer) version!: number;  // 乐观锁版本，integer 或 bigint
  @logicDelete() @column("DELETED", integer) deleted!: number; // 逻辑删除，默认 0 未删除、1 已删除；logicDelete("N", "Y") 改值
  @readOnly @column("SOURCE") source!: string | null;     // 只读：数据库默认值或触发器维护，本库从不写
}

@table("SEQ")
class Seq {
  @id @generated @column("ID", integer) id!: number;     // 数据库生成的主键：插入时不写
  @column("NAME") name!: string;
}
```

| 写法 | 说明 |
| --- | --- |
| `insertInto(Doc).values({ id: "D1", title: "a", version: 1 }, ...)` | 行是普通对象，键对应字段；各行的键必须相同，值为 null 写 NULL。行在一个事务里逐行执行 |
| `insertInto(Note).from(query)` | 查询的结果键对应字段 |
| `update(Note).set((n) => ({ qty: n.qty.plus(10), status: "closed" })).where(...).expect(1)` | SET 的值可以是值或表达式（表达式要是这一列的那一类，以文本存的日期不能赋日期表达式）；`skipEmpty()` 省略空值；影响的行数不符时抛 `UnexpectedRowCountError` |
| `deleteFrom(Note).where((n) => n.status.eq("closed"))` | 没有 where 的 UPDATE、DELETE 报错，`.all()` 放行；全局过滤器和逻辑删除的条件加进 WHERE，`unfiltered(...)`、`withDeleted()` 同查询 |
| `update(Lips).set(...).where((l) => l.mara.manufacturer.eq("MK1"))` | WHERE 里的导航写成 `EXISTS (SELECT 1 FROM (SELECT 1) seed LEFT JOIN MARA ... WHERE ...)`，与查询里导航的语义相同；SET 里不能用导航 |
| `update(Doc).byId(row)`、`deleteFrom(Doc).byId(row)` | 按主键（和版本）定位一行，SET 其余字段、版本加一；版本不对时抛 `UnexpectedRowCountError` |
| `update(Doc).byId(edited).diffFrom(loaded)` | 差异更新：只 SET 与修改前不同的字段（小数按数值比较，日期按值比较）；完全没变的行不发语句、不加版本、不计入期望行数；`changedRows` 是会被写的行 |
| `@readOnly`、`@writeOnce` | 只读列插入和更新都不写，`set(...)` 显式写报错；只插入列（例如创建时间）插入时写，`byId` 和插入或更新的更新部分不写 |
| `.withoutFills()` | 插入、更新、删除都可以加：不套用审计填充，例如迁移数据时保留原值 |
| `await db.insertKeys<number>(insertInto(Seq).values(...))` | 按行的顺序取回 `@generated` 列：PostgreSQL 用 `RETURNING`，MySQL 用 `insertId`，HANA 在同一连接上查 `CURRENT_IDENTITY_VALUE()` |
| `.onConflict().update((d) => d.title)`、`.onConflict((d) => d.id).doNothing()` | 插入或更新；不写键时用 `@id` 的列，`update()` 不写列时更新键以外的全部列 |
| `.with(forUpdate("skipLocked"))` | 加锁读，另有 `"wait"`（默认）、`"nowait"`；HANA 写作 `IGNORE LOCKED` |
| `update(Lips).with((l) => noIndex(l, "IX_LIPS")).set(...)`、`deleteFrom(...).with(...)` | 写在 UPDATE、DELETE 关键字后面的提示（MySQL 的优化器提示、PolarDB 的 `inventory(n)`），同一方言的合并进一个注释，别的方言的提示渲染时报错；插入暂不支持 |
| `await db.transaction(async (tx) => { ... })` | 一个连接上的事务：正常返回提交，抛出时回滚，嵌套调用加入外层事务。`tx` 是绑定在事务上的同一种数据库，经由它执行的语句都用事务的连接；在 Node 上里面直接用 `db.*` 也经 AsyncLocalStorage 用同一个连接，浏览器里必须用 `tx`（见第 19 节） |
| `deleteFrom(Doc).where(...)` 在带 `@logicDelete` 的表上 | 改写成 `UPDATE ... SET DELETED = 1`，`.physically()` 才真正删除；查询、连接、关系都自动加"未删除"条件；插入时补上未删除的值；`timestamp` 列记录删除时间（`IS NULL` / `CURRENT_TIMESTAMP`）；`@logicDelete({ epochMillis: true })` 的 bigint 列未删除为 0、删除写当前毫秒数，`UNIQUE (业务键, 删除列)` 允许同一个键删除后再建 |

**审计字段自动填充**：

```ts
const db = database(pool, {
  fills: [
    onInsert(Audited, (a) => a.createdAt, () => now()),
    onUpdate(Audited, (a) => a.updatedAt, () => currentTimestamp()),  // 也可以返回表达式
    onUpdate(Audited, (a) => a.updatedBy, () => currentUser()),
  ],
});
```

逐行生成的列用 `generate(Keyed, (k) => k.id, () => randomUUID())`，放进同一个 `fills`：只填行里为 null 或没有这个字段的行，每行调用一次；生成的值不写回行对象。

按实体的基类匹配。插入时填 `onInsert` 的列；更新、`byId` 更新、插入或更新的更新部分、逻辑删除都填 `onUpdate` 的列；值每条语句取一次，总是覆盖行里或 SET 里的同名项。`toSql(dialect)` 没有数据库实例，不带填充。

自己实现的 `Driver` 要在 `DriverResult` 里返回 `affected`（MySQL 还有 `insertId`），并实现 `transaction(work)` 才能用 `db.transaction` 和多行写入。

**联表更新与删除**（只在方言包里）：

```ts
import { deleteJoin, updateJoin } from "@zerodep-dev/query-postgres"; // 或 query-mysql、query-hana

await db.execute(
  updateJoin(Note, Doc, (n, d) => n.id.eq(d.id))
    .set((_n, d) => ({ title: d.title }))
    .where((_n, d) => d.version.gt(1)),
);
await db.execute(deleteJoin(Note, Doc, (n, d) => n.id.eq(d.id)).where((_n, d) => d.title.eq("gone")));
```

写法和规则同 Java 版第 15 节：PostgreSQL 写成 `UPDATE ... FROM` / `DELETE ... USING`，MySQL 写成 `UPDATE ... JOIN ... SET` / `DELETE 别名 FROM ... JOIN`，HANA 写成按主键对上行的 MERGE（被改表要有 `@id`）和 `WHERE EXISTS`。每个被改行至多匹配一个连接行，否则 PostgreSQL、MySQL 任取一行，HANA 报错；没有 where 时报错，`.all()` 放行；不能经过导航关系。

### 实体追踪

与 Java 版相同（`tracked` 在 TS 里不需要，`tracking` 本身返回 work 的结果）：

```ts
const d = await db.tracking(async (t) => {
  const doc = await t.one(Doc, from(Doc).where((x) => x.id.eq("D1")).select((x) => ({ id: x.id, title: x.title, version: x.version })));
  doc.title = "new";
  return doc;
}); // 范围结束时 UPDATE ... SET TITLE = ?, VERSION = VERSION + 1 WHERE ID = ? AND VERSION = ?；d.version 已是新值
```

`t.one` / `t.optional` / `t.list(表, 查询)` / `t.track(表, 行)` / `t.forget(行)`。行是普通对象或类的实例，字段都是表的列、带主键（和版本）；快照是保留原型的浅拷贝。在 Node 上范围跟着异步上下文走（AsyncLocalStorage），嵌套调用加入外层；浏览器里每次调用是独立的范围，读写都经由 `t`。

## 16. 与 Java 版的差异

概念一一对应，两端渲染出的 SQL 完全相同，由 `spec/render/` 的金样例保证。

| 方面 | Java | TypeScript |
| --- | --- | --- |
| 可空性 | 不跟踪 | LEFT JOIN 的一侧和 `@one` 关系的列按类型变为可空 |
| 表与列 | 继承 `Entity` 的接口，列是返回列接口（`ColText<String>` 等）的方法 | 类的字段，类型由 `@column` 的类型参数声明 |
| 关系 | 表接口的 default 方法 `one(...)`、`many(...)`，或 `@ManyToOne`、`@OneToMany` | `@one`、`@many` 字段 |
| 对象树 | `selectAs(VO.class)` 按属性类型自动映射嵌套 VO 与 `List<X>` | 对象投影里写 `list(...)` 和嵌套对象 |
| 日期、精确小数 | `LocalDate`、`BigDecimal` | `CalendarDate`、`CalendarDateTime`、`Decimal`（peerDependencies）；`Decimal` 不保留末尾的零 |
| 列的类型接口 | 表接口的方法返回 `ColText<T>` 等，`Metadata` 按存储类型检查 | 由字段类型推出（`string` 是 `ColText`），以另一种类型存的字段写 `Stored<J, D>`；`number` 不分整数和浮点数，所以是一个 `ColNumber` |
| 方言视图 | `Hana.ViewText` 等，按参数的静态类型选重载 | 同名的 `ViewText` 等，从各方言包导出 |
| 按编码存的枚举 | 实现 `CodeEnum<C>` | 字段直接声明成编码的字面量联合（如 `"A" \| "C"`），不需要转换 |
| 表数 | 最多 16 张，`row(...)` 最多 22 个参数 | 不限 |
| 执行 | 同步，JDBC | Promise，各库的 Node 驱动 |

## 17. 结构变更

与 Java 版第 16 节相同，声明写在装饰器的选项里：

```ts
@table("S_ITEM", {
  schema: "APP",
  comment: "items",
  indexes: [{ name: "IX_S_ITEM_CODE", columns: ["code"], unique: true }],
  foreignKeys: [{ name: "FK_S_ITEM_OWNER", columns: ["ownerId"], references: () => Owner }],
})
export class Item {
  @id @generated @column("ID", bigint) id!: bigint;
  @column("CODE", text, { length: 20, nullable: false }) code!: string;
  @column("PRICE", decimal, { precision: 13, scale: 3 }) price!: Decimal | null;
  @column("STATUS", text, { length: 10, nullable: false, default: "'open'" }) status!: string;
  @column("OWNER_ID", bigint) ownerId!: bigint | null;
}
```

- `@column` 的第三个参数：`length`、`precision` / `scale`、`nullable`、`default`、`lob`、`comment`、`previousName`；外部表写 `@table(name, { external: true })`。
- `await db.schema().check(Item)`、`await db.schema().plan(Item)`（`statements`、`toSql()`、`await plan.apply()`、`plan.allowDestructive()`）、`db.schema().createTable(Item)`、`await db.schema().read(schema, table)`。
- 主键列在前，其余列按字段名排序，生成的建表语句与 Java 完全相同。

**版本化迁移**与 Java 版相同：

```ts
const v1 = migration("2026.10.24.1", "create S_ITEM", (steps) =>
  steps
    .createTable(tableBuilder("APP", "S_ITEM").column("ID", columnType("BIGINT")).identity().column("CODE", columnType("TEXT", 20)).notNull().primaryKey("ID").build())
    .sql("ALTER TABLE APP.S_ITEM ...", HanaDialect) // 只在 HANA 上执行
    .execute(insertInto(Code).values(seedRows)),
);
const migrator = db.migrator([v1, v2]).historySchema("APP");
await migrator.migrateToLatest();
await migrator.verify();
```

`plan()`、`pending()`、`history()`、`baseline(version)`、`repair(version)`、`migrateTo(version)` 都是异步的；手写的 SQL 用 `?` 作占位符，PostgreSQL 上自动换成 `$n`。`historyTable("flow_migration")` 换历史表的名字（锁表跟着叫 `flow_migration_lock`），同 Java。

**从数据库生成列**：每个方言包带一个 bin（`query-pull-hana`、`query-pull-postgres`、`query-pull-mysql`），读 `query-pull.json`，在 `src/generated` 里生成只有列的基类，自己的表类继承它：

```json
{
  "connection": { "host": "db", "port": 5432, "database": "erp", "user": "report" },
  "passwordEnv": "ERP_DB_PASSWORD",
  "schema": "public",
  "outDir": "src/generated",
  "tables": [{ "name": "S_ITEM" }, { "name": "LIPS", "typeName": "LipsColumns", "columns": ["VBELN", "MATNR", "LFIMG"] }]
}
```

```ts
// npx query-pull-postgres（或 query-pull-postgres other.json --only=LIPS）生成 src/generated/SItemColumns.ts
@table("S_ITEM", { schema: "public" })
export class Item extends SItemColumns {
  @one(() => Owner, (i, o) => o.id.eq(i.ownerId)) owner!: One<Owner>;
}
```

`connection` 原样交给驱动（pg 的 PoolConfig、mysql2 的连接参数、@sap/hana-client 的 `serverNode` / `uid`），密码从 `passwordEnv` 指定的环境变量读，不写进配置文件。属性名、可选项和类型与 Java 的 `query:pull` 一致；SMALLINT 列生成为 `smallint`，DECIMAL、DATE、TIMESTAMP 列的字段是 `Decimal`、`CalendarDate`、`CalendarDateTime`，生成的文件从 `decimal.js`、`@internationalized/date` 导入它们的类型。也可以在代码里调用 `pullTables(db, schema, tables)` 或 `typescriptSource(tableDef, typeName)`。

## 18. 分区与分表

与 Java 版第 17 节相同。原生分区写在 `@table` 的 `options` 里，只在对应的方言上加到建表语句末尾：

```ts
@table("EVENT_LOG", { schema: "APP", options: [{ dialect: PostgresDialect, sql: 'PARTITION BY RANGE ("DAY")' }] })
export class EventLog {
  @id @column("ID", bigint) id!: bigint;
  @id @column("DAY", date) day!: CalendarDate;
}
```

同库物理分表在数据库选项里注册：

```ts
const db = new PostgresDatabase(pgDriver(pool), {
  shards: [shard(Event, monthly("day", "2025-01", "2026-12")), shard(Order, modulo("customer", 8))],
});
await db.list(from(Event).where((e) => e.day.range(parseDate("2026-01-15"), parseDate("2026-03-01"))).select((e) => e.id));
```

`monthly(key, "YYYY-MM", "YYYY-MM")`（键是日期或时间戳列，值是 `CalendarDate`、`CalendarDateTime` 或它们的文本）、`modulo(key, n)`（number 或 bigint）、`customShards(key, tables, router)`；
查询和 `update` / `deleteFrom` 有 `allShards()`、`onShards(...tables)`；迁移里用 `tableBuilder(...).option(dialect, sql)`。规则、选表和渲染出的 SQL 与 Java 完全相同（金样例 `shard-*`）。

## 19. SQLite 与浏览器

`@zerodep-dev/query-sqlite` 在 Node 里接 `node:sqlite`，在浏览器里接官方的 SQLite WASM。设计、实测的语义差异和取舍见 [0009](decisions/0009-sqlite-dialect.md)。

```ts
import { DatabaseSync } from "node:sqlite";
import { database, from, sqlite } from "@zerodep-dev/query-sqlite";

const raw = new DatabaseSync("app.db");
raw.exec("ATTACH DATABASE 'erp.db' AS ERP; PRAGMA foreign_keys = ON"); // 表声明里的 schema 是 ATTACH 进来的库名
const db = database(raw);                                               // 或 database(raw, { schemas: { ERP: "main" } })
```

浏览器里把本库和 sqlite-wasm 一起放进一个 Worker，用 `opfs-sahpool` 持久化（不需要 COOP/COEP 响应头）。页面与 Worker 之间的消息由使用方定：

```ts
// worker.ts（new Worker(url, { type: "module" })）
import sqlite3InitModule from "@sqlite.org/sqlite-wasm";
import { database, insertInto } from "@zerodep-dev/query-sqlite";

const sqlite3 = await sqlite3InitModule();
const pool = await sqlite3.installOpfsSAHPoolVfs({});
const db = database(new pool.OpfsSAHPoolDb("/app.db"), { schemas: { ERP: "main" } });
await db.transaction(async (tx) => {
  await tx.execute(insertInto(Note).values(row));   // 浏览器里事务内必须经由 tx
});
```

- **驱动**：两个驱动都是单连接，语句按到达顺序排队，事务期间别的调用等事务结束；事务用 `BEGIN IMMEDIATE`。它们不能中途取消语句，所以 `queryTimeoutMs` 不起作用。需要 ATTACH 别的库、打开外键检查时，在交给 `database(...)` 之前对连接执行。
- **类型**：小数列按 NUMERIC 亲和存储，比较和排序是数值的，但有效数字超过 15 位或要精确求和（金额）时不精确，金额建议存整数的最小单位；日期和时间戳存成 `YYYY-MM-DD`、`YYYY-MM-DD HH:MM:SS.fff` 文本，读出来仍是 `CalendarDate`、`CalendarDateTime`；整数超出安全范围时读成 `bigint`。
- **语义差异**：`LIKE` 不区分 ASCII 大小写（与 MySQL 默认相同），要区分时用方言视图 `sqlite(e).glob("A*")`；`currentTimestamp()` 是本地时间。其余函数与三库语义一致，写法见金样例 `spec/render/*.sqlite.sql`。
- **需要编译选项的函数**：`sqrt`、`power`、`ceil`、`floor`、`mod` 和方言包的 `sqlite(e).median()`、`percentileCont(p, e)`、`percentileDisc(p, e)` 照常渲染。每个数据库实例对每个函数第一次渲染时，经由选项 `warn`（缺省 `console.warn`）记一条警告；`node:sqlite`、官方 WASM 和 sqlite-jdbc 都带这些函数。
- **不支持的**：从 SQLite 包导入的 `grouping`、`forUpdate` 是 `never` 类型，写不出来；ROLLUP 在渲染时报错。
- **结构变更**：自增主键写成 `INTEGER PRIMARY KEY AUTOINCREMENT`；外键只能写在建表语句里；改列的类型、可空、默认值和给已有的表加外键都只报告（REBUILD：新建表、搬数据、改名）；没有表和列的注释。从库生成基类用 `query-pull-sqlite`。
- **浏览器里的核心包**：事务、追踪写回和多语句写入都经由事务视图 `tx` 执行，不依赖 AsyncLocalStorage。但浏览器里事务回调中直接用 `db` 的语句不在事务里：单连接驱动上它会一直等到事务结束，所以必须用 `tx`。`capture` 需要 AsyncLocalStorage，浏览器里报错；嵌套的 `tracking` 各自写回。迁移的校验和用 Web Crypto，浏览器里要在安全上下文（https 或 localhost）中。

## 20. PolarDB

`@zerodep-dev/query-polardb` 重新导出 `@zerodep-dev/query-mysql` 的全部内容（一次导入），另有闪回查询和库存提示，驱动同 MySQL 用 `mysql2`。与 Java 的第 19 节一一对应，金样例相同；设计与实测见 [0012](decisions/0012-polardb-dialect.md)。

```ts
import { CalendarDateTime } from "@internationalized/date";
import { database, from, fromAsOf, inventory, update } from "@zerodep-dev/query-polardb";

const db = database(mysql.createPool({ host, port, user, password, database: "app" }));
await db.list(fromAsOf(Stock, new CalendarDateTime(2026, 10, 24, 10, 0)).where((s) => s.qty.gt(0)).select((s) => s.id));
await db.transaction(async (tx) => {
  await tx.execute(update(Stock).with(() => inventory(1)).set((s) => ({ qty: s.qty.minus(1) })).where((s) => s.id.eq(1n)));
});
```

- `fromAsOf(表, 时刻)`：时刻是 `CalendarDateTime`，按数据库所在时区的本地时间理解，写成 `FROM 表 AS OF TIMESTAMP CAST(? AS DATETIME(6)) 别名`；条件同 Java。
- `inventory(n)`：只能写在 `update` / `deleteFrom` 的 `with(...)` 上，必须在事务里执行；正好改到 n 行时立即提交整个事务，否则报错并回滚整个事务。
- 写法与 MySQL 完全相同，MySQL 包的专属函数照常可用；没有纳入的能力与云产品的差异见 Java 的第 19 节。

## 21. 与前端配合：表格请求、typed JSON、树

与 vue-tsx-pro 的约定见 [0015](decisions/0015-typed-json-wire.md)（值怎样写成 JSON）和 [0016](decisions/0016-table-query-contract.md)（表格请求），Java 版见 api.md 第 20 节。

**表格请求**：前端 `useTableQuery` + `tableParams(query, columns)` 发出 `page`、`pageSize`、`sort=-amount,name`、已知时的 `total`、表头多选 `filter.列=值`、表头关键字 `search.列=关键字`，其余参数是查询栏的条件：

```ts
export default defineEventHandler(async (event) => {
  const params = getQuery(event);
  const q = from(Order).where(Object.assign(new OrderFilter(), params)).select((o) => ({ id: o.id, supplier: o.supplier, amount: o.amount }));
  return toWire(await db.table(q, parseTableRequest(params)));
});
```

- `parseTableRequest(params, { maxPageSize })` 接受 `URLSearchParams` 或 `getQuery` / `req.query` 那样的对象：没写时第 1 页、每页 10 条；条数上限默认 2000（同 Spring Data），超过时报错。写错的数字报 `RangeError`，Web 层据此回 400。
- `db.table(q, request)` 依次 `filterBy`、`searchBy`、`sortBy`、`page`；只认结果列。前端只翻页、换排序时带上上一次的总数，这时不再 COUNT。
- 查询栏的条件交给条件对象（第 6 节）：地址栏里的文字按列的类型读，区间写成 `Range<CalendarDate>` 或两个同名参数。

**typed JSON**：`toWire(value)`（`@zerodep-dev/wire`，核心包重新导出）把 bigint、Decimal、`CalendarDate`、`CalendarDateTime`、`Time`、`ZonedDateTime`、字节写成文字，根上附一张 `$types`；`fromWire(json)` 按它还原。h3、Express 直接返回 `toWire(...)` 的结果即可（`JSON.stringify` 写不了 bigint，decimal.js 的 `toJSON` 会用科学计数法，`CalendarDate` 会写成对象）。地址栏里的单个值用 `wireText(value)` / `parseWire(kind, text)`。

**树**：

```ts
// 懒加载：每行带上有没有子节点
from(Dept).where((d) => d.parentId.isNull()).select((d) => ({ id: d.id, name: d.name, hasChildren: bool(() => d.children.any()) }));
// 服务端搜索：命中的行加上全部祖先，组装成从根开始的树
const rows = await db.withAncestors(Dept, {
  key: (d) => d.id,
  parent: (d) => d.parentId,
  hits: (d) => d.name.contains(keyword),
  select: (d) => ({ id: d.id, parentId: d.parentId, name: d.name }),
});
const tree = buildTree(rows, { key: (d) => d.id, parent: (d) => d.parentId });   // 每个节点是 { ...行, children }
```

- `db.withAncestors` 在 PostgreSQL、MySQL（PolarDB）、SQLite 上是一条 `WITH RECURSIVE ... UNION ...`（`dialect.supportsRecursiveCte`）：命中的行共用的祖先只走一遍，父子成环也会停；HANA 没有递归 CTE，一层一层按键查只有键的两列，最后按键分批取 select 的列。行的顺序不保证。
- `buildTree(rows, { key, parent, withChildren? })`：同级保持 rows 的顺序，父键不在这批行里的当作根，键重复或成环时报错。数据不来自 SQL 时用 `withAncestors(rows, { key, parent, load })`。

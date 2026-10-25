# 0016 表格查询的前后端契约

- 状态：已采纳（2026-10-25），Java、TypeScript 与 vue-tsx-pro 三边实现
- 起因：对照 EasyQuery 找缺口时发现，对象树加载、条件对象我们不缺，缺的是"表格请求 → 查询"这一段：前端已经有多列排序、区间输入、表头筛选，但 `useTableQuery` 与后端的 `sortBy` / `page` 之间没有共同的契约，两边各缺一截。用户要求前后端深度配合，两边都可以改。
- 相关：值怎样写成文字见 [0015](0015-typed-json-wire.md)。

## 决定

### 1. 一套地址栏参数

```
GET /orders?page=2&pageSize=20&sort=-amount,name&total=368
           &filter.status=draft&filter.status=paid&search.supplier=启航
           &keyword=泵&created=2026-01-01&created=2026-01-31
```

| 参数 | 意思 | 前端 | Java | TypeScript |
| --- | --- | --- | --- | --- |
| `page`、`pageSize` | 页码从 1 开始；没写时 1、10；条数上限 2000（同 Spring Data 的 `max-page-size`），超过时报错而不是悄悄少给 | `useTableQuery` | `TableRequest.parse(params[, max])` | `parseTableRequest(params, { maxPageSize })` |
| `sort` | 逗号分隔的结果列名，减号是倒序（JSON:API）；也认 `amount desc, name` | `formatSorts(sorts)` | `Query.sortBy` | `query.sortBy` |
| `total` | 前端已知的总数：只翻页、换排序或条数时是上一次结果的 total，有它时不再执行 COUNT；筛选条件、表头筛选、params 变了，或"查询"、refresh 时不带 | `TableQuery.total` | `db.page(q, page, size, total)` | 同左 |
| `filter.列` | 表头的多选筛选，值在其中（IN），可重复 | `columnFilters` | `Query.filterBy(Map<String, List<String>>)` | `query.filterBy(record)` |
| `search.列` | 表头的关键字筛选，文字包含（LIKE，`%`、`_` 按普通字符） | 列定义 `filter: { options: 'text' }` | `Query.searchBy(Map<String, String>)` | `query.searchBy(record)` |
| 其余 | 查询栏的条件，按字段名平铺；数组、区间写成重复的参数，空的一端是空字符串 | `tableParams` 用 `wireText` 写 | Spring 绑定到条件对象 | 条件对象（文字按列类型读） |

- 前端：`tableParams(query, columns)` 写出这套参数；`columns` 用来分清哪些表头筛选是关键字。`QueryTable` 把 DataTable 的表头筛选（`manualFilter`）绑到 `query.columnFilters`，`multiSort` 时绑 `query.sorts`。
- 后端：`db.table(查询, 请求)` 依次 `filterBy`、`searchBy`、`sortBy`、`page`；结果经 `Page.toWire()`（Java）或 `toWire(page)`（TS）发回，`total` 与 `totalPages` 是 JSON 数字。
- **只认结果列**：`filterBy`、`searchBy`、`sortBy` 的键都是结果列名，相当于白名单，请求里的列名碰不到结果以外的列。未知列名、排除掉的列、嵌套对象、子列表报错；`searchBy` 只能用在文字列上。
- **WHERE 还是 HAVING**：构建查询时还不知道方言，判断不了一列是不是聚合，所以按结果列的条件存在 SELECT 规格里，渲染时用探查写入器写一遍那一列：逐行计算的进 WHERE，聚合的进 HAVING，窗口函数的报错（要先放进 CTE）。聚合由渲染时报到 `Aggregation`（Java）/ `aggregation.ts`（TS）的方式认出：`Aggregate` 节点自己报到，`MEDIAN`、`STRING_AGG`、`CORR`、`PERCENTILE_*`、`MODE`、`ARRAY_AGG` 这类函数在声明处标 `FunctionDef.aggregating()`；窗口函数里、子查询里的聚合不算外层的。COUNT 的优化（第 3 节）用同一个判断。
- 不做 NULLS FIRST / LAST：等哪个库的默认顺序真成了问题再加。

### 2. 区间与文字的值

- 条件对象加区间比较：Java `@Where(op = RANGE)`，TS `@where("range")`。值是 `Range` / `{ start, end }`（vue-tsx-pro 的 DateRangePicker）或两项的数组、集合（地址栏里重复的参数），两端都含，空的一端不加条件，两端都空时不加条件。
- **日期落在时间戳列上**：起端是那天的零点，止端写成"早于后一天的零点"。日期区间在前端是两端都含的整天，直接写 `<= 止端` 会丢掉止端那天零点以后的数据。参照 EasyQuery 的 `RANGE_CLOSED_OPEN`，但我们不让使用方选开闭：前端的日期区间就是闭区间，转换只发生在"日期对时间戳"这一种情况。
- **文字按列的类型读**：条件对象的字段值、表头筛选的值是文字时，按列的类型读（Java 用 `Wire.parse` 按列的 Java 类型，TS 用 `parseColumnText` 按内置类型）。这样 Nuxt 里直接把地址栏参数交给条件对象也能比较数字、bigint、日期列；空白的文字不加条件（IN 列表里也去掉）。TS 的转换器列字段类型无从得知，文字原样交给绑定。

### 3. 分页与数行数

- Java 的 `page` 仍是先查这一页再数行数，TS 两条并行。没有学 EasyQuery 的"先数，总数为 0 或页码超出时不查这一页"：空跑时总数是 0，那样 `capture` 就看不到这一页的查询了，而测试正靠它断言 SQL；页码超出时前端本来就会退到最后一页。
- 已知总数由前端带来（上面的 `total`），省掉的是翻页时的 COUNT。

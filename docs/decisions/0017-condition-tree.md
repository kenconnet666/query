# 0017 条件树：前端条件构造器到 SQL

- 状态：已采纳（2026-10-26），Java、TypeScript 与 vue-tsx-pro 三边实现
- 起因：vue-tsx-pro 新做了条件构造器（`ConditionBuilder`）与 FilterBar 的高级筛选，流程连线的条件也用同一种写法。
  表头筛选（`filterBy`、`searchBy`）只能表达"每列各自满足"，"金额介于、品类是其中之一、或者加急"这类组合要一棵条件树。
- 相关：表格请求的契约见 [0016](0016-table-query-contract.md)，值的文字格式见 [0015](0015-typed-json-wire.md)。

## 决定

### 1. 一种 JSON

```json
{ "all": [ { "field": "amount", "op": "gte", "value": 10000 },
           { "any": [ { "field": "category", "op": "in", "value": ["steel", "aluminum"] },
                      { "not": { "field": "urgent", "op": "eq", "value": false } } ] } ] }
```

- 节点是比较 `{ field, op, value }`，或 `{ all }`、`{ any }`、`{ not }`。与 vue-tsx-pro 的 `Condition` 同形；流程定义里条件的
  `var` 在流程引擎的模型包定稿时对齐成 `field`。
- 比较方式 14 种：`eq`、`ne`、`gt`、`gte`、`lt`、`lte`、`between`（`[下限, 上限]`）、`in`、`notIn`（数组）、`contains`、
  `notContains`、`startsWith`（只能用在文字列上）、`exists`、`empty`（没有值）。
- 地址栏里是 `condition` 参数（JSON 文字，`TableRequest.parse` / `parseTableRequest` 读），请求体里原样是对象。
  Java 不依赖 JSON 库：wire 模块加了 `Json.read`（RFC 8259，整数是 Long、其余数字是 BigDecimal，嵌套上限 256 层）。

### 2. 只认结果列，值按列的类型读

- `Query.conditionBy(tree)` 与 `filterBy` 同一套规则：字段是结果列名（白名单），排除掉的列、嵌套对象、子列表报错；
  值是文字时按列的类型读（`Wire.parse` / `parseColumnText`），数字、布尔先写成文字再读，所以 Long、BigDecimal、bigint 列都能接。
- 最外层的 `all` 拆成几个条件，各自在渲染时判断进 WHERE 还是 HAVING（同 0016 的按结果列条件）；一棵子树（同一个 `any`、`not`）里
  既有逐行计算的列又有聚合的列时报错：WHERE、HAVING 都放不下。窗口函数的列不能筛选。
- 读条件树时校验形状与比较方式，消息带出错的位置（`condition.all[1].op: unknown operator "like"`），嵌套超过 32 层报错；
  Web 层据此回 400。

### 3. 与前端求值一致

前端 `matchesCondition` 与后端 SQL 用同一套规则，同一份条件在两边筛出同样的行：

| 规则 | 前端 | SQL |
| --- | --- | --- |
| 不等于、不在其中、不包含把空值算作满足 | `!same(...)` | `(x <> ?) OR (x IS NULL)` |
| 有值 / 为空：文字列把空字符串算作空 | `isBlank` | `x IS NOT NULL AND x <> ''` / `(x IS NULL) OR (x = '')` |
| 介于含两端，空的一端不限；时间戳列配日期时止端是后一天零点之前 | `compare` | `>= ? AND <= ?`（同条件对象的区间） |
| 没填完整的比较（值为空、区间两端都空、空数组）不加条件 | 当作成立 | 不写 |
| 空的组不加条件 | 当作成立 | 不写 |

文字比较的大小写不在规则里：前端不分大小写，SQL 按数据库的排序规则（MySQL 默认不分，PostgreSQL 区分）。
要在 PostgreSQL 上不分大小写，列用不分大小写的排序规则或 `citext`。

## 没有做的

- 字段名到列的映射（前端字段名与结果列名不同）：结果列名就是对外的名字，需要时在投影里起别名。
- 列与列比较、函数（`year(created) = 2026`）：条件树只比较列与值；复杂的条件写在查询里。
- 文字比较不分大小写的统一处理：`lower(x) LIKE lower(?)` 用不上索引，留给排序规则。

# 0005 API 简化审查

- 状态：已采纳（2026-10-21）
- 范围：1.0.0 发布前的一轮公开 API 审查。1.0.0 还没有发布，改动直接进入 1.0.0，不保留旧写法的过渡期

## 方法

逐个列出两端的公开类型和方法，对照用法说明、测试和使用方 SQL 的审计（0003 的 M1），找五类问题：

1. 最常见的写法里必须写的样板；
2. 同一件事有两种写法；
3. 名字容易读错；
4. 两端写法不一致；
5. 只覆盖了一部分情况的简写，用户顺手一用就编译失败。

## 决定

### 1. 只有一个分组键、排序键或命名列时不用包装（Java）

以前 `groupBy(l -> keys(a))`、`orderBy(l -> orders(a.asc()))`、`selectAs(X.class, l -> keys(a.as(...)))`，一个键也要包一层；升序还必须写 `.asc()`。审计里绝大多数分组和排序只有一个键。现在：

```java
.groupBy(l -> l.col(Lips::getMaterial))
.orderBy(l -> l.col(Lips::getMaterial))                           // 裸表达式按升序
.orderBy(l -> orders(l.col(Lips::getMaterial), l.col(Lips::getDelivery).desc()))
.selectAs(MaterialScope.class, m -> upper(m.col(Mara::getPartNumber)).as(MaterialScope::partNumber))
```

- **为什么不用重载**：Java 对隐式类型的 lambda，无法按返回类型区分重载。`groupBy(l -> a)` 和 `groupBy(l -> keys(a, b))` 若分别对应两个重载，javac 会报歧义。
- **做法**：新增两个 sealed 标记接口。
  - `Keys`：`Expr` 本身，或 `keys(...)` 返回的 `KeyList`；
  - `OrderItem`：`Expr`（升序）、`Order`，或 `orders(...)` 返回的 `OrderList`。

  接口没有任何方法，不会在 IDE 的补全里多出东西。`KeyList` 和 `OrderList` 仍然是 `List`，`groupingSets(keys(a, b), keys())` 照旧可用。
- **同样接受裸表达式的地方**：窗口的 `orderBy`、`stringAgg` 的排序参数。
- **两端对照**：TS 本来就是这样，现在两端写法一致。

### 2. EXISTS 直接接收作用域，去掉 `selectOne()`

以前写 `exists(from(Vbkd.class).where(...).selectOne())`。`selectOne` 读起来像"只取一行"，实际意思是 `SELECT 1`。现在 `exists` / `notExists` 直接接收作用域，由库渲染 `SELECT 1`。`exists(query)` 仍然可用，`selectOne()` 从两端删除。

### 3. 删除 `whereIf`

`whereIf(test, cond)` 与 `where(... onlyIf(test, () -> cond))` 完全重复，而且 Java 端没有测试覆盖。条件统一写在 `where` 里：可选的值用 `eqIfPresent` 等，按开关加条件用 `onlyIf`，按值决定条件用 `ifPresent`。两端都删除。

### 4. 引用另一张表的列的简写覆盖所有比较（Java）

`a.eq(l, Lips::getMaterial)` 这种简写以前只有 `eq`、`ne` 和 `coalesce` 支持，`gt`、`ge`、`lt`、`le` 不支持，有效期条件（`有效期起 <= 日期`）这类写法会意外编译失败。现在六种比较都支持。没有反过来删掉这个简写：连接条件是最常见的写法，删掉会让每个连接都变长。

### 5. 文档

- 两份用法说明开头加了"速查"表，用 SQL 构造对照写法。
- `val` 与 `lit` 的区别写成一句规则：默认用 `val`；`lit` 只用于必须出现在 SQL 文本里的代码常量。
- 泛型模板的示例改用 `java.util.function.Function`，不再让使用方接触生成的 `Fn.F1`。

## 考虑过但不改

- **`l.col(Lips::getMaterial)` 太长**：这是不用代码生成的代价（0001、0002），Java 拿不到字段的静态引用。`col` 已经是最短的有意义的名字。
- **引入 Spring 时自动接入事务**：可以用 ServiceLoader 让 `database(dataSource)` 在有 `query-spring` 时自动改用 `SpringConnections`。但隐式行为更难排查，而且只读的大屏查询不依赖事务里的未提交写入，所以保持显式。
- **合并 `val` 和 `lit`**：两者在条件里的行为确实不同（参数还是字面量），合并会让常量在 WHERE 里的行为不可控。只在文档里讲清楚。
- **`q.toSql(dialect)` 与 `db.render(q)`**：前者只按方言渲染，用于测试；后者带上数据库实例的过滤器和改写，是将要执行的 SQL。用途不同，都保留。
- **LIKE 自动转义**：见 0004，等有用户输入的模糊搜索时再设计。

## 验证

- 测试里的示例都换成了新写法。`spec/render/` 的 88 个金样例一个没变：新写法渲染出的 SQL 与旧写法逐字相同。
- 新增的渲染测试确认三件事：
  - 包装与不包装的写法生成相同的 SQL；
  - 裸表达式在 ORDER BY 和窗口里都按升序；
  - `exists` 接收作用域，以及四种大小比较的简写。
- 两端的单元测试、编译期检查和三库实库测试全部通过。

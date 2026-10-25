# 0018 一对多 first(...) 在 PostgreSQL、MySQL 上用 LATERAL

- 状态：已采纳（2026-10-26），Java、TypeScript 两端
- 起因：`m.deliveries().first(l -> l.createdOn().desc())` 原来在所有库上都写成 ROW_NUMBER 排名的派生表再 LEFT JOIN 回来。
  派生表要给整张子表（只受全局过滤器和关系的筛选条件约束）排名，父查询分页只取 20 行时也一样，子表越大越慢。
  按"PostgreSQL、MySQL 两者都支持就用性能更好的写法"的约定（0016 的待办），改用 LATERAL。

## 决定

- 方言加能力 `supportsLateral()`（TS 是 getter）：PostgreSQL、MySQL 为真；HANA、SQLite 没有 LATERAL；PolarDB 继承 MySQL
  但改回假：生产环境 PolarDB MySQL 8.0 的小版本未知，可能早于引入 LATERAL 的 8.0.14，按"最旧基线兼容"处理。
- 构建时还不知道方言，`first(...)` 两种写法都备好（Java `table.FirstRow`、TS `FirstRowSource`），渲染时按方言选：

  ```sql
  -- PostgreSQL、MySQL
  LEFT JOIN LATERAL (SELECT 子表各列 FROM 子表 WHERE 过滤器 AND 键 = 父行的列 ORDER BY 排序 LIMIT 1) x ON TRUE
  -- HANA、SQLite、PolarDB
  LEFT JOIN (SELECT 子表各列, ROW_NUMBER() OVER (PARTITION BY 键 ORDER BY 排序) AS rn__ FROM 子表 WHERE 过滤器) x
    ON 键相等 AND x.rn__ = 1
  ```

  两种写法的列都是表接口的属性名，投影、之后的导航（多对多的 `through`、`first(...).vendor()`）不分写法；
  没有匹配行时各列都是 NULL。
- 索引：LATERAL 每个父行按（键, 排序列）取一行，给子表建这样的索引才快；没有索引时每个父行扫一遍子表，父行多时不如排名。
  文档里写明。
- `many.orderBy(...).limit(n).list(...)`（每个父行取前 n 个子行）仍是按键批量的一条查询加 ROW_NUMBER：它本来就只查本批父行的子行，
  换成 LATERAL 要把父键写成 VALUES 表，收益小、写法各库不同，暂不改。

## 验证

- 金样例 `one-to-many` 的 PostgreSQL、MySQL 文件改成 LATERAL，PolarDB 有了自己的文件（与原来的 MySQL 写法相同）；两端逐字节一致。
- 实库：五个库的关系与对象树套件照常通过（PostgreSQL、MySQL 走 LATERAL，其余走排名）。

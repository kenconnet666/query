# 0011 阿里云 PolarDB MySQL 8.0 的兼容性调研

- 状态：调研结论（2026-10-24），**暂不改代码**；用户决定以后再看
- 范围：MySQL 方言（Java `query-mysql`、TS `@zerodep-dev/query-mysql`）在 PolarDB MySQL 版上能否使用
- 证据：本文的结论都来自阿里云文档和本仓库代码的对照，**还没有在真实的 PolarDB 集群上执行过**

## 背景

用户问：PolarDB MySQL 8.0 与标准 MySQL 8 有什么不同，本库能不能用，它有哪些新特性。本库的 MySQL 方言按社区 MySQL 8.4 实现和验证（`infra/mysql`，`mysql:8.4.11`）。

## PolarDB MySQL 8.0 与社区 MySQL 的差异

- **兼容的社区版本更旧**：
  - 8.0.1 完全兼容社区 MySQL 8.0.13 及更早的版本；
  - 8.0.2 完全兼容社区 8.0.18 及更早的版本。
  - 文档里只有 5.6、5.7、8.0 三个大版本，没有对应 8.4 的内核。
- **新语法逐个补**：8.0.2 的小版本会单独补上社区后来才有的语法：
  - `VALUES ROW(...)`：8.0.2.2.27（2024-12）补上；
  - `INSERT ... AS 别名 ON DUPLICATE KEY UPDATE` 的行别名：8.0.2.2.31（2025-09）补上；
  - 调研时最新的小版本是 8.0.2.2.38.1（2026-10-13）。
- **不能原地升级**：小版本之间、跨大版本都不能原地升级，只能新建集群，再用 DTS 迁移。
- **限制**：不支持 Serializable 隔离级别（本库不用）；表名长度有上限。
- **集群地址带读写分离代理**：事务里的语句和加锁读走主节点。本库的事务固定用一个连接，所以事务语义不受影响。

## 本库 MySQL 方言里超出 8.0.18 基线的写法

| 本库生成的写法 | 社区 MySQL 起始版本 | PolarDB 上的情况 | 出现在哪 |
| --- | --- | --- | --- |
| `INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE c = new.c` | 8.0.19 | 8.0.2.2.31 起支持；更早的 8.0.2 和 8.0.1 会报语法错误 | `onConflict().update(...)`（`MysqlDialect` 的插入或更新） |
| `INTERSECT` / `EXCEPT`，方言包的 `intersectAll` / `exceptAll` | 8.0.31 | 发布说明里没有提到；按 8.0.18 的基线推断不支持，**待实测** | 核心的 `intersect` / `except`，金样例 `set-operations`、`mysql-set-all` |
| `CAST(x AS DOUBLE)` | 8.0.17 | 8.0.2 可以，8.0.1 不行 | 统计函数等，金样例里 5 处 |

其余用到的能力在 8.0.1 上就已经有了：窗口函数、CTE 与递归 CTE、`SKIP LOCKED` / `NOWAIT` / `FOR SHARE`、`WITH ROLLUP` 与 `GROUPING()`、JSON、`MATCH ... AGAINST` 全文检索、优化器提示。驱动方面没有问题：Java 用 Connector/J 8 及以上，TS 用 mysql2。

**结论**：用 8.0.2 的最新小版本（不低于 8.0.2.2.31）时，只剩 `intersect` / `except` 一处需要避开或改写；用 8.0.1 或更早的 8.0.2 时，三处都要处理。

## 对分析查询有用的 PolarDB 特性

- **列存索引（IMCI）**：
  - 只放在只读的列存节点上，行列混存，配合向量化执行，由优化器按代价选择走行存还是列存；
  - 开启方法是给表的 `COMMENT` 加 `COLUMNAR=1`，查询不用改写；`EXPLAIN` 里出现 `IMCI Execution Plan` 就说明用上了；
  - 对报表、大屏这类聚合查询最有价值，因为本库生成的 SQL 原样就能受益。
- **只在 8.0.2 上有的执行能力**：弹性并行查询、Hash Join（含并行与 Bloom Filter）、子查询解关联、Partial Result Cache。可以通过方言包已有的提示能力加 hint，但 hint 的具体写法要以官方文档为准，这次没有核实。
- **增量刷新的物化视图**：8.0.2.2.35 起。
- **分区增强**：INTERVAL RANGE 分区、二级分区、全局二级索引（GSI）、冷数据归档到 OSS。
- **新特性**：向量索引、原生 BSON、SQL 里调用大模型、Iceberg 外表、库表分支（FORK）。这些是 PolarDB 专属，本库的方言里没有对应写法，需要时用原生 SQL 片段。

## 以后要做时的选项（未决定）

1. **先实测**：把 `QUERY_MYSQL_URL` 指向一个 PolarDB 测试集群，跑现有的 MySQL 实库场景（Java `MysqlLiveTest`、TS `mysql.live.test.ts`），确认上表的推断，特别是 `INTERSECT` / `EXCEPT`。
2. **兼容模式**：在 MySQL 方言里加一个面向旧基线的选项，例如：
   - 插入或更新改用 `VALUES(列)`，社区 8.0.20 起已弃用但仍可用；
   - `intersect` / `except` 改写成 `EXISTS` / `NOT EXISTS`，注意去重语义；
   - `CAST AS DOUBLE` 改成 `CAST AS DECIMAL` 或 `+ 0.0`。
3. **独立的 PolarDB 方言包**：只在需要 IMCI 提示、物化视图等专属能力时考虑。按"方言不抹平"的原则，这些不进 MySQL 方言。

## 来源

- [Engine versions 5.6, 5.7, and 8.0](https://help.aliyun.com/en/polardb/polardb-for-mysql/engine-versions)
- [8.0.1 与 8.0.2 的对比](https://help.aliyun.com/zh/polardb/polardb-for-mysql/comparison-of-mysql-8-0-1-and-8-0-2)
- [PolarDB MySQL 8.0.2 版本发布日志](https://help.aliyun.com/zh/polardb/polardb-for-mysql/polardb-mysql-8-0-2-release-notes)
- [Features in PolarDB for MySQL 5.6, 5.7, and 8.0](https://help.aliyun.com/en/polardb/polardb-for-mysql/version-comparison)
- [列存索引 IMCI](https://help.aliyun.com/zh/polardb/polardb-for-mysql/user-guide/imcis/)
- [通过 IMCI 加速分析查询](https://www.alibabacloud.com/help/zh/polardb/polardb-for-mysql/user-guide/accelerate-analytical-queries-through-imci)
- [PolarDB MySQL 使用限制](https://help.aliyun.com/zh/polardb/polardb-for-mysql/limits)
- [Migration between PolarDB for MySQL clusters（DTS）](https://help.aliyun.com/en/dts/user-guide/migrate-data-between-polardb-for-mysql-clusters-1)
- [MySQL 8.0 Reference: INSERT ... ON DUPLICATE KEY UPDATE](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/insert-on-duplicate.html)
- [MySQL 8.0 Reference: Set Operations](https://dev.mysql.com/doc/refman/8.0/en/set-operations.html)

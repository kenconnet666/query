# 0012 PolarDB 方言

- 状态：已采纳（2026-10-24），两端实现并在本机实测；CI 增加 PolarDB 实库的两个 job
- 起因：流程引擎（flow 仓库）第一版只用 MySQL 8，生产环境是阿里云 PolarDB MySQL 8.0。用户要求按 PolarDB 社区版的实测完成一个 PolarDB 方言
- 修订：[0011](0011-polardb-mysql.md) 的"以后要做时的选项"由本文落实

## 实测环境

- 阿里云 PolarDB for MySQL 是闭源的云产品，拿不到镜像。开源、能在本机跑的最接近的是 PolarDB-X 的标准版引擎 `polardbx/polardbx-engine:v2.5.0_8.4.21`：
  - 2026-09 发布，内核是 MySQL 8.0.42（`SELECT VERSION()` 返回 `8.0.42-X-Cluster-8.4.21-20260830`）；
  - 单节点的 X-Paxos 集群（`cluster_info` 只写自己），启动几秒后选自己为 Leader 才能写。
- 镜像只带裸的 `mysqld`，没有初始化脚本。`infra/polardb` 自己补上：
  - 首次启动时初始化数据目录，等节点成为 Leader 后再建库和账号；
  - 表名大小写默认按不区分（`lower_case_table_names=1`）初始化，云上大多这样建，`.env` 里可以改；
  - 打开 undo 保留（`innodb_undo_retention=1800`），并把时刻到快照号的记录间隔调成 1 秒，闪回查询才能读到最近的数据。
- 本机实例端口 53307，与 MySQL 的 53306 并存。

## 结论

1. **MySQL 方言原样可用**：MySQL 方言的全部实库场景（Java 87、TS 86 项）在社区版引擎上原样通过，渲染出的 SQL 与 MySQL 逐字相同，表名不区分大小写也没有影响。所以 PolarDB 方言**继承** MySQL 方言：
   - Java：`PolardbDialect extends MysqlDialect`、`PolardbDatabase extends MysqlDatabase`、门面 `Polardb extends Mysql`，一次静态导入拿到核心、MySQL 和 PolarDB 的全部写法；
   - TS：`@zerodep-dev/query-polardb` 重新导出 `@zerodep-dev/query-mysql`；
   - MySQL 专属函数按 `instanceof` 判断方言，在 PolarDB 上照常可用。
2. **只纳入社区版实测过、并且和云产品写法一致的专属能力**：

   | 能力 | 写法 | 社区版实测 | 云产品 | 结论 |
   | --- | --- | --- | --- | --- |
   | 闪回查询 | `FROM 表 AS OF TIMESTAMP CAST(? AS DATETIME(6)) 别名` | 可用；别名必须跟在 AS OF 之后；时刻必须是时间类型，直接绑参数报 7502，所以包 CAST | 同写法，要先打开 `loose_innodb_backquery_enable`，8.0.2.2.21 起 | 纳入：`fromAsOf(表, 时刻)` |
   | 热点行的库存提示 | `UPDATE /*+ COMMIT_ON_SUCCESS ROLLBACK_ON_FAIL TARGET_AFFECT_ROW(n) */ ...` | 可用；必须在事务里，自动提交时报错；改到正好 n 行时立即提交整个事务，否则报 8114 并回滚整个事务 | 同写法 | 纳入：`inventory(n)`，写在 `update` / `deleteFrom` 的 `with(...)` 上 |
   | 序列 | `CREATE SEQUENCE`、`NEXTVAL(s)`、`s.nextval` | 可用（`NEXT VALUE FOR` 不行） | 文档里没有，只有 PolarDB-X 有 | 不纳入，免得以为生产上也能用 |
   | 取回改动的行 | `CALL dbms_trans.returning('列', '语句')` | 可用；没有原生的 `RETURNING` | 8.0 不支持 | 不纳入 |
   | 闪回到 SCN / GCN | `AS OF SCN` / `AS OF GCN` | 语法可用 | 不支持 | 不纳入 |
   | 并行查询、列存索引的提示，`FOR UPDATE WAIT n` | `/*+ PARALLEL(n) */` 等 | 社区版没有 | 云上独有 | 社区版上测不了，不纳入；需要时用原生 SQL 片段 |
   | Outline、并发控制、回收站 | `CALL dbms_outln...` 等 | 有 | 有，个别参数不同 | 属于运维，不进查询库 |

3. **写语句可以带提示**（核心，两端）：`update(...)` / `deleteFrom(...)` 加 `with(t -> 提示)`，提示写在 UPDATE、DELETE 关键字后面，由方言合并（MySQL 合进一个 `/*+ */`）；只收写在关键字后面的提示，别的方言的提示渲染时报错。MySQL 自己的 `index` / `noIndex` 在写语句上也能用。插入暂不支持：插入或更新由方言整句写出，需要时再加。
4. **金样例沿用 MySQL**：`RenderSpec.fallbackDialect()`（TS 的 `fallbacks`）让 PolarDB 没有自己文件的用例直接和 `*.mysql.sql` 比对，重写金样例时与 MySQL 相同就不写自己的文件。现在只有 `polardb-flashback`、`polardb-inventory` 两个文件。
5. **实库场景继承 MySQL**：`PolardbLiveTest extends MysqlLiveTest`（query-mysql 产出 test-jar），TS 把 MySQL 的目标与专属场景拆进 `live/mysql-scenarios.ts` 复用。PolarDB 上跑 MySQL 的全部场景，再加闪回查询与库存提示的场景。

## 与云产品的差异（用的时候要注意）

- **语法基线**：云上 PolarDB 8.0.1 兼容社区 8.0.13，8.0.2 兼容 8.0.18；社区版引擎是 8.0.42。8.0.19 之后的写法要看小版本，详见 0011：
  - 插入或更新的行别名：8.0.2.2.31 起；
  - `INTERSECT` / `EXCEPT`：8.0.31 起；
  - `CAST AS DOUBLE`：8.0.17 起。

  用户的小版本未知；流程引擎不用 `INTERSECT` / `EXCEPT`，插入或更新只在需要时用。需要兼容旧小版本时再加兼容选项。
- **默认隔离级别**：云上是读已提交，社区版和社区 MySQL 是可重复读，并且云上不支持 SERIALIZABLE。建在本库上的库不要依赖隔离级别：推进前先锁行，关键读用加锁读。
- **区分云还是社区版**：云上有变量 `polardb_version`，社区版有 `polardbx_engine_version`。
- 隐式主键：云上默认开，社区版默认关；本库不依赖它。

## 顺带发现（流程引擎相关）

- InnoDB 的 `FOR UPDATE SKIP LOCKED` 会锁住扫描到的每一行，不只是返回的行：没有合适的索引时，第一个工作进程用 `LIMIT 2` 也会把整张表锁住，第二个进程什么都领不到；PostgreSQL 只锁返回的行。所以引擎领取作业改成"先普通读出候选，再逐个用带条件的 UPDATE 抢占"，五个库写法相同（实库场景"两个工作进程同时领取作业时互不重复"）。
- 本机 WSL 空闲时会关掉虚拟机，里面的数据库跟着被正常关机，测试会卡住或报连接错误。跑实库测试期间要有一个 `wsl` 进程挂着（例如后台运行 `wsl -d Debian -- sleep 14400`）。

## 验证证据（本机，2026-10-24）

- 金样例：Java PolarDB 88 项、TS 全部 427 项一致。
- 实库：
  - PolarDB：Java 97、TS 96（含 MySQL 的全部场景与 6 个流程引擎场景）；
  - MySQL 8.4：Java 93、TS 92；
  - PostgreSQL：Java 100、TS 97；
  - HANA：Java 93、TS 93；
  - SQLite：Java 83、TS 80 / 77（跳过的都是原有的几项）。
- Java 全量构建（`mvnw verify`，不连库）与 TS 单元测试 616 项通过。

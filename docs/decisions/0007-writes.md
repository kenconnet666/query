# 0007 写入：INSERT、UPDATE、DELETE 与配套能力

- 状态：已采纳（2026-10-24），在 `feat/v2` 上分四步实现，两端、三方言同步
- 目标版本：2.0
- 修订对象：0001 第 4 条"写入以后再评估"

## 背景

阶段 B 调研流程引擎（Flowable、Camunda 7、Activiti 7、jBPM 7、Warm-Flow、SnakerFlow）时发现，自研流程引擎的写操作离不开几种固定模式：

- 乐观锁更新：`UPDATE ... SET REV_ = REV_ + 1 WHERE ID_ = ? AND REV_ = ?`，影响 0 行就是并发冲突；
- 认领任务：`SELECT ... FOR UPDATE SKIP LOCKED`，各库写法不同；
- 批量插入、按编号删除、逻辑删除（更新 `del_flag`）、插入或更新。

这些交给 MyBatis 或手写 JDBC 时，三库的写法差异、全局过滤器（租户）和逻辑删除条件都要在调用点重复。用户在 2026-10-24 决定参考 EasyQuery 补齐写入，流程引擎本身先不做，并确认了三个取舍：

1. 插入时行的全部属性都写，null 写成 NULL（不按非空列裁剪）；
2. 逻辑删除和审计字段自动填充在这一轮一起做；
3. 只拦没有 WHERE 的 UPDATE、DELETE（`.all()` 放行），不默认禁止物理删除。

## 决定

1. **语句是不可变的值**，与查询一样：`insertInto(T)`、`update(T)`、`deleteFrom(T)` 起头，链式方法返回副本，`toSql(dialect)` / `db.render(write)` 看 SQL，`db.execute(write)` 执行并返回影响的行数。起头的名字取 jOOQ 的写法，因为 TS 里 `delete` 不能作为函数名，两端保持一致。
2. **声明只加四个注解**：`@Id`（可多列）、`@Version`（整数）、`@Generated`（数据库生成的主键）、`@LogicDelete(active, deleted)`；TS 是同名的小写装饰器。版本列、生成列、逻辑删除列每表至多一列，启动时校验。
3. **行按属性名对应列**：Java 的 record 或 POJO、TS 的普通对象；找不到列就报错，各行的字段必须相同。从查询插入时按结果列名对应。
4. **守卫**：没有 WHERE 条件的 UPDATE、DELETE 抛异常，`.all()` 才放行；`skipEmpty()` 省略的条件不算。全局过滤器和逻辑删除条件照常加进 WHERE，但不算作"有条件"，`unfiltered()` 关闭两者。`.expect(n)` 在影响的行数不符时抛 `UnexpectedRowCountException` / `UnexpectedRowCountError`。
5. **按主键写**：`byId(rows)` 按 `@Id` 定位，有 `@Version` 时 SET 版本加一、WHERE 带旧版本，每行期望 1 行，所以乐观锁冲突直接是异常。`skipEmpty().byId(row)` 只 SET 非空属性，代替"部分更新"的专门 API。
6. **生成主键**：`db.insertKeys(insert)` 按行的顺序返回 `@Generated` 列。各库取法由方言钩子决定：PostgreSQL 由驱动按列名取或 `RETURNING`，MySQL 用生成键或 `insertId`，HANA 在同一连接上查 `CURRENT_IDENTITY_VALUE()`。
7. **插入或更新**由方言钩子写出，核心只给出表、列、值、键和要更新的列：HANA `MERGE INTO ... USING (SELECT ? AS ... FROM DUMMY)`，PostgreSQL `ON CONFLICT ... DO UPDATE SET c = EXCLUDED.c`，MySQL 8.0.19 起的行别名 `AS new ON DUPLICATE KEY UPDATE c = new.c`；"什么也不做"在 MySQL 写成 `k = k`。
8. **加锁读**是查询上的子句 `forUpdate(Lock)`，等待、`NOWAIT`、`SKIP LOCKED` 三种；HANA 的 `SKIP LOCKED` 写作 `IGNORE LOCKED`。
9. **事务**：`db.transaction(work)` 在不用 Spring 时提供一个连接上的事务（Java 绑定到线程，TS 用 AsyncLocalStorage），嵌套调用加入外层。接入 Spring 时它报错，事务交给 `@Transactional`，避免两套事务管理。多行写入和多条 `byId` 自动放进一个事务；Java 多行用 JDBC 批量（每批 500 行）。
10. **逻辑删除并入表的过滤条件**：与全局过滤器在同一处生成，查询、连接、关系、子查询、UPDATE、DELETE 都带"未删除"条件；`deleteFrom` 改写成 SET 删除值的 UPDATE，`.physically()` 才真正删除；插入时补上未删除的值。时间戳列表示"记录删除时间"：未删除为 `IS NULL`，删除写 `CURRENT_TIMESTAMP`。
11. **审计填充配在数据库实例上**（Java `Config.onInsert/onUpdate`，TS `fills`），按父类型匹配，与全局过滤器同一种写法。插入填 onInsert 的列；更新、`byId`、插入或更新的更新部分、逻辑删除都填 onUpdate 的列；值每条语句取一次，总是覆盖行里的值，调用方无法绕过审计。

## 不做

- **实体跟踪与脏检查**、`save(entity)` 自动判断插入还是更新、级联写入：属于完整 ORM，见 0001。2026-10-24 复核了 EasyQuery 3.2.17 的跟踪实现：
  - 跟踪上下文绑在线程上，要手动 `begin()` / `release()`；
  - 更新执行后不论是否改到行都刷新快照（`AbstractClientEntityUpdatable.executeRows` 无条件调用 `refreshTrackEntities`），乐观锁冲突时这次修改会被当作已保存；
  - 新版本号只在绑定参数时算出（`VersionPropertySQLParameter`），不写回对象，同一上下文里第二次更新会因版本不符改不到行（读源码得出，未实跑）。

  因此差异更新改为无状态的 `diffFrom(loaded)`，见下文"之后补上的"。之后 [0008](0008-schema-tracking-sharding.md) 在 `diffFrom` 之上加了显式范围的实体追踪，避开了上面两个问题；级联写入仍然不做。
- **按非空列裁剪插入的列**：与取舍 1 相反；需要数据库默认值时不在行里放这个属性即可。
- **默认禁止物理删除**：与取舍 3 相反；逻辑删除的表已经默认不物理删除。
- **多行合并成一条 `INSERT ... VALUES (...), (...)`**：参数个数受 65535 的限制，且 HANA 不支持；JDBC 批量已经足够快。
- **`RETURNING` 任意列**：PostgreSQL 专属；只做三库都能取回的生成主键。

## 之后补上的（2026-10-24）

对照 EasyQuery 继续补齐的四批，规划见交接文档：

1. **写语句经过导航的条件**：之前 UPDATE、DELETE 的 WHERE 经过多对一导航会渲染出没有连接的列（SQL 无效）。改为 `EXISTS (SELECT 1 FROM 单行种子 LEFT JOIN 关联表 ... WHERE 条件)`，与查询里导航的 LEFT JOIN 语义一致，三库通用；SET 里用导航报错。另有 `diffFrom`、`@ReadOnly` / `@WriteOnce`、`db.exists`。
2. **配置与过滤**：运行时 schema 映射、具名过滤器与 `unfiltered(name)`、`withDeleted()`、`withoutFills()`、Java 的 `CodeEnum`、逻辑删除记毫秒数。
3. **函数**：`lpad`、`rpad`、`mod`、`power`、`sqrt`、`sign`、`hour`、`minute`、`second`、`addHours` / `addMinutes` / `addSeconds`、`monthStart`；先在三库实测了截断、负数和 NULL 的行为。
4. **联表 UPDATE / DELETE**：三库写法不同，一行匹配多行时 PostgreSQL、MySQL 任取一行而 HANA 的 MERGE 报错，所以入口只在方言包（`updateJoin`、`deleteJoin`），核心只有语句结构和方言钩子。HANA 沿用前身仓库实测过的 MERGE 写法。

## 验证证据

- 金样例（两端渲染完全相同）：`write-insert`、`write-insert-select`、`write-update`、`write-delete`、`write-update-by-id`、`write-delete-by-id`、`write-upsert`、`write-upsert-nothing`、`write-insert-generated`、`write-logic-delete`、`logic-delete-query`、`workflow-claim`。
- 三库实库场景（Java 与 TS 各一份）：影响行数与 `expect`、乐观锁冲突、生成主键的顺序、插入或更新（PostgreSQL 需要键上有主键或唯一约束）、`SKIP LOCKED` 领取任务、事务回滚、逻辑删除与审计填充。
- 补齐的四批：金样例 `write-navigation`、`time-functions`、`number-text-functions`、`write-join-update`、`write-join-delete`、`write-join-logic-delete`；实库场景覆盖导航条件的匹配行数（MySQL 驱动默认返回匹配行数，值没变的行也计入，三库一致）、差异更新与只读列默认值、具名过滤器与 schema 映射、按编码的枚举、毫秒逻辑删除下的唯一约束、各函数的结果、联表更新与删除；HANA 上一行匹配多行的联表更新报错且没有行被改。
- 本机三库与 CI 全部通过（2026-10-24）。

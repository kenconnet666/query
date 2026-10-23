# 交接（2026-10-24）

长期约定见 [CLAUDE.md](../CLAUDE.md)，本文件只记录当前进度。换电脑继续时先看[换电脑后的准备](#换电脑后的准备)。

1.0.0 已于 2026-10-22 发布（Maven 中央仓库 `org.dpdns.zerodep:*:1.0.0`，npm `@zerodep-dev/query-*@1.0.0`，标签 `v1.0.0` 指向 `966a236`）。2.0 在 `feat/v2` 上完成了全部七期、阶段 A（对照 EasyQuery 补全）和阶段 B（流程引擎读查询），尚未合并、尚未发布。

## 当前状态

### 2.0 完成了什么（`feat/v2`，设计见 [0006](../docs/decisions/0006-query-model-direction.md)）

- **第一期 骨架与删减**：Java 的表改为继承 `Entity` 的接口（每次表出现一个动态代理），投影只留单个表达式、`cols`、`row`、`selectAs`、`into`；`skipEmpty()` 代替 `IfPresent` 系列；作用域 16 张表、`row` 22 个参数由 `tools/generate-java-scopes.mjs` 生成。TS 同步删除重复写法。
- **整理**：Java 核心包按职责分子包（`table`、`expr`、`scope`、`select`、`exec` 等），TS 源码分 `schema`、`sql`、`query`、`exec` 文件夹；全部注释改为中文。
- **打印与测试 SQL**：`debugSql()`、`printSql`、`db.capture(...)`、空跑连接与驱动。
- **第二期 关系与导航**：`one`/`many`（Java 的 default 方法与 `@ManyToOne`/`@OneToMany`，TS 的 `@one`/`@many`）；多对一按需 LEFT JOIN，一对多的 EXISTS、相关子查询和 `first`。
- **第三期 动态列与排序**：`exclude`/`excludeIf`/`only`（列写成 NULL，相同的分组键和连接一起去掉）、`sortBy`。
- **第四期 对象树**：`many.list(...)` 按键批量加载子列表，Java 的 `selectAs` 自动映射嵌套 VO 和 `List<X>`。
- **第五期 条件对象**：`where(search)` 加 `@Where`/`@where`；`contains`、`startsWith`、`endsWith`。
- **第六期 原生 SQL**：`sql`、`sqlCond` 混用参数、`.on(方言类, ...)`、`sqlTable`、`identifier`。
- **第七期 文档**：两份 API 说明、[迁移指南](../docs/migration.md)（含从 1.x 升级）、[CHANGELOG](../CHANGELOG.md) 的 2.0.0、0006 改为已采纳、场景对照、README。
- 1.x 已有场景的金样例全部不变；新增 `navigation`、`one-to-many`、`dynamic-columns`、`filter-object`、`native-sql` 五个场景。

### 阶段 A：对照 EasyQuery 简化与补全（已完成）

四个子代理读完 EasyQuery 的测试（`sql-test`）和官方文档后结论一致，按三批推进：

- **第 1 批 可移植函数（已完成）**：聚合的 `.filter(cond)`（CASE WHEN，取代 PG 专属的 FILTER）、核心 `stringAgg`（取代 MySQL `groupConcat` 和两个方言版本）、`year`/`month`/`day`/`addDays`/`addMonths`/`addYears`/`daysBetween`（取代 HANA、MySQL 的同类函数）、`e.range(from, until)` 半开区间；金样例 `date-functions`、`conditional-aggregates`。
- **第 2 批 查询结构（已完成）**：`qualify(窗口条件)` 自动包派生表（写入器新增节点替换，窗口函数与不在结果里的排序项作为派生表的列）；`Many` 的 `countDistinct`、`stringAgg`；`firstValue`/`lastValue`/`ntile`、`lag`/`lead` 默认值；`db.page(q, page, size, total)`；金样例 `qualify`、`window-values`、`many-text`。
- **第 3 批 性能（已完成）**：`groupJoin(m -> m.deliveries())` 把同一关系的多个 count/sum/any 合成一个分组派生表（渲染时用节点替换，`Many` 的集合运算返回可替换的节点）；`many.orderBy(...).limit(n).list(...)` 每个父行限数（复用 qualify）；`@Where`/`@where` 走一对多路径时生成 EXISTS，同一关系的字段合并；金样例 `group-join`、`filter-many`。
- **决定不做**：类型化元组（Java 局部 record 加 `row` 已够）、跨方言日期格式串翻译（易错，保留方言函数）、递归 CTE（HANA 不支持）、动态表名、多对多中间表、JSON。

### 阶段 B：流程引擎的读查询（已完成）

- 子代理读了 Flowable、Camunda 7、Activiti 7、jBPM 7、Warm-Flow（经 RuoYi-Vue-Plus）、SnakerFlow 的映射文件和查询代码，整理出共同模式；结论写进[场景对照](../docs/scenarios.md#流程引擎的读查询)。
- 简化后的六张表（定义、实例、任务、身份链接、变量、历史任务）和查询放在测试夹具 `Workflow`（Java）与 `fixtures/workflow.ts`（TS）：待办（候选人或候选组，条件对象，分页计数）、变量过滤三种写法（EXISTS、groupJoin、多别名 JOIN）、最新版本两种写法（自关联的 `versions().max`、qualify）、已办、定义统计（groupJoin）、工作量（条件聚合）、读取时算耗时。金样例 `workflow-*` 共 9 个场景，三库实库全部通过。
- 由此补上的能力：`secondsBetween(from, to)`（三库统一为整秒、舍去小数）。其余写法用现有能力即可，没有发现别扭到需要新 API 的地方。

### 写入能力（进行中，用户 2026-10-24 确认：插入时全部列都写、逻辑删除和审计填充这一轮做、只拦没有 WHERE 的 DELETE）

- **第 1 步（已完成）**：`@Id`/`@Version`/`@Generated`/`@LogicDelete` 注解和 TS 装饰器（元数据就绪，后几步用）；`insertInto(T).values(rows)`、`.from(query)`，`update(T).set(...).where(...)`，`deleteFrom(T).where(...)`；没有 WHERE 报错（`.all()` 放行），全局过滤器进 WHERE，`expect(n)`，`skipEmpty` 省略空值的 SET；`db.execute`、`db.render(write)`、`db.transaction(...)`（Java 线程绑定连接，TS 用 AsyncLocalStorage；Spring 用 @Transactional）；多行写入自动在一个事务里，Java 用 JDBC 批量。金样例 `write-*`，三库实库通过。
- **第 2 步（已完成）**：`update(T).byId(rows)`、`deleteFrom(T).byId(rows)`：按 `@Id` 定位，有 `@Version` 时 `SET version = version + 1 ... AND version = ?`，期望每行一行（乐观锁冲突抛 UnexpectedRowCount）；`skipEmpty().byId(row)` 只更新非空属性；`db.insertKeys(insert)` 取回 `@Generated` 的值（方言钩子：PG 由驱动或 RETURNING，MySQL 生成键或 insertId，HANA 在同一连接上查 CURRENT_IDENTITY_VALUE()）。
- **第 3 步（已完成）**：`insertInto(T).values(row).onConflict(keys?).update(cols?)` / `.doNothing()`（方言钩子：HANA `MERGE ... USING (SELECT ? AS ... FROM DUMMY)`，PG `ON CONFLICT ... DO UPDATE SET c = EXCLUDED.c`，MySQL `... AS new ON DUPLICATE KEY UPDATE c = new.c`，什么也不做时 `k = k`）；加锁读 `.with(forUpdate(Lock))`（HANA 的 SKIP LOCKED 写作 IGNORE LOCKED）。
- **待做**：第 4 步逻辑删除 + 审计填充；最后写 API 文档、决定记录 0007、CHANGELOG。设计草稿在会话 scratchpad 的 `write-design.md`。

### 测试（本机三库全部通过）

- **Java**：core 185，HANA 105，PostgreSQL 109，MySQL 102（含实库场景与金样例），Spring 5。
- **TS**：单元、编译期与金样例 271，实库 142。
- 本机实库：WSL Debian 的 Docker 里按 `infra/` 启动三库，连接信息在已忽略的 `.local/connections.md`。

## 待用户决定或执行

- **合并 `feat/v2`**：需要用户同意后开 PR 合并进 `master`。
- **发布 2.0.0**：版本号目前仍是 1.x（根 POM、各模块 parent、TS 包、`docs/api.md` 的依赖示例要一起改），CHANGELOG 的 2.0.0 写的是"未发布"；按 [docs/release.md](../docs/release.md) 手动发布。
- **前身仓库是否冻结**：0001 里唯一的待定项。
- **是否给本库加写入能力**（自研流程引擎需要）：流程引擎的写操作有乐观锁更新（`UPDATE ... SET REV_ = ? WHERE ID_ = ? AND REV_ = ?`，影响 0 行即并发冲突）、认领任务（Activiti 7 用 `FOR UPDATE SKIP LOCKED`，PG 和 MySQL 写法不同，HANA 未见等价写法）、批量插入、按编号批量删除、逻辑删除（更新 `del_flag`）和历史清理（按时间查编号再删除）。可选方向：本库加最小的 insert/update/delete 构建器（条件对象可复用于 update/delete 的 WHERE），或者写入交给 JDBC/MyBatis、本库只管查询。请用户决定。

## 下一步

用户在 2026-10-23 定下的两个阶段（对照 EasyQuery、流程引擎读查询）都已完成。接下来等用户决定上面的待定事项：写入能力的方向、合并与发布 2.0。

## 换电脑后的准备

仓库里的东西 `git clone` 后就齐了。下面这些只存在于旧电脑上，新电脑需要重新准备或手动拷贝：

| 东西 | 在旧电脑上的位置 | 新电脑上怎么办 |
| --- | --- | --- |
| 本仓库 | `C:\Users\lionheart\IdeaProjects\query` | `git clone https://github.com/kenconnet666/query`，在 `master` 上新开分支 |
| 参考仓库（只读） | `IdeaProjects\easy_query_pro`、`IdeaProjects\easy-query`、`IdeaProjects\orm-references\*` | 按 CLAUDE.md 的说明重新克隆；不影响构建，只用于设计参考 |
| 使用方代码（只读） | 旧电脑的本地目录 | 只在 Claude 项目记忆里有路径和说明，不进公开仓库 |
| Claude 项目记忆 | `C:\Users\Administrator\.claude\projects\C--Users-Administrator-IdeaProjects-query\memory\` | 含使用方说明和设计证据，按需手动拷贝到新电脑对应目录；没有它也能继续，只是缺少使用方背景 |
| 开发数据库 | WSL Debian 里的 Docker，由前身仓库的 `tools/*.ps1` 启动 | 见下文；没有本机库也能开发，实库测试交给 CI |
| IDEA 的 MCP 与数据源 | 用户级配置 `idea` 与四个数据源 | 在新电脑的 IDEA 中重新配置，连接 ID 会变 |
| 对照程序 | 旧电脑的会话临时目录，含使用方的业务常量，不进仓库 | 不需要；结论在 0002 与 0003 里 |

**最小可用环境**：

1. **JDK 17**：
   - CLAUDE.md 写的是旧电脑的 `C:\Users\Administrator\.jdks\jbr-17.0.14`；
   - 新电脑路径不同时，把 `JAVA_HOME` 指向任一 JDK 17 即可；
   - Maven 3.9.16 由 wrapper 自动下载。
2. **Node 24 与 corepack**：在 `typescript/` 里运行 `corepack pnpm install` 和 `corepack pnpm test`，pnpm 11.22.0 由 `packageManager` 固定。
3. **先跑一遍不需要数据库的测试**：

   ```powershell
   .\mvnw.cmd --batch-mode --threads 1C verify
   cd typescript; corepack pnpm install; corepack pnpm test
   ```

   实库场景缺少环境变量时会自动跳过。

**需要本机实库时**：

- 启动本仓库 `infra/` 下对应数据库目录里的 `compose.yaml`（命令见文件开头的注释，CI 用的也是它们，内存限制相同）；前身仓库 `tools/*.ps1` 启动的是库名 `eqp` 的旧实例，端口相同，不要同时运行。
- 然后按 CLAUDE.md 的"实库测试的变量"设置 `QUERY_*`。
- CLAUDE.md 里的 WSL 地址是旧电脑的，在新电脑上要换成实际值；数据库密码在各数据库目录的 `.env`，按 `compose.yaml` 开头的注释生成。
- 用本仓库的 compose 文件启动时，密码来自自己设置的 `QUERY_DB_PASSWORD` / `QUERY_HANA_PASSWORD`。

**新电脑上要核对的 CLAUDE.md 内容**：

- 本机 JDK 路径；
- WSL、Docker 的网络与地址；
- IDEA 数据源的连接 ID。

这些是旧电脑的事实，确认后直接改成新电脑的值。

## 容易踩的坑

- **LSP 路径**：会话从别的目录启动后再切到本仓库时，`${CLAUDE_PROJECT_DIR}` 仍指向原目录，TS LSP 会报 ENOENT。从本仓库目录新开会话即可。LSP 需要 `typescript/` 已安装依赖。
- **WSL 地址**：WSL 的 `eth0` 地址在 WSL 重启后会变。连不上数据库时，先用 `wsl -d Debian hostname -I` 查，或者直接用 `localhost`。
- **corepack**：非交互运行 corepack 时要设置 `COREPACK_ENABLE_DOWNLOAD_PROMPT=0`。
- **pnpm 11**：依赖的安装脚本要在 `pnpm-workspace.yaml` 的 `allowBuilds` 里放行，`@sap/hana-client` 已放行。
- **PowerShell 传 Maven 参数**：带点的 `-D` 参数要加引号，例如 `'-Dtest=HanaLiveTest'`。
- **金样例**：改了渲染以后，用 `$env:QUERY_SPEC_WRITE = '1'` 运行 `*SpecTest` 重写 `spec/render/`，审查 diff，再跑 TS 的 `spec.test.js` 确认两端一致。
- **Java 泛型**：行类来源的 `col(Getter)` 公共 API 要写成泛型 `<E> Getter<? super E, ?>`，通配符参数接收不了方法引用；同类型的多参数运算要做成实例方法（理由见 0002 第 4 条）。
- **TS 类型**：
  - `instanceof` 取反会把 `w.dialect` 收窄成 `never`，先存到 `const dialect: Dialect` 再判断；
  - getter 在类型上像只读字段，`into` 的字段检查已排除只读属性；
  - 只为编译期检查写的错误代码要放进不会被调用的函数里，否则测试运行时会真的执行它。
- **Bash 工具里的转义**：在 heredoc 或 `python -` 里写入文本时，反斜杠转义（例如 Java 字符串里的换行符转义）会被提前展开成真正的换行，字符串因此断开。含反斜杠的改动用编辑工具写。
- **Bash 工具里的大段 heredoc**：内容很长且引号很多时会报 "unexpected EOF"。先用写文件工具把脚本写到临时目录，再执行它。
- **HANA 内存**：HANA 会把 cgroup 内存限制当作物理内存。8 GiB 是 SAP 公布的最低值，测试变慢或内存不足时先查看占用，不要调大限制。
- **javac**：Java 17 上 switch 模式匹配仍是预览特性，渲染器用节点自己的 `render` 方法。

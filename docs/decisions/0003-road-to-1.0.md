# 0003 通往 1.0 的蓝图

- 状态：已采纳（2026-10-21），按里程碑执行中
- 承接 [0002](0002-api-architecture.md) 的分期：第三期剩余的事项按本文重新取舍

## 背景

第一到第三期完成后，库已经能表达内部 ERP 分析大屏里的三条代表性查询（见 0002 的验证证据）。下一步的目标是"完全可用"，取舍依据是对使用方代码的只读统计：

- 主要的分析模块里，26 个文件直接查询 HANA，共约 139 段 SQL 文本、1.1 万行。
- 25 个文件用请求对象传入筛选条件；可选条件已经可以用 `eqIfPresent` 等一行写完。
- 4 到 5 处在 Java 里把一条扁平查询的结果按键分组成"父项加子项列表"；没有"父查询、子查询分两次执行"的用法。
- 写入只有 3 个文件，内容是把 HANA 的查询结果写成 MySQL 快照；`read_parquet` 剩 4 个文件，正在迁往 HANA。
- HANA 计算视图、表函数、层级函数、JSON、ROLLUP 的使用次数都是 0。
- 函数以 COALESCE、TRIM、NULLIF、SUBSTR、REPLACE、ROUND 为主，都已支持；缺的是 FLOOR、GREATEST/LEAST、HANA 的 LOCATE 与 TO_INT 等少数几个。

## 决定

### 1. "完全可用"的定义

1. 使用方直接查 HANA 的查询都能用本库的 Java 版改写，**查询结果**与原 SQL 一致；生成的 SQL 文本不要求相同。
2. 以 1.0 版本发布到 Maven 中央仓库，使用方能以普通依赖引入（Spring Boot 2.7 对应的 Spring 5.3 已由 `query-spring` 支持）。发布由维护者手动执行，仓库只提供配置和步骤。
3. TypeScript 与 Java 保持同步，由金样例保证两端渲染一致。
4. 有快速上手、迁移指南和 API 说明，CI 全绿。

### 2. 做什么

| 里程碑 | 内容 |
| --- | --- |
| M1 表达力审计 | 逐段阅读使用方的 HANA SQL，按构造分类计数，得出缺口清单。通用结论写进本文；具体 SQL 和业务常量不进仓库 |
| M2 补缺口 | 已知的 FLOOR、CEIL（核心包），GREATEST、LEAST（方言包，三库对 NULL 的处理不同），HANA 的 LOCATE、TO_INT 等，加上审计发现的其他项；两端、三库、金样例、实库 |
| M3 结果分组 | `nest`：一条查询的扁平结果按键分组成父项加子项列表，保持顺序；Java 与 TS 都做 |
| M4 扩大对照 | 从审计里挑 8 到 10 条代表性查询，在本机 HANA 的合成数据上比较原 SQL、Java 改写、TS 改写三者的结果 |
| M5 1.0 准备 | API 审查（命名、可见性）、错误信息、并发与连接泄漏测试、性能粗测（相对直接写 JDBC 的开销）、SLF4J 日志监听器示例 |
| M6 发布准备 | 中央仓库要求的 POM 元数据、源码与 Javadoc 包、签名与发布插件（放在发布 profile 里）、版本号与 CHANGELOG、README 快速上手、迁移指南、手动发布步骤 |

### 3. 不做或延后

- **写入**：快照写入继续用 JdbcTemplate；本库保持只读查询。
- **按请求对象的注解生成条件**：不做。一个筛选条件只要一行 `eqIfPresent`，而且有类型检查；注解版本只能用字符串写属性名（项目不使用代码生成），会丢掉类型安全。
- **两次查询的批量加载子表**：没有使用证据，不做；需要时由 `nest` 加一次 `IN` 查询组合出来。
- **DuckDB 方言**：延后，等使用方剩余的 parquet 读取迁完后再评估。
- **HANA 计算视图与表函数、JSON、递归与层级查询、加密列**：没有使用证据，延后。
- **MySQL 的流式读取默认缓冲**：需要逐行读取时配置 `fetchSize(Integer.MIN_VALUE)`，不自动切换（理由见 0002 第 8 条）。

- **文档站**：不做，之后另行安排；1.0 只提供仓库里的 Markdown（README、快速上手、迁移指南、API 说明）。

## 验证证据

### M1 表达力审计（2026-10-21）

- **范围**：使用方直接查 HANA 的 26 个文件里的 121 段 SQL 文本，约 4900 行。其中 1 段是 DuckDB 查询、几段是写 MySQL 快照的语句，不在范围内。
- **方法**：先用脚本统计各种构造和函数出现在多少段里，再逐段阅读。脚本和摘录只放本机，不进仓库。
- **已能表达的构造**（括号内为出现的段数）：CTE（52）、LEFT JOIN（59）、CASE（82，其中简单 CASE 10）、GROUP BY（68）、DISTINCT（41）、COUNT(DISTINCT)（24）、UNION ALL（29）、EXISTS（19）、派生表（19）、窗口函数（11）、`FROM DUMMY` 码表（9）、CROSS JOIN（9）、LIMIT/OFFSET（7）、HAVING（4）、`IN` 子查询（6）、标量子查询，以及 `COUNT(*) OVER ()` 取分页总数。
- **函数**：COALESCE、TRIM、NULLIF、MAX、SUM、SUBSTR/SUBSTRING、COUNT、REPLACE、LEFT、ROUND、TO_DATE、DAYS_BETWEEN、TO_VARCHAR 等都已支持。

**缺口**（M2 补齐）：

1. 起止位置是表达式的 SUBSTRING，配合 LOCATE 拆分逗号分隔的值。
2. LOCATE：三库语义一致（从 1 开始，找不到为 0），放核心包，由方言渲染。
3. GREATEST、LEAST：三库对 NULL 的处理不同，放方言包。
4. HANA 的 TO_INT、TO_INTEGER。CAST 能得到同样的结果，仍在 HANA 包里提供 `toInteger`。
5. Java 的命名投影必须逐列写全，`SELECT p.*, 额外列` 这种写法（6 段）很啰嗦。改为：没有列出的属性，按同名列自动补齐。
6. 简单 CASE（10 段，最长 13 个分支）：新增 `caseOf(expr).when(值, 结果)`。
7. 多参数 COALESCE（最多 6 个参数）：`coalesce` 接受多个后备值，渲染成一个 COALESCE。
8. FLOOR、CEIL：使用方没有用到，但三库语义一致、成本低，一并放进核心包。

**不需要新 API 的写法**，写进迁移指南：

- UNION 之后的 ORDER BY（3 段）：把合并结果包成 CTE 再排序。
- `%s` 拼出的动态列名：在 Java 里选择 getter。
- `FROM %s` 拼出的动态表：同形状的实体子类加泛型模板。
- 按条件拼接的 JOIN：改成可选的 EXISTS。
- `(:p = 'ALL' OR col = :p)` 和多分支的 `:mode` 条件：`eqIfPresent`、`onlyIf` 或 Java 的 `switch`。
- `{{X}}` 拼出的 CTE：返回 CTE 的函数。
- 由运行时的值组成的常量行：`values(...)`。
- 整数相除：先 CAST 成小数（`divide` 只接受小数，避免各库整数除法的差异）。

### M2 补缺口（2026-10-21）

- 缺口 1 到 8 都已补上，Java 与 TS 同时实现，三个方言都有金样例和实库测试：
  - LOCATE 在 PostgreSQL 上渲染为 `STRPOS(text, search)`，在 MySQL 上为 `LOCATE(search, text)`（参数顺序相反）；
  - 起止位置为表达式时，`SUBSTRING(text, 1, (STRPOS(text, ',') - 1))` 在三个库上都按预期截取。
- 实库确认 GREATEST 遇到 NULL 的差异：HANA、MySQL 返回 NULL，PostgreSQL 跳过 NULL。
- Java 的 `greatest(列, 常量)` 原本有"表达式加常量"的重载，但 `l.col(getter)` 本身是泛型方法，javac 把 T 推断成 Object，选中了常量重载（0002 第 4 条的问题）。实库测试抓到了这一点，于是去掉这个重载，常量统一写成 `val(x)`。
- Java 命名投影改为按形状的属性顺序输出列，同一形状的 UNION 各段因此按名字对齐（TS 本来就按键名对齐）。原有的 76 个金样例没有变化。

### M3 结果分组（2026-10-21）

- 使用方的写法是：一条查询取出扁平的行，再在 Java 里用 `LinkedHashMap` 按键分组，父项取第一行，子项逐行累加。`Nest.into`（TS 为 `nest`）把这件事做成一个函数：每个键一个父项，保持首次出现的顺序；子项函数返回 null（LEFT JOIN 没有匹配）时不加子项。
- 没有放进 `Query` 或 `Database`：分组会改变行数，与分页、流式读取的语义冲突，作为对结果的普通函数更简单。
- 三个库上的实库场景：物料 LEFT JOIN 交货，按物料分组；没有交货的物料得到空的子项列表。

### M4 扩大对照（2026-10-21）

- 从审计里挑了 8 条查询，覆盖不同的构造：
  - 13 个分支的简单 CASE 与自连接两次的人员表；
  - 按月分组的趋势；
  - 码表、交叉连接与比率；
  - `COUNT(*) OVER ()` 分页明细；
  - 订单缺货率（GREATEST、多段 UNION 后排序、HAVING COUNT DISTINCT）；
  - 采购周期（20 多个 CTE、ROW_NUMBER、冲销判断、LOCATE 拆分 ATA、TO_INTEGER）；
  - AOG 明细（DISTINCT 加多层 LEFT JOIN）；
  - 报废供应商选项（ROW_NUMBER 取最新、EXISTS）。
- 在本机 HANA 上建了 36 张表的合成数据，数据覆盖各个分支。原 SQL、Java 改写、TS 改写三者对比，每条查询 2 到 7 组参数，共 36 组，两端结果全部逐行一致；生成的 SQL 与原文不同。
- 对照暴露并已处理的问题：
  - **TS**：`lit("3.1")` 和 `val("A")` 被推断成字面量类型，只差一个常量的 UNION 各段因此类型不一致。已改为字符串、数字、布尔放宽成 `string`、`number`、`boolean`（与 `caseWhen` 的结果一致），并加了编译期检查。
  - **Java**：一条查询用到 10 张表，超过作用域的 8 张上限，按既定做法把用户名与姓名的连接拆成 CTE。
  - **Java**：对照程序把约 600 个不同的 getter 引用写在一个类里，javac 报 "code too large"。原因是 getter 必须可序列化（库靠 SerializedLambda 取属性名），javac 为每个类生成一个 `$deserializeLambda$` 方法，每个不同的方法引用占一个分支，超过 64KB 方法上限。拆成两个类后正常。真实服务类很少到这个量级，写进了代价与风险。
- 对照程序含使用方的业务常量，只放本机，不进仓库。

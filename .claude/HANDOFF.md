# 交接（2026-10-21）

新会话从本仓库目录 `C:\Users\Administrator\IdeaProjects\query` 启动。长期约定见 [CLAUDE.md](../CLAUDE.md)，本文件只记录当前进度。

## 当前状态

### 分支

- 分支依次叠加，都已推送，尚未合并到 `master`，合并需要用户明确要求：
  1. `feat/java-core`：第一期 Java；
  2. `feat/typescript-core`：第二期 TypeScript；
  3. `feat/real-query-parity`：真实查询对照、补齐的能力与 Spring 适配。
- `master` 只有方向文档、TS 工程骨架与 LSP 插件。

### 已完成

- **文档**：
  - [0002 API 与架构](../docs/decisions/0002-api-architecture.md)，第 9 条是 TS 的决定；
  - [Java API 使用说明](../docs/api.md)、[TypeScript API 使用说明](../docs/api-typescript.md)；
  - [场景对照](../docs/scenarios.md)，以 EasyQuery 的场景为清单；
  - 0001 已记下：从 HANA 目录生成实体的工具暂时不做。
- **Java 第一期**，包括 `query-core`、`query-hana`、`query-postgres`、`query-mysql` 四个模块：
  - 注解实体、getter 引用、lambda 作用域（1 到 8 张表）；
  - 函数式、按名、单列三种投影；
  - CTE 提升、UNION、窗口函数、子查询；
  - 全局过滤器、分页、流式读取、原生 SQL、结果缓存；
  - 映射计划缓存；三个方言的门面、专属函数与子句。
- **TypeScript 第二期**，`typescript/` 下的 pnpm 工作区，包括 `@query/core`、`@query/hana`、`@query/postgres`、`@query/mysql`：
  - 标准装饰器实体，列类型由 `@column` 声明，装饰器检查字段类型；
  - 回调作用域按位置跟踪 LEFT JOIN 的可空性；对象投影即列标签，支持嵌套、`selectAs`、`mapTo`、`map`；
  - 与 Java 相同的渲染规则、全局过滤、缓存、分页、原生 SQL；
  - 驱动适配 `@sap/hana-client`、`pg`、`mysql2`，三库都支持游标式流读取。
- **真实查询对照**：内部 ERP 分析大屏的三条查询（期间收入、应收账龄、报废与库存）在本机 HANA 上用合成数据对比，原 SQL 与 Java、TS 改写各 21 组参数结果一致。由此补上：
  - 常量行 `values`；
  - `substring`、`left`、`right`、`replace`、`negate`、接收列表的 `plus`；
  - HANA 的 `likeRegexpr`、`toNvarchar`；
  - 常量字面量规则（0002 第 10 条）。
- **Spring 适配**：`java/query-spring` 的 `SpringConnections`，在 Spring 5.3、6.2、7.0 下通过 H2 事务测试。
- **两端一致**：`spec/render/` 下 60 个金样例，Java 与 TS 的方言测试都读取，SQL 与参数逐字相同。
- **测试**（中文命名，本机三库全部通过）：
  - Java 196 个（含 63 个金样例、48 个实库场景、javac 编译期检查、3 个 Spring 事务测试）；
  - TS 180 个（含 63 个金样例、48 个实库场景、16 条 `@ts-expect-error` 编译期检查）。
- **CI**：`.github/workflows/ci.yml` 并行运行 Java 单元、TS 单元、Spring 6.2 与 7.0 适配，以及 PostgreSQL、MySQL、HANA 三个实库 job；每个实库 job 先跑 Java 再跑 TS。

### 待用户决定（见 0001）

前身仓库是否冻结。

## 下一步（建议）

1. 确认这次推送后 CI 全绿（HANA job 启动约需 10 分钟）。
2. 由用户决定是否开 PR 把三个分支合并进 `master`。
3. **第三期剩余**（见 0002 的分期）：分组集合与 HANA 计算视图等方言扩展、SQL 级列转换、一对多的批量组装、按请求对象生成条件。
4. 在使用方仓库里挑一个服务试点迁移，需要用户同意（使用方代码目前只读）。

## 容易踩的坑

- **LSP 路径**：会话从别的目录启动后再切到本仓库时，`${CLAUDE_PROJECT_DIR}` 仍指向原目录，TS LSP 会报 ENOENT。从本仓库目录新开会话即可。
- **WSL 地址**：WSL 的 `eth0` 地址在 WSL 重启后会变。连不上数据库时，先用 `wsl -d Debian hostname -I` 查，或者直接用 `localhost`。
- **corepack**：非交互运行 corepack 时要设置 `COREPACK_ENABLE_DOWNLOAD_PROMPT=0`。
- **pnpm 11**：依赖的安装脚本要在 `pnpm-workspace.yaml` 的 `allowBuilds` 里放行，`@sap/hana-client` 已放行。
- **PowerShell 传 Maven 参数**：带点的 `-D` 参数要加引号，例如 `'-Dtest=HanaLiveTest'`。
- **Java 泛型**：形如 `Getter<?, ?>` 的通配符参数接收不了方法引用，公共 API 要写成泛型 `<E> Getter<? super E, ?>`；同类型的多参数运算要做成实例方法（理由见 0002 的第 4 条）。
- **TS 类型**：
  - `instanceof` 取反会把 `w.dialect` 收窄成 `never`，先存到 `const dialect: Dialect` 再判断；
  - getter 在类型上像只读字段，`mapTo` 的字段检查已排除只读属性；
  - 只为编译期检查写的错误代码要放进不会被调用的函数里，否则测试运行时会真的执行它。
- **Bash 工具里的转义**：heredoc 或 `python -` 写入的文本中，`\n` 会被变成真正的换行，Java 字符串因此断开。含反斜杠的改动用编辑工具写。
- **HANA 内存**：HANA 会把 cgroup 内存限制当作物理内存。8 GiB 是 SAP 公布的最低值，测试变慢或内存不足时先查看占用，不要调大限制。
- **javac**：Java 17 上 switch 模式匹配仍是预览特性，渲染器用节点自己的 `render` 方法。

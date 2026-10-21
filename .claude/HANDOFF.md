# 交接（2026-10-21）

新会话从本仓库目录 `C:\Users\Administrator\IdeaProjects\query` 启动。长期约定见 [CLAUDE.md](../CLAUDE.md)，本文件只记录当前进度。

## 当前状态

### 分支

- 第一期 Java 实现在 `feat/java-core` 分支，已推送，尚未合并到 `master`。合并需要用户明确要求。
- `master` 只有方向文档、TS 工程骨架与 LSP 插件。

### 已完成

- **文档**：
  - [0002 API 与架构](../docs/decisions/0002-api-architecture.md)；
  - [API 使用说明](../docs/api.md)；
  - [场景对照](../docs/scenarios.md)，以 EasyQuery 的场景为清单；
  - 0001 已删去被取代的设计示意。
- **Java 第一期**，包括 `query-core`、`query-hana`、`query-postgres`、`query-mysql` 四个模块：
  - 注解实体、getter 引用、lambda 作用域（1 到 8 张表）；
  - 函数式、按名、单列三种投影；
  - CTE 提升、UNION、窗口函数、子查询；
  - 全局过滤器、分页、流式读取、原生 SQL、结果缓存；
  - 映射计划缓存；三个方言的门面、专属函数与子句。
- **测试**：用中文命名，本机三库全部通过，共 122 个。
  - 编译期类型检查测试用 javac 验证 11 种错误写法不能编译。
  - 三库共用一组实库场景（`LiveScenarios`）。
- **CI**：`.github/workflows/ci.yml` 并行运行单元测试、PostgreSQL、MySQL、HANA 实库与 TypeScript 类型检查，容器定义在 `infra/`。

### 待用户决定（见 0001）

1. 是否允许一个独立的生成工具（不是 APT）从 HANA 目录生成实体源码？
2. 前身仓库是否冻结？

## 下一步（建议）

1. 确认 CI 在 GitHub 上全绿（HANA job 启动约需 10 分钟）。
2. **第二期 TypeScript**：
   - 按 0002 的第 9 节实现 `@query/core`、`@query/hana`、`@query/postgres`、`@query/mysql`；
   - 建立 `spec/` 金样例，Java 与 TS 共同读取；
   - 探针结论记在本机 Claude 项目记忆里。
3. **用使用方的真实查询做对照**：只读，不改使用方代码。把一两条复杂查询改写成本库写法，检查遗漏的能力。

## 容易踩的坑

- **LSP 路径**：会话从别的目录启动后再切到本仓库时，`${CLAUDE_PROJECT_DIR}` 仍指向原目录，TS LSP 会报 ENOENT。从本仓库目录新开会话即可。
- **WSL 地址**：WSL 的 `eth0` 地址在 WSL 重启后会变。连不上数据库时，先用 `wsl -d Debian hostname -I` 查，或者直接用 `localhost`。
- **corepack**：非交互运行 corepack 时要设置 `COREPACK_ENABLE_DOWNLOAD_PROMPT=0`。
- **PowerShell 传 Maven 参数**：带点的 `-D` 参数要加引号，例如 `'-Dtest=HanaLiveTest'`。
- **Java 泛型**：形如 `Getter<?, ?>` 的通配符参数接收不了方法引用，公共 API 要写成泛型 `<E> Getter<? super E, ?>`；同类型的多参数运算要做成实例方法（理由见 0002 的第 4 条）。
- **HANA 内存**：HANA 会把 cgroup 内存限制当作物理内存。8 GiB 是 SAP 公布的最低值，测试变慢或内存不足时先查看占用，不要调大限制。
- **javac**：Java 17 上 switch 模式匹配仍是预览特性，渲染器用节点自己的 `render` 方法。

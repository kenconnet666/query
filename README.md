# query

面向分析型复杂查询的类型安全 SQL 构建库，同时提供 Java 与 TypeScript 两个实现。它不依赖 EasyQuery，也不做完整 ORM：只负责把查询写成可组合、可复用、可校验的值，再按方言渲染并执行。

当前处于设计阶段，还没有可用的 API。方向、范围和待定事项见 [0001 独立的分析查询库](docs/decisions/0001-standalone-analytic-query-library.md)。

## 基线

| 项 | 要求 |
| --- | --- |
| Java | 17 及以上，`maven.compiler.release=17` |
| TypeScript | 6.0 及以上 |
| 代码生成 | 不使用注解处理器（APT） |
| 首批方言 | SAP HANA、DuckDB；随后是 PostgreSQL、MySQL |

## 开发环境

本机 JDK 使用 `C:\Users\Administrator\.jdks\jbr-17.0.14`（IDEA 中的 SDK 名为 `jbr-17`）。Maven 版本由 wrapper 固定为 3.9.16。

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.jdks\jbr-17.0.14'
.\mvnw.cmd --batch-mode verify
```

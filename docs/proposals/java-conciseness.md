# 建议：Java 写法进一步简化（已被 0006 取代）

2026-10-23 基于 1.0.0 的调研建议。其中的表接口加 JDK 代理方案，已由 [0006](../decisions/0006-query-model-direction.md) 采纳并在 2.0 实现；按编码的枚举也已在 2.0 提供。写法以 0006 和[用法说明](../api.md)为准。本文只保留 0006 没有记录的 Lombok 实测结论。

## Lombok 实测（JBR 17、Lombok 1.18.46、1.0.0 的代码）

- **`@Accessors(fluent = true)`**：1.0 的 getter 引用写成 `Lips::material`，可以直接解析和渲染。按 1.0 用法说明第 3 节的主查询算，只少约 7% 的字符。
- **映射**：`@SuperBuilder` 的 DTO 能映射，子类字段也会填上；`@Value` 的 DTO 映射失败，因为它没有无参构造器，也没有 `builder()`。
- **`@FieldNameConstants(asEnum = true)`**：会往手写的 `enum Fields implements X {}` 里补常量（1.18.6 起跳过已存在的成员）。但枚举常量带不了各列的值类型，不能代替类型化的列引用。
- **`@ExtensionMethod(Hana.class)`**：能把方言函数写成 `a.daysBetween(b)`，但官方把这个特性标为"搁置"，并承认 bug 偏多，不推荐使用方依赖。
- **Lombok 自定义 handler**：属于代码生成，IDEA 的 Lombok 插件也认不出来，不采用。

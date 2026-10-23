# 建议：Java 写法进一步简化

2026-10-23 的调研建议，还没有决定，代码没有改动。

## 结论

Lombok 缩短不了最占篇幅的 `l.col(Lips::getMaterial)`，最多变成 `Lips::material`。明显的收益来自让用户手写一个表接口，由库用 JDK 动态代理实现。按[用法说明](../api.md)第 3 节的主查询算（去掉空白后的字符数）：现在 639，流式 getter 597，表接口 491（少 23%）。

## 实测（JBR 17、Lombok 1.18.46、1.0.0 的代码）

- `@Accessors(fluent = true)`（`Lips::material`）和 `@SuperBuilder` 的 DTO 现在就能用；`@Value` 的 DTO 映射失败。
- `@FieldNameConstants(asEnum = true)` 会往手写的 `enum Fields implements X {}` 里补常量，但枚举常量带不了各列的值类型。
- `@ExtensionMethod(Hana.class)` 能写成 `a.daysBetween(b)`，但官方把它标为"搁置"，不推荐。
- 表接口加 JDK 代理可行：泛型返回类型能给出值类型；default 方法可以当派生列；接口继承可以做泛型模板；类型不符时编译失败。

## 方案

1. **1.x 小改，不破坏兼容**：支持 `@Value`（全参构造器）的 DTO；编码枚举（`Coded { String code(); }`）；带属性名占位符的多列公式 `@Formula("COALESCE({quantity}, 0) * {price}")`，两端一起做。
2. **表接口（2.0 级别）**：

   ```java
   @Table("LIPS")
   public interface Lips extends Client {
       @Column("MATNR") Col<String> material();
       @Column("LFIMG") Col<BigDecimal> quantity();
       default Expr<BigDecimal> amount() { return quantity().coalesce(ZERO).times(price()); }
   }
   from(Lips.class).join(Mara.class, (l, m) -> m.material().eq(l.material()))
   ```

   - 写法与 TS 一致，也不再依赖 SerializedLambda。
   - 代价：表不能再作结果行；作用域的参数类型要从 `Ref<E>` 改成句柄类型，需要发 2.0。

不采用的做法：

- EasyQuery 式的实体加代理类：每列要写两遍。
- Lombok 自定义 handler：属于代码生成，IDE 也不认识。

## 待决定

1. 是否接受"表只是接口、结果一律是 DTO"。
2. 为方案 2 发 2.0，还是只做方案 1。

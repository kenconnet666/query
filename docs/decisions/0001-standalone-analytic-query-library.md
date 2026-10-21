# 0001 独立的分析查询库

- 状态：方向已采纳（2026-10-21）；Java API 待原型验证
- 前身：`easy_query_pro`（EasyQuery 3.2.17 的 Java 8 增强包，以及仓库内的 TypeScript ORM 子项目）

## 背景

### 前身的问题

| 维度 | 问题 |
| --- | --- |
| 接入 | 每个数据库需要按固定顺序手动安装 4 步（服务、JDBC handler、应用组件、类型映射），顺序错误就会出错；Spring 示例配置约 100 行；HANA 只能借 starter 的 SQL92 入口接入。 |
| 查询写法 | 每段表达式都要先 `ProDsl.of(entity)` 包装，例如 `ProDsl.of(p).nullSafeEqualsValue(p.name(), v).eq(true)`；数据库专属类型要先 `view(column, type)` 才能使用。 |
| 扩展 | 上游 APT 的列类型映射是写死的，专属类型无法直接出现在列上；扩展依赖继承（protected `recreate`、SELF 泛型、继承后委托 super），并继承或实现了约 40 处上游内部类，上游一升级就容易出问题。 |
| 模块 | 两个基础模块没有共同依赖，工具类只能各复制一份；公共 facade 有 46 个方法，同一个函数要按类型族重载多次。 |
| TS 方言 | 方言是写死的三选一联合类型，编译器里有 28 处按方言名分支，函数 renderer 也按方言名作 key，新增或替换方言都要改核心代码。 |

### 实际使用方需要什么

首个使用方是一个内部 ERP 分析大屏：Spring Boot 2.7、Java 17，数据访问用 MyBatis-Plus 和多数据源。分析查询直接查 SAP HANA 的 ABAP 表，另有一部分用 DuckDB 读取 parquet 快照，这部分正逐步改为直连 HANA。分析 SQL 全部以文本块手写，按出现次数统计（含 `.sql` 探测脚本）：

| 写法 | 次数 |
| --- | --- |
| `COALESCE`/`IFNULL` | 899 |
| `LEFT JOIN` | 494 |
| `CASE WHEN` | 440 |
| `UNION ALL` | 245 |
| `WITH`（CTE） | 184 |
| 窗口 `OVER (...)` | 35 |
| `ROLLUP`/`GROUPING SETS` | 0 |

突出的痛点：

- 动态条件靠 `String.formatted` 和 `{{占位符}}` 替换拼接 SQL，有注入风险，也难以阅读。
- 可选过滤写成 `(:flag = FALSE OR col = :value)`，影响执行计划。
- 业务口径（如十六个期间金额字段求和、客户端和公司代码过滤、物料号归一化）在多个查询之间复制粘贴。
- 列名是 SAP 的缩写（`MATNR`、`MFRPN` 等），没有编译期检查。

使用方不需要实体跟踪、导航属性、级联保存这些 ORM 能力。另一个 Java 8 的使用方以 CRUD 和列表过滤为主，分析需求很少，不在本库的目标范围内。

## 决定

1. 新开仓库，不依赖 EasyQuery，也不沿用其扩展机制。
2. Java 最低 17，TypeScript 最低 6.0。TS 开发使用 7.x 的原生编译器，CI 同时用 6.0 和 7.x 检查类型。
3. 不使用注解处理器（APT）。
4. 定位为分析查询构建器，不是完整 ORM：
   - 首批要支持的能力：
     - 查询结构：JOIN、CTE、子查询、`EXISTS`/`IN`、`UNION`、分组和 `HAVING`、窗口函数；
     - 表达式：`CASE`、`COALESCE`、`NULLIF`，以及常用的字符串和日期函数；
     - 条件与参数：可选条件、参数绑定、`IN` 列表分批；
     - 结果映射到 Java record；
     - 带结果类型的原生 SQL 片段，作为兜底。
   - 暂不做：实体跟踪、导航和级联、DDL 和迁移；写入以后再评估。
5. 内置功能和扩展要写法一致：
   - 函数是带泛型约束的静态方法，用户自定义的函数和内置函数写法相同；
   - 方言是开放对象，函数渲染按"函数 + 方言"注册，不使用写死的方言枚举分支；
   - 扩展采用组合，不依赖继承基类、再覆写受保护方法。
6. 执行层只依赖连接提供者接口，不依赖 Spring。另提供一个 Spring 适配模块，通过 `DataSourceUtils` 取连接，从而和 MyBatis-Plus 等框架加入同一个 `@Transactional` 事务；它只依赖 spring-jdbc，Spring Boot 2.7、3、4 都能使用。

## 设计方向（待原型验证）

### Java

- 表声明是普通类，只写查询用到的列。
- record 作为行类型，同时用于最终结果和 CTE 的中间行。
- 构造器引用用来做带类型的投影，方法引用用来从 CTE 中取列。
- 业务口径就是普通的 Java 方法，返回条件、表达式或 CTE。

```java
public final class Lips extends Table<Lips> {
    public final Col<String> client = col("MANDT", text(3));
    public final Col<String> material = col("MATNR", text(40));
    public final Col<BigDecimal> quantity = col("LFIMG", decimal(13, 3));
    public Lips(String alias) { super("LIPS", alias, Lips::new); }
}

record Material(String client, String material, String partNumber) {}
record Delivery(String partNumber, BigDecimal quantity) {}

Cte<Material> scope = cte("material_scope",
    select(Material::new, ma.client, ma.material, ma.partNumber).distinct()
        .from(ma)
        .where(activeMaterial(ma))
        .whereIf(model != null, () -> ma.model.eq(model)));

Query<Delivery> deliveries = select(Delivery::new, scope.get(Material::partNumber), sum(l.quantity))
    .from(scope)
    .join(l, l.client.eq(scope.get(Material::client)), l.material.eq(scope.get(Material::material)))
    .where(l.createdOn.between(start, end))
    .groupBy(scope.get(Material::partNumber));
```

编译期会检查三件事：
- `Delivery::new` 的参数类型与投影表达式逐个对应；
- `scope.get(Material::partNumber)` 得到 `Expr<String>`；
- `sum` 只接受数值表达式。

需要原型验证的问题：

- **方法引用怎么解析成列名**：`scope.get(Material::partNumber)` 要靠 `SerializedLambda` 才能还原出列名。这是 MyBatis-Plus `LambdaQueryWrapper` 使用的技术，但它依赖反射，在 GraalVM 下需要额外配置。备选方案是为每个 CTE 写一个显式的形状类。
- **投影的参数个数**：构造器引用投影需要按参数个数提供重载，重载由仓库内的脚本一次性生成，不对使用方生成代码。
- **NULL 的承接**：record 组件要用包装类型才能承接 SQL NULL；遇到基本类型组件时如何处理需要定义清楚。
- **渲染方式**：Java 17 的 switch 模式匹配仍是预览特性，渲染器使用 sealed 节点加 visitor 或 `instanceof` 判断。

### TypeScript

- 前身的 TS ORM 采用无生成器 schema、不可变查询和带类型的 codec，可以作为起点。
- 需要重构的是方言模型：
  - 改为开放的方言对象，方言能力进入类型参数，例如不支持 `RETURNING` 的方言调用 `returning()` 会编译失败；
  - 第三方方言和函数通过模块扩充（module augmentation）注册。

### 仓库结构（暂定）

Java 和 TypeScript 各自独立构建，另有一份共享的规格：

```text
java/         Maven 多模块：core、各方言、spring 适配
typescript/   pnpm 包
spec/         方言能力矩阵与金样例：同一查询在各方言下的预期 SQL、参数与实库结果，两端测试共同读取
```

当前根目录仍是单模块 Maven 工程，等到第一批实际代码时再调整为上述结构。

## 代价与风险

- 这实际上是一个新产品，工作量明显大于继续在前身仓库上修补。范围必须守住"查询"，不能逐步滑向完整 ORM。
- Java 没有映射类型和条件类型，按方言能力区分可用 API 只能通过方言专属的入口类型或静态函数来做，做不到 TS 那样完全在编译期检查。
- 不用 APT，表声明需要手写。SAP 宽表在单个查询里通常只用 5 到 15 列，只声明用到的列反而更清晰。

## 待定事项

1. 是否允许一个独立的生成工具（CLI 或 Maven 插件，不是 APT）从 HANA 目录生成表声明源码，生成后提交到仓库？
2. TypeScript 是与 Java 同步推进，还是 Java 先行？
3. 前身仓库是否冻结在当前状态？
4. 方言顺序：HANA、DuckDB 首发，PostgreSQL、MySQL 其次，是否确认？

## 下一步

用实际使用方的三类查询写 Java API 原型：
- CTE + `EXISTS` + 动态条件；
- 口径复用 + 可选过滤；
- 窗口函数或 `QUALIFY`。

原型用来验证四点：泛型投影、CTE 取列、口径复用，以及 HANA 和 DuckDB 的渲染。验证通过后，再确定公共 API 和模块划分。

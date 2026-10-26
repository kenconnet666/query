# 0019 映射再省事一点：流程引擎用下来的几处

- 状态：讨论中（2026-10-26 提出，待用户定）。第 1、2 条已做（PR #27、#28），第 3、4、6 条是提案，第 5 条（DTO 当表）待用户选方向
- 起因：flow 仓库的默认存储（`java/flow-core` 的 `store/query`）用 query 读写十张表。用户要求"少用 Object、多用映射到实体类的写法，
  代码尽量简单"，存储改写时碰到几处 query 不够顺手的地方，记在这里，按需要先后做。

## 现在的写法（flow 改写之后）

```java
@Table("flow_instance")
interface Instance extends Tenanted, Touched {
    @Id @Generated @Column("id") ColBigint<Long> id();
    @Column(value = "variables", converter = Columns.VariablesJson.class) ColClob<Variables> variables();
    @Column(value = "started_at", converter = Columns.Utc.class) ColTimestamp<Instant> startedAt();
    // ……
}

record InstanceRecord(Long id, String processKey, …, Variables variables, RunState state, Instant startedAt, …) { }

db.optional(from(Instance.class).where(r -> r.id().eq(id)).selectAs(InstanceRecord.class));   // 读：直接是引擎的记录
List<Long> keys = db.insertKeys(insertInto(Instance.class).values(record));               // 写：记录原样插入，取回自增的编号
db.execute(update(Instance.class).byId(record));                                            // 整行写回
```

租户与修改时间用 `Config.filter` + `onInsert` / `onUpdate` 统一处理，存储里不再写。整体已经比原来（每张表一个 Row 记录、一个个字段换编号与时间）短了一半。

## 1. 列的值类型可以带泛型（已做，PR #27）

`ColClob<Map<String, Object>>`、`ColClob<List<Line>>` 以前报"must declare its value type"；现在取原始类，同转换器的类型参数。

## 2. selectAs 里 Map、List、值对象类型的属性，同名的是一列时按列取（已做，PR #28）

以前这些类型一律当对象树的分支去找关系；现在同名的是一列（转换器存成 JSON）就按列取。

## 3. 提案：通用的 JSON 列（`@JsonColumn`）

**现状**：每种存成 JSON 的类型要一个转换器类（flow 里有 5 个：表单值、流程变量、运行状态、当前节点、摘要）。

**做法**：query 提供一个通用转换器，按列声明的 Java 类型读写：

```java
@Column("variables") @JsonColumn ColClob<Variables> variables();
@Column("current") @JsonColumn ColClob<List<CurrentNode>> current();   // 元素类型取自泛型参数
```

- 写：wire 的 `Wire.encode` + `Json.write`（record 取组件，Map / List 原样）。
- 读：wire 新增**按类型还原**（`Wire.decode(Type, tree)`）：record 按组件名、Map / List 按泛型参数、标量同 `Wire.parse`；
  值类（如 `Variables`）认一个静态的 `of(Map)` 工厂。
- TS 一侧：`json<T>()` 列类型，读写用 `JSON.parse` / `toWire`。
- MySQL 列是 `JSON`，PostgreSQL 是 `jsonb`（绑定参数要 `?::jsonb`，在方言里处理），HANA 是 `NCLOB`，SQLite 是 `TEXT`。

**代价**：wire 多一个按类型还原的入口（约两三百行，两端）；PG 的 jsonb 绑定要在方言里加转换。**收益**：每种 JSON 不用再写转换器类。

## 4. 提案：插入后拿回带编号的记录

**现状**：`insertKeys` 只给编号的列表，记录要自己再拼一遍（flow 里待办的记录 22 个字段，插入后重新 new 一次只为换上编号）。

**做法**：`db.insertReturning(insertInto(T).values(rows))` 返回同类型的行，`@Generated` 的属性换成生成的值：record 用规范构造器重建，
可变类直接设属性。多行时按顺序对应。

**代价**：小（`MappingPlan` 已经能按属性名构造 record）。**收益**：存储里的"插入后重拼一遍"整段去掉。

## 5. 待用户定：DTO 当表，一份声明（2026-10-26 用户提出"考虑加 DTO 存储"）

**现状**：Java 一张表写两遍——表接口（列、转换器、关系，用来写类型安全的查询）和行记录（属性同名同类型，用来读写）。
flow 的待办表两边各 26 个。TS 一侧没有这个问题：`@table` 装饰的类既是表也是行的类型（映射类型把字段在查询里变成列）。
Java 的记录访问器返回值、不返回列，所以 2.0 选了"表是接口"（0006 的取舍）。

| 方向 | 写法 | 好处 | 代价 |
| --- | --- | --- | --- |
| **A. DTO 当表，编译时生成表接口（推荐）** | 在 record 或类上写 `@Table`、`@Id`、`@Column(converter=…)`；注解处理器生成同包的 `XxxTable` 接口（就是现在手写的那个）。读写用 DTO：`selectAs`、`insertInto(…).values(dto)`、`update(…).byId(dto)`；查询条件用生成的接口 | 一份声明；与 TS 一侧（类即表）一致；生成的接口与现在手写的完全相同，查询写法不变，已有的接口表照常可用 | 多一个注解处理器（同 Lombok、EasyQuery 的做法，IDEA 与 Maven 都直接支持）；推翻 2.0"表声明成类或 record 会报错"的规定 |
| B. 运行时映射 + 方法引用写条件 | 不改 DTO：`Mapping.of(WorkItem.class).table("flow_work_item")…`；条件写成 `q.eq(WorkItem::state, "open")` | 能映射加不了注解的类（别的模块的 record） | 等于第二套写法，表达式、函数、关系都要再做一遍，类型安全弱 |
| C. 保持两份 | 表接口管查询，DTO 管读写，`selectAs` 按名字对上 | 不动 | 重复的列名与类型一直在 |

**A 的样子**：

```java
@Table("flow_work_item")
public record WorkItemRow(
    @Id @Generated Long id,
    @Column("instance_id") Long instanceId,
    @Column("state") String state,
    @Column(value = "created_at", converter = Utc.class) Instant createdAt,
    …) { }
// 编译时生成 WorkItemRowTable（@Table、继承 Entity、每个组件一个 Col 方法，注解原样带过去）

db.list(from(WorkItemRowTable.class).where(w -> w.state().eq("open")).selectAs(WorkItemRow.class));
List<WorkItemRow> saved = db.insertReturning(insertInto(WorkItemRowTable.class).values(row));   // 第 4 条
```

- 生成的接口名默认 `原名 + Table`，可以在 `@Table(interfaceName = …)` 改；关系（`@ManyToOne`、`@OneToMany`）写在 DTO 的方法上，原样生成进接口。
- 计算列、`default` 方法这类只有接口能写的，仍然可以手写一个接口继承生成的接口。
- 注解处理器只读注解、生成源码，不在运行时做事；没有引入它的项目照旧手写接口。

**B 的样子**（对照用）：不生成代码，在运行时登记"DTO 的哪个属性是哪一列"，写条件时用 DTO 的 getter 方法引用指列（MyBatis-Plus 的
`LambdaQueryWrapper`、EasyQuery 的 lambda 写法就是这一类）：

```java
Mapping<WorkItem> items = Mapping.of(WorkItem.class).table("flow_work_item").id(WorkItem::id).column(WorkItem::assigneeId, "assignee_id");
db.list(from(items).where(q -> { q.eq(WorkItem::state, "open"); q.isNull(WorkItem::assigneeId); }));
```

- 好处：DTO 一行注解都不用加，别的模块里的 record 也能映射。
- 难处：getter 返回的是值，没有类型化的"列"可以接着点方法，所以 `contains`、`plus`、日期函数、条件树、关系这些都得换成
  `q.like(WorkItem::title, …)` 这样一个个包一层，等于再做一套写法；编译期也拦不住"给文字列用日期函数"这类错。

**对 flow 的影响**：`store/query` 下的 `FlowTables`（十张表的接口）不再手写。但模型包（`flow-model`）里的 `WorkItem`、`FlowEvent`
不依赖 query，不能加 query 的注解，所以要么把 query 的注解拆成一个只有注解、零依赖的小包（`query-annotations`）给模型包用，
要么存储里另写一份行记录再 `selectAs` 成模型包的记录（又回到两份）。前者更彻底，建议一起做。

**和第 3、4 条的关系**：第 4 条（插入后拿回带编号的 DTO）在 A 之下最自然；第 3 条（通用 JSON 列）与 A 无关，照常做。

## 6. 提案：从 DTO 拷贝属性到 SET（`set(dto)`）

**现状**：带条件的更新（认领、处理这类"只在打开着时改到"）要在 SET 块里一行一列地写：

```java
update(WorkItemTable.class).set(r -> {
    r.state().to(DONE);
    r.outcome().to(c.outcome());
    r.comment().to(c.comment());
    r.completedBy().to(c.userId());
    r.completedByName().to(c.userName());
    r.completedAt().to(c.at());
}).where(r -> { r.id().eq(id); r.state().eq(OPEN); });
```

**做法**：`set(Object dto)` 把 DTO 里每个属性按名字 SET 到同名的列——同 `where(Object filter)` 按名字对条件对象、
`insertInto().values(row)` 按名字对行。可以和赋值块连着写；`skipEmpty()` 下空的属性省略；属性找不到同名的列时报错；转换器照常生效。

```java
record Completion(String state, String outcome, String comment, String completedBy, String completedByName, Instant completedAt) { }

update(WorkItemTable.class)
    .set(completion)                                  // 把这份处理结果写进去
    .set(r -> r.rev().to(r.rev().plus(val(1))))       // 版本加一
    .where(r -> { r.id().eq(id); r.state().eq(OPEN); });   // 只在打开着时
```

- TS 一侧的 `set` 本来就收对象（`set((l) => ({ unit: "KG" }))`），再加一个直接收对象的重载，两端写法就一致了。
- 只是"属性 → 同名列"的拷贝，不做别的：不是 BeanUtils 那种对象之间的拷贝，表接口本身也不会因此变成数据。

**代价**：小（与 `insertInto().values(row)` 共用按名字对列的代码）。**收益**：条件更新写成"一个 DTO + 一行条件"，读起来就是一句话。

## 关于"把 DTO 的属性拷贝到接口"

Java 里接口不能"继承"一个 record 的属性声明——record 的访问器返回值、接口的列方法返回 `Col`，类型不同，语言层面没有办法把一份声明
变成另一份。要只写一份，只有两条路：编译时生成（第 5 条的 A），或运行时映射加另一套条件写法（第 5 条的 B）。
在不生成代码的前提下，能省掉的是"值的拷贝"：读用 `selectAs`（列 → 属性）、插入用 `values(row)`、整行更新用 `byId(row)`、
部分更新用第 6 条的 `set(dto)`（属性 → 列）。这四个合起来，存储里就不再有逐个字段的赋值。

## 建议的先后

1. 第 5 条选 A（DTO 当表 + 注解处理器生成接口），连同拆出零依赖的 `query-annotations`：一次解决重复，Java 与 TS 写法对齐。
2. 第 4 条（插入后拿回带编号的 DTO）与第 6 条（set(dto)）跟着 A 做，都小；不选 A 也可以单独做。
3. 第 3 条（通用 JSON 列，两端，含 PG 的 jsonb 绑定）。

用户选了之后，按选的方向改 API 文档与迁移指南，两端一起做，flow 的存储随后换过去。

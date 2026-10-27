# 0019 映射再省事一点：流程引擎用下来的几处

- 状态：第 1、2、4、5 条已做（PR #27、#28 与 `feat/dto-into-sql`），第 3 条是提案
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
- 读：json-wire 新增**按类型还原**（`Wire.decode(Type, tree)`）：record 按组件名、Map / List 按泛型参数、标量同 `Wire.parse`；
  值类（如 `Variables`）认一个静态的 `of(Map)` 工厂。
- TS 一侧：`json<T>()` 列类型，读写用 `JSON.parse` / `toWire`。
- MySQL 列是 `JSON`，PostgreSQL 是 `jsonb`（绑定参数要 `?::jsonb`，在方言里处理），HANA 是 `NCLOB`，SQLite 是 `TEXT`。

**代价**：json-wire 多一个按类型还原的入口（约两三百行，两端）；PG 的 jsonb 绑定要在方言里加转换。**收益**：每种 JSON 不用再写转换器类。

## 4. 插入后拿回带编号的记录（已做）

**现状**：`insertKeys` 只给编号的列表，记录要自己再拼一遍（flow 里待办的记录 22 个字段，插入后重新 new 一次只为换上编号）。

**做法**：`db.insertReturning(insertInto(T).values(rows))` 返回同类型的行，`@Generated` 的属性换成生成的值：record 用规范构造器重建，
可变类直接设属性。多行时按顺序对应。

**代价**：小（`MappingPlan` 已经能按属性名构造 record）。**收益**：存储里的"插入后重拼一遍"整段去掉。

## 5. 从 DTO 拷贝属性到 SET（`set(dto)`，已做）

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
变成另一份。所以表接口与行记录仍是两份；能省掉的是"值的拷贝"：读用 `selectAs`（列 → 属性）、插入用 `values(row)`、
插入后拿回编号用 `insertReturning`、整行更新用 `byId(row)`、部分更新用 `set(dto)`（属性 → 列）。这几个合起来，存储里就不再有逐个字段的赋值。

## 之后

第 3 条（通用 JSON 列，两端，含 PG 的 jsonb 绑定）等第二个用到 JSON 列的项目出现时再做；flow 的转换器先照现在的写。

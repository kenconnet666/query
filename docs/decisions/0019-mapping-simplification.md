# 0019 映射再省事一点：流程引擎用下来的几处

- 状态：讨论中（2026-10-26 提出，待用户定）。第 1、2 条已做（PR #27、#28），其余是提案
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

## 5. 待讨论：表接口与行记录的重复

**现状**：一张表写两遍——表接口（列、转换器、关系）和行记录（属性同名同类型）。flow 的待办表两边各 26 个。

可以考虑的方向（都不急，先记下）：

| 方向 | 做法 | 代价 |
| --- | --- | --- |
| A. 生成行记录 | `query:pull`（已有，从库生成列接口）再生成同名的 record | 多一步生成；手写的记录（带方法、带别的类型）不适用 |
| B. 行记录上的注解推出表接口 | 编译期处理器（APT）按 record 生成表接口 | 引入注解处理器，构建变复杂 |
| C. 保持两份 | 表接口管 SQL 的类型安全，记录管业务；`selectAs` 已经把两者自动对上 | 无 |

建议先 C：两份各管各的，重复只在列名与类型；等 3、4 做完再看还嫌不嫌重。

## 建议的先后

1. 第 4 条（小、立刻省代码）。
2. 第 3 条（两端都要，带 PG 的 jsonb 绑定）。
3. 第 5 条先不动。

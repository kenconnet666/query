import { columnName, deletedValue, entityMeta, inserts, updates, type EntityMeta, type One, type PropertyMeta, type Table, type WriteMeta } from "../schema/metadata.js";
import { all, bindValue, Column, Cond, Expr, skips, withSkipEmpty } from "../sql/expr.js";
import type { Renderable, Rendered, SqlWriter } from "../sql/writer.js";
import { currentTimestamp, lit, plus } from "../sql/functions.js";
import { filterCondition, type FilterObject } from "../query/filter.js";
import type { Many } from "../query/many.js";
import { applyFiltering, conditionsFor, emitRelations, FILTERING_ON, implicitJoins, renderNamed, unfilteredBy, type AppliedFilters, type Filtering, type Query, type SourceFilter } from "../query/query.js";
import { createRef, ownerOf, RefOwner, tableSource, type Ref } from "../query/source.js";
import type { Clause, JoinWrite, Lock, Upsert } from "../sql/dialect.js";

/** 表实体里的列字段：去掉 `@one`、`@many` 关系字段。 */
export type Columns<T> = { [K in keyof T as T[K] extends Many<any> | One<any> ? never : K]: T[K] };

/** `update(...).set(...)` 回调返回的对象：列字段到新值（值或表达式）。 */
export type Assignments<T> = { readonly [K in keyof Columns<T>]?: Columns<T>[K] | null | Expr<Columns<T>[K]> | Expr<NonNullable<Columns<T>[K]>> };

/** `insertInto(...).values(...)` 的一行：列字段到值，没有的列不写，null 写成 NULL。 */
export type InsertRow<T> = { readonly [K in keyof Columns<T>]?: Columns<T>[K] | null };

/**
 * 写语句：{@link Insert}、{@link Update} 或 {@link Delete}。和查询一样是不可变的值，`db.execute(write)` 执行，
 * `db.render(write)` 查看将要执行的语句和参数。
 */
export abstract class Write {
  /** 渲染出要执行的语句，每条用 writer 给的新写入器；多行插入每行一条（SQL 相同），没有要做的事时为空数组。 */
  abstract render(writer: () => SqlWriter): Rendered[];

  /** 期望影响的总行数，undefined 表示不检查；见 `expect(n)` 和 `byId(row)`。 */
  abstract get expected(): number | undefined;
}

/** 插入 table：`insertInto(Lips).values(rows)` 或 `.from(query)`，见 {@link Insert}。 */
export function insertInto<T>(table: Table<T>): Insert<T> {
  return new Insert(table, [], [], undefined);
}

/** 更新 table：`update(Lips).set((l) => ({ unit: "KG" })).where(...)`，见 {@link Update}。 */
export function update<T>(table: Table<T>): Update<T> {
  return new Update(Target.of(table), []);
}

/** 从 table 删除：`deleteFrom(Lips).where(...)`，见 {@link Delete}。 */
export function deleteFrom<T>(table: Table<T>): Delete<T> {
  return new Delete(Target.of(table));
}

/**
 * 写入时自动填充的一列（审计字段），放进 `DatabaseOptions.fills`：插入时填 onInsert 的列，更新（包括按主键更新、
 * 插入或更新的更新部分、逻辑删除）时填 onUpdate 的列。按表实体的父类匹配，值总是覆盖行里的值。
 */
export interface Fill {
  readonly type: abstract new (...args: never[]) => unknown;
  readonly column: (ref: never) => Expr<unknown>;
  /** 每条语句取一次值；返回表达式时原样写出（例如 `currentTimestamp()`），否则按列的类型绑定。 */
  readonly value: () => unknown;
  readonly onInsert: boolean;
  /** 插入时逐行生成：只填行里为 null 或没有这个字段的行，每行调用一次 value，见 {@link generate}。 */
  readonly generate?: boolean;
}

/**
 * 插入 type（或它的子类）的表时逐行生成一列的值，只填行里为 null 或没有这个字段的行，例如主键：
 * `generate(Keyed, (k) => k.id, () => randomUUID())`。生成的值不写回行对象，之后还要用这个值时在代码里先生成。
 */
export function generate<E, V>(type: abstract new (...args: never[]) => E, column: (ref: Ref<E>) => Expr<V>, value: () => NonNullable<V>): Fill {
  return Object.freeze({ type, column: column as Fill["column"], value, onInsert: true, generate: true });
}

/** 插入 type（或它的子类）的表时自动填充一列，例如创建时间：`onInsert(Audited, (a) => a.createdAt, () => now())`。 */
export function onInsert<E, V>(type: abstract new (...args: never[]) => E, column: (ref: Ref<E>) => Expr<V>, value: () => V | Expr<V> | Expr<NonNullable<V>>): Fill {
  return Object.freeze({ type, column: column as Fill["column"], value, onInsert: true });
}

/** 更新 type（或它的子类）的表时自动填充一列，例如修改人：`onUpdate(Audited, (a) => a.updatedBy, () => user())`。 */
export function onUpdate<E, V>(type: abstract new (...args: never[]) => E, column: (ref: Ref<E>) => Expr<V>, value: () => V | Expr<V> | Expr<NonNullable<V>>): Fill {
  return Object.freeze({ type, column: column as Fill["column"], value, onInsert: false });
}

/** 写入器上的数据库实例里，对 ref 这张表生效的一类填充（不含逐行生成的）：列和值。 */
function fillsFor(w: SqlWriter, ref: object, insert: boolean): (readonly [Column<unknown>, Expr<unknown>])[] {
  return matchingFills(w, ref, insert, false).map(([column, f]) => {
    const value = f.value();
    return [column, value instanceof Expr ? value : bindValue(column.type, value)] as const;
  });
}

/** 对 ref 这张表生效的一类填充和它的列；generated 为 true 时只取逐行生成的。 */
function matchingFills(w: SqlWriter, ref: object, insert: boolean, generated: boolean): (readonly [Column<unknown>, Fill])[] {
  const entity = ownerOf(ref).source.entity;
  const fills = (w.settings.fills as readonly Fill[] | undefined) ?? [];
  return fills
    .filter((f) => f.onInsert === insert && (f.generate === true) === generated && entity !== undefined && (entity === f.type || entity.prototype instanceof f.type))
    .map((f) => {
      const column = f.column(ref as never);
      if (!(column instanceof Column) || column.owner !== ownerOf(ref)) throw new Error(`A fill for ${f.type.name} must return one of its columns`);
      return [column as Column<unknown>, f] as const;
    });
}

/** 写出 `schema.表名`。 */
function tableName(w: SqlWriter, meta: EntityMeta): void {
  w.table(meta.table!.schema, meta.table!.name);
}

/**
 * INSERT 语句。行是普通对象（或表类的实例），字段对应表的列：取第一行里值不为 undefined 的字段，
 * 其余行必须有相同的字段；null 写成 NULL，`@generated` 的列总是不写；字段不是表的列时报错。
 * 多行在一个事务里逐行执行。不可变，每个方法返回新的语句。
 */
export class Insert<T> extends Write {
  readonly #table: Table<T>;

  /** 插入的表。 */
  get table(): Table<T> {
    return this.#table;
  }
  readonly #rows: readonly object[];
  readonly #columns: readonly PropertyMeta[];
  readonly #source: Query<unknown> | undefined;
  /** 插入或更新：冲突的键和冲突时更新的列（undefined 是插入的列里除了键以外的全部，空数组是什么也不做）。 */
  readonly #conflict: { readonly keys: readonly string[]; readonly updates: readonly string[] | undefined } | undefined;
  /** 是否套用数据库实例的写入填充（审计列）。 */
  readonly #fills: boolean;

  /** 由 {@link insertInto} 和各修改方法调用。 */
  constructor(
    table: Table<T>,
    rows: readonly object[],
    columns: readonly PropertyMeta[],
    source: Query<unknown> | undefined,
    conflict?: { readonly keys: readonly string[]; readonly updates: readonly string[] | undefined },
    fills = true,
  ) {
    super();
    tableSource(table);   // 校验它是带 @table 的表
    this.#table = table;
    this.#rows = rows;
    this.#columns = columns;
    this.#source = source;
    this.#conflict = conflict;
    this.#fills = fills;
  }

  /** 不套用数据库实例的写入填充（审计列），例如迁移数据时保留行里原来的创建人和时间。 */
  withoutFills(): Insert<T> {
    return new Insert(this.#table, this.#rows, this.#columns, this.#source, this.#conflict, false);
  }

  /**
   * 插入或更新：插入的行与已有的行在 keys 上冲突时改为更新，例如 `insertInto(Doc).values(row).onConflict((d) => d.code).update()`。
   * HANA 写成 MERGE，PostgreSQL 写成 `ON CONFLICT (键)`（键要有主键或唯一约束），MySQL 写成 `ON DUPLICATE KEY UPDATE`
   * （由表的主键和唯一索引判断冲突）。不写 keys 时按 `@id` 的列。键必须是插入的列。
   */
  onConflict(keys?: (t: Ref<T>) => Expr<unknown> | readonly Expr<unknown>[]): Conflict<T> {
    if (keys !== undefined) return new Conflict(this, this.#names(keys(createRef(tableSource(this.#table))), "onConflict(...)"));
    const ids = entityMeta(this.#table).writes.ids;
    if (ids.length === 0) throw new Error(`${this.#table.name} has no @id; write onConflict((t) => [...])`);
    return new Conflict(this, ids);
  }

  /** 带上插入或更新设置的副本；由 {@link Conflict} 调用。 */
  withConflict(keys: readonly string[], updates: ((t: Ref<T>) => Expr<unknown> | readonly Expr<unknown>[]) | readonly string[] | undefined): Insert<T> {
    if (this.#source !== undefined) throw new Error("onConflict(...) needs values(...), not from(query)");
    const names = typeof updates === "function" ? this.#names(updates(createRef(tableSource(this.#table))), "update(...)") : updates;
    const readOnly = entityMeta(this.#table).writes.readOnly;
    for (const n of names ?? []) if (readOnly.includes(n)) throw new Error(`onConflict(...).update(...) cannot write @readOnly ${n}`);
    return new Insert(this.#table, this.#rows, this.#columns, undefined, { keys, updates: names }, this.#fills);
  }

  /** 表达式都是这张表的列，返回它们的字段名；what 用于报错。 */
  #names(exprs: Expr<unknown> | readonly Expr<unknown>[], what: string): string[] {
    return (Array.isArray(exprs) ? exprs : [exprs]).map((e) => {
      if (!(e instanceof Column) || !(e.owner instanceof RefOwner) || e.owner.source.entity !== this.#table) throw new Error(`${what} takes columns of ${this.#table.name}`);
      return e.column.name;
    });
  }

  /** 追加要插入的行。 */
  values(...rows: readonly InsertRow<T>[]): Insert<T> {
    if (this.#source !== undefined) throw new Error("insertInto(...) already takes its rows from(query)");
    const all = [...this.#rows, ...rows] as object[];
    if (all.length === 0) return this;
    const columns = this.#columns.length > 0 ? this.#columns : this.#columnsOf(all[0]!);
    const names = columns.map((c) => c.name).join(",");
    for (const row of all) {
      if (row === null || typeof row !== "object") throw new TypeError("insertInto(...).values(...) takes objects");
      const own = this.#columnsOf(row).map((c) => c.name).join(",");
      if (own !== names) throw new Error(`insertInto(...) rows must have the same fields; got ${names} and ${own}`);
    }
    return new Insert(this.#table, all, columns, undefined, this.#conflict, this.#fills);
  }

  /** INSERT ... SELECT：查询的每一列按名字对应表的同名列，例如 `select((s) => ({ delivery: s.delivery }))`。 */
  from(query: Query<unknown>): Insert<T> {
    if (this.#rows.length > 0) throw new Error("insertInto(...) already has values(...)");
    if (this.#conflict !== undefined) throw new Error("onConflict(...) needs values(...), not from(query)");
    const writes = entityMeta(this.#table).writes;
    for (const label of query.labels) {
      this.#column(label, "the query's column");
      if (!inserts(writes, label)) throw new Error(`from(query) writes ${label}, which is @readOnly or @generated`);
    }
    return new Insert(this.#table, [], [], query, undefined, this.#fills);
  }

  /** 插入不检查影响的行数。 */
  get expected(): number | undefined {
    return undefined;
  }

  /** `@generated` 的列，`db.insertKeys` 取回它的值；表上没有、或用的是 {@link from} 时报错。 */
  generated(): PropertyMeta {
    const name = entityMeta(this.#table).writes.generated;
    if (name === undefined) throw new Error(`${this.#table.name} has no @generated column; use db.execute(insert) instead`);
    if (this.#source !== undefined) throw new Error("insertKeys(...) needs values(...), not from(query)");
    return entityMeta(this.#table).property(name)!;
  }

  /** 每行一条 `INSERT INTO 表 (列, ...) VALUES (?, ...)`；INSERT ... SELECT 是一条。 */
  render(writer: () => SqlWriter): Rendered[] {
    const source = this.#source;
    if (source !== undefined) {
      const w = writer();
      this.#head(w, source.labels.map((l) => this.#column(l, "the query's column")));
      w.sql("\n");
      renderNamed(w, source);
      return [w.statement()];
    }
    return this.#rows.map((row) => {
      const w = writer();
      const values = this.#values(w, row);
      if (this.#conflict !== undefined) {
        w.dialect.upsert(w, this.#upsert(w, values, this.#conflict));
        return w.statement();
      }
      this.#head(w, values.map(([c]) => c));
      w.sql(" VALUES (").join(values.map(([, v]) => v), ", ").sql(")");
      return w.statement();
    });
  }

  /**
   * 一行要写的列和值：行里的字段，加上数据库实例的插入时填充（覆盖同名的字段），以及行里没有逻辑删除列时它的"未删除"值
   * （记录删除时间的列未删除就是 NULL，不用写）。
   */
  #values(w: SqlWriter, row: object): [PropertyMeta, Expr<unknown>][] {
    const out: [PropertyMeta, Expr<unknown>][] = this.#columns.map((c) => [c, bindValue(c.type, (row as Record<string, unknown>)[c.name] ?? null)]);
    const put = (column: PropertyMeta, value: Expr<unknown>) => {
      const i = out.findIndex(([c]) => c.name === column.name);
      if (i >= 0) out[i] = [column, value];
      else out.push([column, value]);
    };
    const ref = createRef(tableSource(this.#table)) as object;
    for (const [column, value] of this.#fills ? fillsFor(w, ref, true) : []) put(column.column as PropertyMeta, value);
    for (const [column, f] of matchingFills(w, ref, true, true)) {
      const property = column.column as PropertyMeta;
      if (((row as Record<string, unknown>)[property.name] ?? null) === null) put(property, bindValue(column.type, f.value()));
    }
    const meta = entityMeta(this.#table);
    const logic = meta.writes.logicDelete;
    if (logic !== undefined && !logic.timestamp && !out.some(([c]) => c.name === logic.property)) {
      const column = meta.property(logic.property)!;
      put(column, bindValue(column.type, logic.active));
    }
    return out;
  }

  /** 一行的插入或更新交给方言的内容：列名按命名策略，键必须是插入的列；更新时的填充作为另外更新的列。 */
  #upsert(w: SqlWriter, values: readonly (readonly [PropertyMeta, Expr<unknown>])[], conflict: { readonly keys: readonly string[]; readonly updates: readonly string[] | undefined }): Upsert {
    const names = values.map(([c]) => c.name);
    for (const k of conflict.keys) if (!names.includes(k)) throw new Error(`onConflict(...) key '${k}' must be one of the inserted columns ${names.join(", ")}`);
    const meta = entityMeta(this.#table);
    const name = (field: string) => columnName(meta.property(field)!, w.naming);
    const assignments = (this.#fills ? fillsFor(w, createRef(tableSource(this.#table)) as object, false) : []).map(([c, v]) => [columnName(c.column as PropertyMeta, w.naming), v] as const);
    const assigned = new Set(assignments.map(([c]) => c));
    return {
      table: { render: (x: SqlWriter) => tableName(x, meta) },
      columns: values.map(([c]) => columnName(c, w.naming)),
      values: values.map(([, v]) => v),
      keys: conflict.keys.map(name),
      updates: (conflict.updates ?? names.filter((n) => !conflict.keys.includes(n) && !meta.writes.writeOnce.includes(n))).map(name).filter((c) => !assigned.has(c)),
      assignments,
    };
  }

  /** 写出 `INSERT INTO 表 (列, ...)`。 */
  #head(w: SqlWriter, columns: readonly PropertyMeta[]): void {
    w.sql("INSERT INTO ");
    tableName(w, entityMeta(this.#table));
    w.sql(" (");
    columns.forEach((c, i) => {
      if (i > 0) w.sql(", ");
      w.identifier(columnName(c, w.naming));
    });
    w.sql(")");
  }

  /** 行里要写的列：值不为 undefined 的字段，跳过 `@generated` 和 `@readOnly` 的列。 */
  #columnsOf(row: object): PropertyMeta[] {
    const writes = entityMeta(this.#table).writes;
    return Object.entries(row)
      .filter(([name, value]) => value !== undefined && inserts(writes, name))
      .map(([name]) => this.#column(name, `${this.#table.name} row field`));
  }

  /** 表里名为 name 的列；没有时报错，what 说明 name 来自哪里。 */
  #column(name: string, what: string): PropertyMeta {
    const meta = entityMeta(this.#table);
    const column = meta.property(name);
    if (column === undefined) throw new Error(`${what} '${name}' has no column in ${this.#table.name}; known: ${meta.properties.map((p) => p.name).join(", ")}`);
    return column;
  }
}

/**
 * 写出 `UPDATE 表 别名 SET ... WHERE ...`，SET 里再加上数据库实例的更新时填充（覆盖同名的项）；逻辑删除也用它。
 * statement 用于没有 WHERE 时的报错。
 */
function renderSet(w: SqlWriter, target: Target<unknown>, sets: readonly (readonly [Column<unknown>, Expr<unknown>])[], located: Cond, statement: string): Rendered {
  const all = [...sets];
  for (const fill of target.fills ? fillsFor(w, target.ref as object, false) : []) {
    const i = all.findIndex(([c]) => c.column.name === fill[0].column.name);
    if (i >= 0) all.splice(i, 1);
    all.push(fill);
  }
  if (implicitJoins(w, [ownerOf(target.ref as object)], all.map(([, v]) => v)).size > 0) {
    throw new Error(`${statement} SET reads a related table through navigation; use a scalar subquery or the dialect's join update`);
  }
  w.sql("UPDATE ");
  ownerOf(target.ref as object).renderSource(w);
  w.sql("\nSET ");
  all.forEach(([column, value], i) => {
    if (i > 0) w.sql(", ");
    w.identifier(columnName(column.column as PropertyMeta, w.naming)).sql(" = ").render(value);
  });
  target.renderWhere(w, statement, located);
  return w.statement();
}

/** {@link Insert.onConflict} 之后选择冲突时做什么。 */
export class Conflict<T> {
  constructor(
    readonly insert: Insert<T>,
    readonly keys: readonly string[],
  ) {}

  /** 冲突时把插入的列（键以外的全部）更新为插入的值；给出 columns 时只更新这些列，例如 `update((d) => [d.title])`。 */
  update(columns?: (t: Ref<T>) => Expr<unknown> | readonly Expr<unknown>[]): Insert<T> {
    return this.insert.withConflict(this.keys, columns);
  }

  /** 冲突时什么也不做，保留已有的行。 */
  doNothing(): Insert<T> {
    return this.insert.withConflict(this.keys, []);
  }
}

/**
 * 加锁读：`from(Task).where(...).with(forUpdate()).select(...)` 写成 `... FOR UPDATE`，读到的行在事务结束前别人改不了；
 * 要在 `db.transaction(...)` 里执行。lock 指定遇到被锁住的行时不等待（"nowait"）或跳过（"skipLocked"，多个工作进程各自领取任务时用）。
 */
export function forUpdate(lock: Lock = "wait"): Clause {
  return { position: "end", render: (w) => void w.dialect.lock(w, lock) };
}

/** UPDATE 与 DELETE 共用的部分：目标表的一次出现、WHERE 条件、skipEmpty、全局过滤器的开关，以及执行前的检查。不可变；不对外导出。 */
export class Target<T> {
  constructor(
    readonly table: Table<T>,
    readonly ref: Ref<T>,
    readonly conditions: readonly Cond[],
    readonly skipping: boolean,
    /** 全局过滤器和逻辑删除的开关。 */
    readonly filtering: Filtering,
    /** 是否套用数据库实例的写入填充。 */
    readonly fills: boolean,
    readonly everyRow: boolean,
    readonly expected: number | undefined,
  ) {}

  /** 以 table 的一次新出现开始。 */
  static of<T>(table: Table<T>): Target<T> {
    return new Target(table, createRef(tableSource(table)), [], false, FILTERING_ON, true, false, undefined);
  }

  /** 改动部分字段后的副本。 */
  with(change: Partial<Pick<Target<T>, "conditions" | "skipping" | "filtering" | "fills" | "everyRow" | "expected">>): Target<T> {
    const c = { ...this, ...change };
    return new Target(this.table, this.ref, c.conditions, c.skipping, c.filtering, c.fills, c.everyRow, c.expected);
  }

  /** 追加 WHERE 条件：回调或条件对象。 */
  where(cond: ((t: Ref<T>) => Cond) | FilterObject): Target<T> {
    const c = typeof cond === "function" ? withSkipEmpty(this.skipping, () => (cond as (t: Ref<T>) => Cond)(this.ref)) : filterCondition([this.ref], cond);
    if (c == null) throw new TypeError("A condition is missing; return Cond.EMPTY for no condition");
    return this.with({ conditions: [...this.conditions, c] });
  }

  /** 期望影响 rows 行。 */
  expect(rows: number): Target<T> {
    if (!Number.isSafeInteger(rows) || rows < 0) throw new RangeError("expect needs a non-negative whole row count");
    return this.with({ expected: rows });
  }

  /**
   * 写出 WHERE：全局过滤器的条件加 where 的条件。where 的条件全部为空（没写，或都被 skipEmpty 省略）又没有 all() 时报错，
   * 免得一条语句改掉或删掉整张表；全局过滤器的条件不算，因为它们只是把范围限定在当前租户等。
   * located 是 byId 按主键（和版本）定位一行的条件。
   */
  renderWhere(w: SqlWriter, statement: string, located: Cond = Cond.EMPTY): void {
    const own = all(...this.conditions, located);
    if (own.isEmpty && !this.everyRow) throw new Error(`${statement} without where(...) would touch every row of ${this.table.name}; add .all() if that is intended`);
    const filters = this.filters(w);
    const where = all(...conditionsFor(filters, this.ref as object), this.#navigated(w, own, filters));
    if (!where.isEmpty) w.sql("\nWHERE ").render(where);
  }

  /** 这条语句套用的过滤器：数据库实例配置的，再按 `filtering` 取舍；全部关闭时为 undefined。 */
  filters(w: SqlWriter): AppliedFilters | undefined {
    return applyFiltering(w.settings.filters as readonly SourceFilter[] | undefined, this.filtering);
  }

  /**
   * own 经过导航关系读到别的表时，包成 `EXISTS (SELECT 1 FROM 单行 LEFT JOIN 关联表 ON ... WHERE own)`；没有时原样返回。
   * UPDATE、DELETE 不能像查询那样直接 LEFT JOIN，三个库的联表写法又各不相同；从单行的种子出发再 LEFT JOIN，
   * 没有关联行时关联列为 NULL，与查询里导航的语义一致。
   */
  #navigated(w: SqlWriter, own: Cond, filters: AppliedFilters | undefined): Cond {
    if (own.isEmpty) return own;
    const owner = ownerOf(this.ref as object);
    const used = implicitJoins(w, [owner], [own]);
    if (used.size === 0) return own;
    const seed = {};
    return new (class extends Cond {
      render(x: SqlWriter): void {
        x.sql("EXISTS (SELECT 1\nFROM (SELECT 1 AS ").identifier("ONE");
        x.dialect.fromNothing(x);
        x.sql(") ").alias(x.aliasFor(seed, "seed"));
        emitRelations(x, owner, used, filters);
        x.sql("\nWHERE ").render(own).sql(")");
      }
    })();
  }
}

/**
 * UPDATE 语句：`update(Lips).set((l) => ({ unit: "KG", quantity: plus(l.quantity, val("1")) })).where((l) => l.delivery.eq("D1"))`。
 * 没有 where 条件时报错（全表更新要写 `.all()`）；全局过滤器的条件也加进 WHERE。不可变，每个方法返回新的语句。
 */
export class Update<T> extends Write {
  readonly #target: Target<T>;

  /** 更新的表。 */
  get table(): Table<T> {
    return this.#target.table;
  }
  readonly #sets: readonly (readonly [Column<unknown>, Expr<unknown>])[];
  readonly #byId: ById | undefined;

  /** 由 {@link update} 和各修改方法调用。 */
  constructor(target: Target<T>, sets: readonly (readonly [Column<unknown>, Expr<unknown>])[], byId?: ById) {
    super();
    this.#target = target;
    this.#sets = sets;
    this.#byId = byId;
  }

  /**
   * 按主键更新这些行：每行一条 `UPDATE ... SET 其余字段 WHERE 主键 = ?`，表上有 `@version` 时加上
   * `SET version = version + 1` 和 `AND version = ?`；期望每行影响一行，否则 `db.execute` 抛出 UnexpectedRowCountError（乐观锁冲突）。
   * 行必须带主键字段（和版本字段）；开启 skipEmpty 时值为 null 的字段不更新。可以再加 set 和 where。
   */
  byId(...rows: readonly InsertRow<T>[]): Update<T> {
    return new Update(this.#target, this.#sets, ById.of(this.#target.table, rows as readonly object[], "update(...).byId(...)"));
  }

  /** 追加 SET 项：回调返回列字段到新值的对象；skipEmpty 下值为空的项被省略，否则 null 设为 NULL。 */
  set(assign: (t: Ref<T>) => Assignments<T>): Update<T> {
    const target = this.#target;
    const sets = [...this.#sets];
    withSkipEmpty(target.skipping, () => {
      for (const [name, value] of Object.entries(assign(target.ref) as object)) {
        const column = (target.ref as Record<string, unknown>)[name];
        if (!(column instanceof Column) || column.owner !== ownerOf(target.ref as object)) throw new Error(`set(...): ${name} is not a column of ${target.table.name}`);
        if (entityMeta(target.table).writes.readOnly.includes(name)) throw new Error(`set(...): ${target.table.name}.${name} is @readOnly`);
        if (value instanceof Expr) sets.push([column, value]);
        else if (value !== undefined && !skips(value)) sets.push([column, bindValue(column.type, value)]);
      }
    });
    return new Update(target, sets, this.#byId);
  }

  /** 追加 WHERE 条件；多次调用之间是 AND。 */
  where(cond: (t: Ref<T>) => Cond): Update<T>;
  /** 条件对象：每个非空字段一个条件，规则同查询的 `where(filter)`。 */
  where<F extends FilterObject>(filter: F): Update<T>;
  where(cond: ((t: Ref<T>) => Cond) | FilterObject): Update<T> {
    return new Update(this.#target.where(cond), this.#sets, this.#byId);
  }

  /** 之后的 set 与 where 里，值为空的 SET 项和与空值比较的条件被省略；要写在它们之前。 */
  skipEmpty(): Update<T> {
    return new Update(this.#target.with({ skipping: true }), this.#sets, this.#byId);
  }

  /** 不套用数据库实例的全局过滤器，也不加逻辑删除的条件；给出名字时只关闭这些名字的过滤器。 */
  unfiltered(...names: string[]): Update<T> {
    return new Update(this.#target.with({ filtering: unfilteredBy(this.#target.filtering, names) }), this.#sets, this.#byId);
  }

  /** 不加逻辑删除的条件，已删除的行也会被改到；全局过滤器照常。 */
  withDeleted(): Update<T> {
    return new Update(this.#target.with({ filtering: { ...this.#target.filtering, logicDelete: false } }), this.#sets, this.#byId);
  }

  /** 不套用数据库实例的写入填充（审计列），例如迁移数据时保留原来的修改人和时间。 */
  withoutFills(): Update<T> {
    return new Update(this.#target.with({ fills: false }), this.#sets, this.#byId);
  }

  /** 允许没有 where 条件，即更新全表。 */
  all(): Update<T> {
    return new Update(this.#target.with({ everyRow: true }), this.#sets, this.#byId);
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError；用于"比较后更新"的状态流转。 */
  expect(rows: number): Update<T> {
    return new Update(this.#target.expect(rows), this.#sets, this.#byId);
  }

  /**
   * 按 `byId(...)` 的行逐一与修改前的行比较，只 SET 值变了的字段；一行完全没变时不发这一行的语句，也不计入期望的行数。
   * 修改前的行与 byId 的行一一对应，主键和版本必须相等。例如编辑表单提交时：`update(Doc).byId(edited).diffFrom(loaded)`。
   */
  diffFrom(...originals: readonly InsertRow<T>[]): Update<T> {
    if (this.#byId === undefined) throw new Error("diffFrom(...) compares against byId(...) rows; call byId(...) first");
    return new Update(this.#target, this.#sets, this.#byId.diffFrom(originals as readonly object[]));
  }

  /**
   * `byId(...)` 的行里会被写的那些，按原顺序：有 `diffFrom(...)` 时只含值变了的行，否则是全部行；没有 byId 时为空。
   * 执行成功后，表上有版本列时这些行在库里的版本都加了一。
   */
  get changedRows(): readonly object[] {
    return this.#byId?.changed() ?? [];
  }

  /** `expect(n)` 写明的行数；没有写而用了 byId 时是行数（有 diffFrom 时只算有变化的行）。 */
  get expected(): number | undefined {
    if (this.#target.expected !== undefined || this.#byId === undefined) return this.#target.expected;
    return this.#sets.length === 0 ? this.#byId.expected : this.#byId.rows.length;
  }

  /** `UPDATE 表 别名 SET 列 = 值, ... WHERE ...`，byId 时每行一条；没有 SET 项时没有语句。 */
  render(writer: () => SqlWriter): Rendered[] {
    const byId = this.#byId;
    if (byId === undefined) return this.#sets.length === 0 ? [] : [this.#statement(writer(), this.#sets, Cond.EMPTY)];
    const t = this.#target;
    const out: Rendered[] = [];
    byId.rows.forEach((row, i) => {
      const changed = withSkipEmpty(t.skipping, () => byId.sets(t.ref, i));
      if (changed.length === 0 && this.#sets.length === 0) {
        if (byId.originals !== undefined) return; // diffFrom：这一行没有变化
        throw new Error(`update(...).byId(...) has nothing to SET: the row holds only key, version or non-updatable fields`);
      }
      out.push(this.#statement(writer(), [...this.#sets, ...changed], byId.locate(t.ref, row)));
    });
    return out;
  }

  /** 写出一条 UPDATE；located 是 byId 定位一行的条件。 */
  #statement(w: SqlWriter, sets: readonly (readonly [Column<unknown>, Expr<unknown>])[], located: Cond): Rendered {
    return renderSet(w, this.#target, sets, located, "update(...)");
  }
}

/**
 * DELETE 语句：`deleteFrom(Lips).where((l) => l.delivery.eq("D1"))`。没有 where 条件时报错（清空全表要写 `.all()`）；
 * 全局过滤器的条件也加进 WHERE。不可变，每个方法返回新的语句。
 */
export class Delete<T> extends Write {
  readonly #target: Target<T>;

  /** 删除的表。 */
  get table(): Table<T> {
    return this.#target.table;
  }
  readonly #byId: ById | undefined;
  /** 是否真正删除；false 时有 `@logicDelete` 列的表改写成 UPDATE。 */
  readonly #physical: boolean;

  /** 由 {@link deleteFrom} 和各修改方法调用。 */
  constructor(target: Target<T>, byId?: ById, physical = false) {
    super();
    this.#target = target;
    this.#byId = byId;
    this.#physical = physical;
  }

  /** 表上有 `@logicDelete` 列时仍然真正删除，而不是把它设为已删除。 */
  physically(): Delete<T> {
    return new Delete(this.#target, this.#byId, true);
  }

  /**
   * 按主键删除这些行：每行一条 `DELETE ... WHERE 主键 = ?`，表上有 `@version` 时加上 `AND version = ?`；
   * 期望每行影响一行，否则 `db.execute` 抛出 UnexpectedRowCountError。行必须带主键字段（和版本字段）。
   */
  byId(...rows: readonly InsertRow<T>[]): Delete<T> {
    return new Delete(this.#target, ById.of(this.#target.table, rows as readonly object[], "deleteFrom(...).byId(...)"), this.#physical);
  }

  /** 追加 WHERE 条件；多次调用之间是 AND。 */
  where(cond: (t: Ref<T>) => Cond): Delete<T>;
  /** 条件对象：每个非空字段一个条件，规则同查询的 `where(filter)`。 */
  where<F extends FilterObject>(filter: F): Delete<T>;
  where(cond: ((t: Ref<T>) => Cond) | FilterObject): Delete<T> {
    return new Delete(this.#target.where(cond), this.#byId, this.#physical);
  }

  /** 之后的 where 里，与空值比较的条件被省略；要写在 where 之前。 */
  skipEmpty(): Delete<T> {
    return new Delete(this.#target.with({ skipping: true }), this.#byId, this.#physical);
  }

  /** 不套用数据库实例的全局过滤器，也不加逻辑删除的条件；给出名字时只关闭这些名字的过滤器。 */
  unfiltered(...names: string[]): Delete<T> {
    return new Delete(this.#target.with({ filtering: unfilteredBy(this.#target.filtering, names) }), this.#byId, this.#physical);
  }

  /** 不加逻辑删除的条件，已删除的行也会被删到；全局过滤器照常。 */
  withDeleted(): Delete<T> {
    return new Delete(this.#target.with({ filtering: { ...this.#target.filtering, logicDelete: false } }), this.#byId, this.#physical);
  }

  /** 不套用数据库实例的写入填充（审计列）；只影响逻辑删除改写成的 UPDATE。 */
  withoutFills(): Delete<T> {
    return new Delete(this.#target.with({ fills: false }), this.#byId, this.#physical);
  }

  /** 允许没有 where 条件，即删除全表。 */
  all(): Delete<T> {
    return new Delete(this.#target.with({ everyRow: true }), this.#byId, this.#physical);
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError。 */
  expect(rows: number): Delete<T> {
    return new Delete(this.#target.expect(rows), this.#byId, this.#physical);
  }

  /** `expect(n)` 写明的行数；没有写而用了 byId 时是行数。 */
  get expected(): number | undefined {
    return this.#target.expected ?? this.#byId?.rows.length;
  }

  /** `DELETE FROM 表 别名 WHERE ...`，byId 时每行一条。 */
  render(writer: () => SqlWriter): Rendered[] {
    const byId = this.#byId;
    if (byId === undefined) return [this.#statement(writer(), Cond.EMPTY)];
    return byId.rows.map((row) => this.#statement(writer(), byId.locate(this.#target.ref, row)));
  }

  /** 写出一条 DELETE 或逻辑删除的 UPDATE；located 是 byId 定位一行的条件。 */
  #statement(w: SqlWriter, located: Cond): Rendered {
    const meta = entityMeta(this.#target.table);
    const logic = meta.writes.logicDelete;
    if (logic === undefined || this.#physical) {
      w.sql("DELETE FROM ");
      ownerOf(this.#target.ref as object).renderSource(w);
      this.#target.renderWhere(w, "deleteFrom(...)", located);
      return w.statement();
    }
    const ref = this.#target.ref as unknown as Record<string, Column<unknown>>;
    const column = ref[logic.property]!;
    const sets: (readonly [Column<unknown>, Expr<unknown>])[] = [[column, logic.timestamp ? (currentTimestamp() as Expr<unknown>) : bindValue(column.type, deletedValue(logic))]];
    const version = meta.writes.version;
    if (this.#byId !== undefined && version !== undefined) sets.push([ref[version]!, plus(ref[version] as unknown as Expr<number>, lit(1)) as Expr<unknown>]);
    return renderSet(w, this.#target as Target<unknown>, sets, located, "deleteFrom(...)");
  }
}

/** `byId(row)` 的行：按 `@id` 的列（有 `@version` 时加上版本）定位每一行，更新时 SET 其余可更新的字段。 */
export class ById {
  constructor(
    readonly rows: readonly object[],
    readonly meta: EntityMeta,
    /** `diffFrom(...)` 给出的修改前的行，与 rows 一一对应；没有时 SET 全部字段。 */
    readonly originals?: readonly object[],
  ) {}

  /** 表上的主键、版本、生成列和写入策略。 */
  get writes(): WriteMeta {
    return this.meta.writes;
  }

  /** 检查表有主键、每行带有每个主键字段（和版本字段）、字段都是表的列；statement 用于报错。 */
  static of(table: Table<unknown>, rows: readonly object[], statement: string): ById {
    const meta = entityMeta(table);
    const { ids, version } = meta.writes;
    if (ids.length === 0) throw new Error(`${statement} needs @id columns on ${table.name}`);
    if (rows.length === 0) throw new Error(`${statement} needs at least one row`);
    for (const row of rows) {
      for (const name of Object.keys(row)) if (meta.property(name) === undefined) throw new Error(`${statement}: ${name} has no column in ${table.name}`);
      for (const key of ById.#keys(meta.writes)) {
        if ((row as Record<string, unknown>)[key] == null) throw new Error(`${statement} needs the ${key} of every row`);
      }
    }
    return new ById(rows, meta);
  }

  /** 带上修改前的行：数量与 rows 相同，主键（和版本）相等；之后只 SET 值变了的字段。 */
  diffFrom(before: readonly object[]): ById {
    if (before.length !== this.rows.length) throw new Error(`diffFrom(...) needs one original per byId(...) row: got ${before.length} for ${this.rows.length}`);
    this.rows.forEach((row, i) => {
      for (const key of ById.#keys(this.writes)) {
        if ((row as Record<string, unknown>)[key] !== (before[i] as Record<string, unknown>)[key]) {
          throw new Error(`diffFrom(...) row ${i} has a different ${key}; byId(...) cannot change the key or version`);
        }
      }
    });
    return new ById(this.rows, this.meta, before);
  }

  /** 要写的行，按原顺序：有 diffFrom 时只含有变化的行，否则是全部行。 */
  changed(): object[] {
    return this.originals === undefined ? [...this.rows] : this.rows.filter((_, i) => this.#changedFields(i).length > 0);
  }

  /** 每行影响一行；有 diffFrom 时只算有变化的行。 */
  get expected(): number {
    return this.originals === undefined ? this.rows.length : this.rows.filter((_, i) => this.#changedFields(i).length > 0).length;
  }

  /** 一行的定位条件：主键相等，有版本列时版本也相等。 */
  locate(ref: object, row: object): Cond {
    return all(...ById.#keys(this.writes).map((k) => ((ref as Record<string, Expr<unknown>>)[k] as Expr<never>).eq((row as Record<string, never>)[k])));
  }

  /**
   * 第 i 行的 SET 项：可更新的字段（skipEmpty 下值为空的省略），有 diffFrom 时只含值变了的；有 SET 项且表上有版本列时
   * 加上 `version = version + 1`。没有变化时为空，这一行不发语句。
   */
  sets(ref: object, i: number): (readonly [Column<unknown>, Expr<unknown>])[] {
    const row = this.rows[i] as Record<string, unknown>;
    const column = (name: string) => (ref as Record<string, Column<unknown>>)[name]!;
    const out: (readonly [Column<unknown>, Expr<unknown>])[] = [];
    for (const name of this.#changedFields(i)) {
      const value = row[name];
      if (skips(value)) continue;
      out.push([column(name), bindValue(column(name).type, value)]);
    }
    if (this.originals !== undefined && out.length === 0) return out;
    const version = this.writes.version;
    if (version !== undefined) out.push([column(version), plus(column(version) as unknown as Expr<number>, lit(1)) as Expr<unknown>]);
    return out;
  }

  /** 第 i 行要 SET 的字段：可更新、值不为 undefined，有 diffFrom 时还要与修改前不同。 */
  #changedFields(i: number): string[] {
    const row = this.rows[i] as Record<string, unknown>;
    const old = this.originals?.[i] as Record<string, unknown> | undefined;
    return Object.keys(row).filter(
      (name) => row[name] !== undefined && updates(this.writes, name) && (old === undefined || !same(row[name], old[name], this.meta.property(name)?.type.kind)),
    );
  }

  /** 定位一行用的字段：主键，有版本列时加上版本。 */
  static #keys(writes: WriteMeta): readonly string[] {
    return writes.version === undefined ? writes.ids : [...writes.ids, writes.version];
  }
}

/** 两个值是否相同；decimal 列的文本按数值比较（"1.0" 与 "1.00" 相同），Uint8Array 按内容比较。 */
function same(a: unknown, b: unknown, kind: string | undefined): boolean {
  if (a === b) return true;
  if (kind === "decimal" && typeof a === "string" && typeof b === "string") return normalize(a) === normalize(b);
  if (a instanceof Uint8Array && b instanceof Uint8Array) return a.length === b.length && a.every((x, i) => x === b[i]);
  return false;
}

/** 去掉小数末尾的 0 和多余的小数点，用于比较精确小数的文本。 */
function normalize(decimal: string): string {
  return decimal.includes(".") ? decimal.replace(/0+$/, "").replace(/\.$/, "") : decimal;
}

/**
 * 联表的 UPDATE：SET 和 WHERE 可以引用连接的表，由方言包的 `updateJoin` 开始：
 * `updateJoin(Card, User, (c, u) => u.id.eq(c.userId)).set((c, u) => ({ owner: u.name })).where((c, u) => u.status.eq("ACTIVE"))`。
 * PostgreSQL 写成 `UPDATE ... FROM`，MySQL 写成 `UPDATE ... JOIN ... SET`，HANA 写成按主键对上行的 MERGE（被改表要有 `@id`）。
 * 连接是内连接；每个被改行至多匹配一个连接行，否则 PostgreSQL、MySQL 取其中一行（值不确定），HANA 报错。
 * 没有 where 条件时报错（连接条件不算），全表要写 `.all()`；两张表的全局过滤器和逻辑删除条件都照常加上。
 * 不能用导航关系，要用的表直接连接。不可变，每个方法返回新的语句。
 */
export class JoinUpdate<T, S> extends Write {
  readonly #target: Target<T>;
  readonly #source: Ref<S>;
  readonly #on: Cond;
  readonly #sets: readonly (readonly [Column<unknown>, Expr<unknown>])[];

  /** 由 {@link JoinUpdate.of} 和各修改方法调用。 */
  constructor(target: Target<T>, source: Ref<S>, on: Cond, sets: readonly (readonly [Column<unknown>, Expr<unknown>])[]) {
    super();
    this.#target = target;
    this.#source = source;
    this.#on = on;
    this.#sets = sets;
  }

  /** 更新 table，连接 joined；由方言包的 `updateJoin` 调用。 */
  static of<T, S>(table: Table<T>, joined: Table<S>, on: (t: Ref<T>, s: Ref<S>) => Cond): JoinUpdate<T, S> {
    const target = Target.of(table);
    const source = createRef(tableSource(joined));
    return new JoinUpdate(target, source, joinCondition(target, source, on), []);
  }

  /** 追加 SET 项：回调返回列字段到新值的对象，值可以引用连接的表，例如 `set((c, u) => ({ owner: u.name }))`。 */
  set(assign: (t: Ref<T>, s: Ref<S>) => Assignments<T>): JoinUpdate<T, S> {
    const target = this.#target;
    const sets = [...this.#sets];
    for (const [name, value] of Object.entries(assign(target.ref, this.#source) as object)) {
      const column = (target.ref as Record<string, unknown>)[name];
      if (!(column instanceof Column) || column.owner !== ownerOf(target.ref as object)) throw new Error(`set(...): ${name} is not a column of ${target.table.name}`);
      if (entityMeta(target.table).writes.readOnly.includes(name)) throw new Error(`set(...): ${target.table.name}.${name} is @readOnly`);
      if (value === undefined) continue;
      sets.push([column, value instanceof Expr ? value : bindValue(column.type, value)]);
    }
    return new JoinUpdate(target, this.#source, this.#on, sets);
  }

  /** 追加 WHERE 条件，可以引用两张表；多次调用之间是 AND。 */
  where(cond: (t: Ref<T>, s: Ref<S>) => Cond): JoinUpdate<T, S> {
    return new JoinUpdate(withCondition(this.#target, cond(this.#target.ref, this.#source)), this.#source, this.#on, this.#sets);
  }

  /** 两张表都不套用全局过滤器，也不加逻辑删除的条件；给出名字时只关闭这些名字的过滤器。 */
  unfiltered(...names: string[]): JoinUpdate<T, S> {
    return new JoinUpdate(this.#target.with({ filtering: unfilteredBy(this.#target.filtering, names) }), this.#source, this.#on, this.#sets);
  }

  /** 两张表都不加逻辑删除的条件。 */
  withDeleted(): JoinUpdate<T, S> {
    return new JoinUpdate(this.#target.with({ filtering: { ...this.#target.filtering, logicDelete: false } }), this.#source, this.#on, this.#sets);
  }

  /** 不套用数据库实例的写入填充（审计列）。 */
  withoutFills(): JoinUpdate<T, S> {
    return new JoinUpdate(this.#target.with({ fills: false }), this.#source, this.#on, this.#sets);
  }

  /** 允许没有 where 条件，即改动所有连接得上的行。 */
  all(): JoinUpdate<T, S> {
    return new JoinUpdate(this.#target.with({ everyRow: true }), this.#source, this.#on, this.#sets);
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError。 */
  expect(rows: number): JoinUpdate<T, S> {
    return new JoinUpdate(this.#target.expect(rows), this.#source, this.#on, this.#sets);
  }

  /** `expect(n)` 写明的行数。 */
  get expected(): number | undefined {
    return this.#target.expected;
  }

  /** 一条联表 UPDATE，由方言写出；没有 SET 项时报错。 */
  render(writer: () => SqlWriter): Rendered[] {
    if (this.#sets.length === 0) throw new Error("updateJoin(...) has nothing to SET; add set(...)");
    const w = writer();
    w.dialect.joinUpdate(w, joinParts(w, this.#target, this.#source, this.#on, this.#sets, "updateJoin(...)"));
    return [w.statement()];
  }
}

/**
 * 联表的 DELETE：WHERE 可以引用连接的表，由方言包的 `deleteJoin` 开始：
 * `deleteJoin(Card, User, (c, u) => u.id.eq(c.userId)).where((c, u) => u.status.eq("LEFT"))`。
 * PostgreSQL 写成 `DELETE ... USING`，MySQL 写成 `DELETE 别名 FROM ... JOIN`，HANA 写成 `WHERE EXISTS` 子查询。
 * 被改表有 `@logicDelete` 列时改写成联表 UPDATE，`.physically()` 才真正删除。其余规则同 {@link JoinUpdate}。
 */
export class JoinDelete<T, S> extends Write {
  readonly #target: Target<T>;
  readonly #source: Ref<S>;
  readonly #on: Cond;
  /** 是否真正删除；false 时有 `@logicDelete` 列的表改写成 UPDATE。 */
  readonly #physical: boolean;

  /** 由 {@link JoinDelete.of} 和各修改方法调用。 */
  constructor(target: Target<T>, source: Ref<S>, on: Cond, physical: boolean) {
    super();
    this.#target = target;
    this.#source = source;
    this.#on = on;
    this.#physical = physical;
  }

  /** 从 table 删除，连接 joined；由方言包的 `deleteJoin` 调用。 */
  static of<T, S>(table: Table<T>, joined: Table<S>, on: (t: Ref<T>, s: Ref<S>) => Cond): JoinDelete<T, S> {
    const target = Target.of(table);
    const source = createRef(tableSource(joined));
    return new JoinDelete(target, source, joinCondition(target, source, on), false);
  }

  /** 追加 WHERE 条件，可以引用两张表；多次调用之间是 AND。 */
  where(cond: (t: Ref<T>, s: Ref<S>) => Cond): JoinDelete<T, S> {
    return new JoinDelete(withCondition(this.#target, cond(this.#target.ref, this.#source)), this.#source, this.#on, this.#physical);
  }

  /** 表上有 `@logicDelete` 列时仍然真正删除。 */
  physically(): JoinDelete<T, S> {
    return new JoinDelete(this.#target, this.#source, this.#on, true);
  }

  /** 两张表都不套用全局过滤器，也不加逻辑删除的条件；给出名字时只关闭这些名字的过滤器。 */
  unfiltered(...names: string[]): JoinDelete<T, S> {
    return new JoinDelete(this.#target.with({ filtering: unfilteredBy(this.#target.filtering, names) }), this.#source, this.#on, this.#physical);
  }

  /** 两张表都不加逻辑删除的条件。 */
  withDeleted(): JoinDelete<T, S> {
    return new JoinDelete(this.#target.with({ filtering: { ...this.#target.filtering, logicDelete: false } }), this.#source, this.#on, this.#physical);
  }

  /** 不套用数据库实例的写入填充；只影响逻辑删除改写成的 UPDATE。 */
  withoutFills(): JoinDelete<T, S> {
    return new JoinDelete(this.#target.with({ fills: false }), this.#source, this.#on, this.#physical);
  }

  /** 允许没有 where 条件，即删除所有连接得上的行。 */
  all(): JoinDelete<T, S> {
    return new JoinDelete(this.#target.with({ everyRow: true }), this.#source, this.#on, this.#physical);
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError。 */
  expect(rows: number): JoinDelete<T, S> {
    return new JoinDelete(this.#target.expect(rows), this.#source, this.#on, this.#physical);
  }

  /** `expect(n)` 写明的行数。 */
  get expected(): number | undefined {
    return this.#target.expected;
  }

  /** 一条联表 DELETE，或逻辑删除改写成的联表 UPDATE，由方言写出。 */
  render(writer: () => SqlWriter): Rendered[] {
    const w = writer();
    const logic = entityMeta(this.#target.table).writes.logicDelete;
    if (logic === undefined || this.#physical) {
      w.dialect.joinDelete(w, joinParts(w, this.#target, this.#source, this.#on, [], "deleteJoin(...)"));
    } else {
      const column = (this.#target.ref as unknown as Record<string, Column<unknown>>)[logic.property]!;
      const deleted = logic.timestamp ? (currentTimestamp() as Expr<unknown>) : bindValue(column.type, deletedValue(logic));
      w.dialect.joinUpdate(w, joinParts(w, this.#target, this.#source, this.#on, [[column, deleted]], "deleteJoin(...)"));
    }
    return [w.statement()];
  }
}

/** 用两张表的引用算出连接条件。 */
function joinCondition<T, S>(target: Target<T>, source: Ref<S>, on: (t: Ref<T>, s: Ref<S>) => Cond): Cond {
  const c = on(target.ref, source);
  if (c == null) throw new TypeError("A condition is missing; return Cond.EMPTY for no condition");
  if (c.isEmpty) throw new Error("A join needs a condition");
  return c;
}

/** 给 target 追加一个已经算好的条件。 */
function withCondition<T>(target: Target<T>, c: Cond): Target<T> {
  if (c == null) throw new TypeError("A condition is missing; return Cond.EMPTY for no condition");
  return target.with({ conditions: [...target.conditions, c] });
}

/**
 * 交给方言的各部分：被改表的过滤条件和 where 合在一起，连接表的过滤条件并进连接条件，SET 里再加上更新时的填充（覆盖同名的项）。
 * 没有 where 条件又没有 all() 时报错；条件或值用到导航关系时报错。statement 用于报错。
 */
function joinParts(
  w: SqlWriter,
  target: Target<unknown>,
  source: Ref<unknown>,
  on: Cond,
  assignments: readonly (readonly [Column<unknown>, Expr<unknown>])[],
  statement: string,
): JoinWrite {
  const own = all(...target.conditions);
  if (own.isEmpty && !target.everyRow) {
    throw new Error(`${statement} without where(...) would touch every joined row of ${target.table.name}; add .all() if that is intended`);
  }
  const sets = [...assignments];
  for (const fill of target.fills ? fillsFor(w, target.ref as object, false) : []) {
    const i = sets.findIndex(([c]) => c.column.name === fill[0].column.name);
    if (i >= 0) sets.splice(i, 1);
    sets.push(fill);
  }
  const owner = ownerOf(target.ref as object);
  const sourceOwner = ownerOf(source as object);
  if (implicitJoins(w, [owner, sourceOwner], [...sets.map(([, v]) => v), own, on]).size > 0) {
    throw new Error(`${statement} cannot follow relations; join the table you need instead`);
  }
  const filters = target.filters(w);
  const where = all(...conditionsFor(filters, target.ref as object), own);
  const meta = entityMeta(target.table);
  const name = (c: Column<unknown>): Renderable => ({ render: (x: SqlWriter) => void x.identifier(columnName(c.column as PropertyMeta, x.naming)) });
  const keys = meta.writes.ids.map((id) => (target.ref as Record<string, Column<unknown>>)[id]!);
  return {
    target: { render: (x) => owner.renderSource(x) },
    targetAlias: { render: (x) => void x.alias(owner.alias(x)) },
    table: { render: (x) => tableName(x, meta) },
    source: { render: (x) => sourceOwner.renderSource(x) },
    on: all(on, ...conditionsFor(filters, source as object)),
    where: where.isEmpty ? undefined : where,
    columns: sets.map(([c]) => name(c)),
    targetColumns: sets.map(([c]) => c),
    values: sets.map(([, v]) => v),
    keys: keys.map(name),
    keyColumns: keys,
  };
}

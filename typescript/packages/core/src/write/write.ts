import { columnName, entityMeta, type EntityMeta, type One, type PropertyMeta, type Table } from "../schema/metadata.js";
import { all, bindValue, Column, Cond, Expr, skips, withSkipEmpty } from "../sql/expr.js";
import type { Rendered, SqlWriter } from "../sql/writer.js";
import { lit, plus } from "../sql/functions.js";
import { filterCondition, type FilterObject } from "../query/filter.js";
import type { Many } from "../query/many.js";
import { conditionsFor, renderNamed, type Query, type SourceFilter } from "../query/query.js";
import { createRef, ownerOf, RefOwner, tableSource, type Ref } from "../query/source.js";
import type { Clause, Lock, Upsert } from "../sql/dialect.js";

/** 表实体里的列字段：去掉 `@one`、`@many` 关系字段。 */
type Columns<T> = { [K in keyof T as T[K] extends Many<any> | One<any> ? never : K]: T[K] };

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

/** 写出 `schema.表名`。 */
function tableName(w: SqlWriter, meta: EntityMeta): void {
  if (meta.table!.schema !== undefined) w.identifier(meta.table!.schema).sql(".");
  w.identifier(meta.table!.name);
}

/**
 * INSERT 语句。行是普通对象（或表类的实例），字段对应表的列：取第一行里值不为 undefined 的字段，
 * 其余行必须有相同的字段；null 写成 NULL，`@generated` 的列总是不写；字段不是表的列时报错。
 * 多行在一个事务里逐行执行。不可变，每个方法返回新的语句。
 */
export class Insert<T> extends Write {
  readonly #table: Table<T>;
  readonly #rows: readonly object[];
  readonly #columns: readonly PropertyMeta[];
  readonly #source: Query<unknown> | undefined;
  /** 插入或更新：冲突的键和冲突时更新的列（undefined 是插入的列里除了键以外的全部，空数组是什么也不做）。 */
  readonly #conflict: { readonly keys: readonly string[]; readonly updates: readonly string[] | undefined } | undefined;

  /** 由 {@link insertInto} 和各修改方法调用。 */
  constructor(
    table: Table<T>,
    rows: readonly object[],
    columns: readonly PropertyMeta[],
    source: Query<unknown> | undefined,
    conflict?: { readonly keys: readonly string[]; readonly updates: readonly string[] | undefined },
  ) {
    super();
    tableSource(table);   // 校验它是带 @table 的表
    this.#table = table;
    this.#rows = rows;
    this.#columns = columns;
    this.#source = source;
    this.#conflict = conflict;
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
    return new Insert(this.#table, this.#rows, this.#columns, undefined, { keys, updates: names });
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
    return new Insert(this.#table, all, columns, undefined, this.#conflict);
  }

  /** INSERT ... SELECT：查询的每一列按名字对应表的同名列，例如 `select((s) => ({ delivery: s.delivery }))`。 */
  from(query: Query<unknown>): Insert<T> {
    if (this.#rows.length > 0) throw new Error("insertInto(...) already has values(...)");
    if (this.#conflict !== undefined) throw new Error("onConflict(...) needs values(...), not from(query)");
    for (const label of query.labels) this.#column(label, "the query's column");
    return new Insert(this.#table, [], [], query);
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
      if (this.#conflict !== undefined) {
        w.dialect.upsert(w, this.#upsert(w, row, this.#conflict));
        return w.statement();
      }
      this.#head(w, this.#columns);
      w.sql(" VALUES (");
      this.#columns.forEach((c, i) => {
        if (i > 0) w.sql(", ");
        w.render(bindValue(c.type, (row as Record<string, unknown>)[c.name] ?? null));
      });
      w.sql(")");
      return w.statement();
    });
  }

  /** 一行的插入或更新交给方言的内容：列名按命名策略，键必须是插入的列。 */
  #upsert(w: SqlWriter, row: object, conflict: { readonly keys: readonly string[]; readonly updates: readonly string[] | undefined }): Upsert {
    const names = this.#columns.map((c) => c.name);
    for (const k of conflict.keys) if (!names.includes(k)) throw new Error(`onConflict(...) key '${k}' must be one of the inserted columns ${names.join(", ")}`);
    const updates = conflict.updates ?? names.filter((n) => !conflict.keys.includes(n));
    const meta = entityMeta(this.#table);
    const name = (field: string) => columnName(meta.property(field)!, w.naming);
    return {
      table: { render: (x: SqlWriter) => tableName(x, meta) },
      columns: this.#columns.map((c) => columnName(c, w.naming)),
      values: this.#columns.map((c) => bindValue(c.type, (row as Record<string, unknown>)[c.name] ?? null)),
      keys: conflict.keys.map(name),
      updates: updates.map(name),
      assignments: [],
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

  /** 行里要写的列：值不为 undefined 的字段，跳过 `@generated` 的列。 */
  #columnsOf(row: object): PropertyMeta[] {
    const generated = entityMeta(this.#table).writes.generated;
    return Object.entries(row)
      .filter(([name, value]) => value !== undefined && name !== generated)
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
    readonly filtered: boolean,
    readonly everyRow: boolean,
    readonly expected: number | undefined,
  ) {}

  /** 以 table 的一次新出现开始。 */
  static of<T>(table: Table<T>): Target<T> {
    return new Target(table, createRef(tableSource(table)), [], false, true, false, undefined);
  }

  /** 改动部分字段后的副本。 */
  with(change: Partial<Pick<Target<T>, "conditions" | "skipping" | "filtered" | "everyRow" | "expected">>): Target<T> {
    const c = { ...this, ...change };
    return new Target(this.table, this.ref, c.conditions, c.skipping, c.filtered, c.everyRow, c.expected);
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
    const filters = this.filtered ? conditionsFor((w.settings.filters as readonly SourceFilter[] | undefined) ?? [], this.ref as object) : [];
    const where = all(...filters, own);
    if (!where.isEmpty) w.sql("\nWHERE ").render(where);
  }
}

/**
 * UPDATE 语句：`update(Lips).set((l) => ({ unit: "KG", quantity: plus(l.quantity, val("1")) })).where((l) => l.delivery.eq("D1"))`。
 * 没有 where 条件时报错（全表更新要写 `.all()`）；全局过滤器的条件也加进 WHERE。不可变，每个方法返回新的语句。
 */
export class Update<T> extends Write {
  readonly #target: Target<T>;
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

  /** 不套用数据库实例的全局过滤器。 */
  unfiltered(): Update<T> {
    return new Update(this.#target.with({ filtered: false }), this.#sets, this.#byId);
  }

  /** 允许没有 where 条件，即更新全表。 */
  all(): Update<T> {
    return new Update(this.#target.with({ everyRow: true }), this.#sets, this.#byId);
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError；用于"比较后更新"的状态流转。 */
  expect(rows: number): Update<T> {
    return new Update(this.#target.expect(rows), this.#sets, this.#byId);
  }

  /** `expect(n)` 写明的行数；没有写而用了 byId 时是行数。 */
  get expected(): number | undefined {
    return this.#target.expected ?? this.#byId?.rows.length;
  }

  /** `UPDATE 表 别名 SET 列 = 值, ... WHERE ...`，byId 时每行一条；没有 SET 项时没有语句。 */
  render(writer: () => SqlWriter): Rendered[] {
    const byId = this.#byId;
    if (byId === undefined) return this.#sets.length === 0 ? [] : [this.#statement(writer(), this.#sets, Cond.EMPTY)];
    const t = this.#target;
    return byId.rows.map((row) => this.#statement(writer(), [...this.#sets, ...withSkipEmpty(t.skipping, () => byId.sets(t.ref, row))], byId.locate(t.ref, row)));
  }

  /** 写出一条 UPDATE；located 是 byId 定位一行的条件。 */
  #statement(w: SqlWriter, sets: readonly (readonly [Column<unknown>, Expr<unknown>])[], located: Cond): Rendered {
    w.sql("UPDATE ");
    ownerOf(this.#target.ref as object).renderSource(w);
    w.sql("\nSET ");
    sets.forEach(([column, value], i) => {
      if (i > 0) w.sql(", ");
      w.identifier(columnName(column.column as PropertyMeta, w.naming)).sql(" = ").render(value);
    });
    this.#target.renderWhere(w, "update(...)", located);
    return w.statement();
  }
}

/**
 * DELETE 语句：`deleteFrom(Lips).where((l) => l.delivery.eq("D1"))`。没有 where 条件时报错（清空全表要写 `.all()`）；
 * 全局过滤器的条件也加进 WHERE。不可变，每个方法返回新的语句。
 */
export class Delete<T> extends Write {
  readonly #target: Target<T>;
  readonly #byId: ById | undefined;

  /** 由 {@link deleteFrom} 和各修改方法调用。 */
  constructor(target: Target<T>, byId?: ById) {
    super();
    this.#target = target;
    this.#byId = byId;
  }

  /**
   * 按主键删除这些行：每行一条 `DELETE ... WHERE 主键 = ?`，表上有 `@version` 时加上 `AND version = ?`；
   * 期望每行影响一行，否则 `db.execute` 抛出 UnexpectedRowCountError。行必须带主键字段（和版本字段）。
   */
  byId(...rows: readonly InsertRow<T>[]): Delete<T> {
    return new Delete(this.#target, ById.of(this.#target.table, rows as readonly object[], "deleteFrom(...).byId(...)"));
  }

  /** 追加 WHERE 条件；多次调用之间是 AND。 */
  where(cond: (t: Ref<T>) => Cond): Delete<T>;
  /** 条件对象：每个非空字段一个条件，规则同查询的 `where(filter)`。 */
  where<F extends FilterObject>(filter: F): Delete<T>;
  where(cond: ((t: Ref<T>) => Cond) | FilterObject): Delete<T> {
    return new Delete(this.#target.where(cond), this.#byId);
  }

  /** 之后的 where 里，与空值比较的条件被省略；要写在 where 之前。 */
  skipEmpty(): Delete<T> {
    return new Delete(this.#target.with({ skipping: true }), this.#byId);
  }

  /** 不套用数据库实例的全局过滤器。 */
  unfiltered(): Delete<T> {
    return new Delete(this.#target.with({ filtered: false }), this.#byId);
  }

  /** 允许没有 where 条件，即删除全表。 */
  all(): Delete<T> {
    return new Delete(this.#target.with({ everyRow: true }), this.#byId);
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError。 */
  expect(rows: number): Delete<T> {
    return new Delete(this.#target.expect(rows), this.#byId);
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

  /** 写出一条 DELETE；located 是 byId 定位一行的条件。 */
  #statement(w: SqlWriter, located: Cond): Rendered {
    w.sql("DELETE FROM ");
    ownerOf(this.#target.ref as object).renderSource(w);
    this.#target.renderWhere(w, "deleteFrom(...)", located);
    return w.statement();
  }
}

/** `byId(row)` 的行：按 `@id` 的列（有 `@version` 时加上版本）定位每一行，更新时 SET 其余的字段。不对外导出。 */
export class ById {
  constructor(
    readonly rows: readonly object[],
    readonly ids: readonly string[],
    readonly version: string | undefined,
    readonly generated: string | undefined,
  ) {}

  /** 检查表有主键、每行带有每个主键字段（和版本字段）、字段都是表的列；statement 用于报错。 */
  static of(table: Table<unknown>, rows: readonly object[], statement: string): ById {
    const meta = entityMeta(table);
    const { ids, version, generated } = meta.writes;
    if (ids.length === 0) throw new Error(`${statement} needs @id columns on ${table.name}`);
    if (rows.length === 0) throw new Error(`${statement} needs at least one row`);
    for (const row of rows) {
      for (const name of Object.keys(row)) if (meta.property(name) === undefined) throw new Error(`${statement}: ${name} has no column in ${table.name}`);
      for (const key of version === undefined ? ids : [...ids, version]) {
        if ((row as Record<string, unknown>)[key] == null) throw new Error(`${statement} needs the ${key} of every row`);
      }
    }
    return new ById(rows, ids, version, generated);
  }

  /** 一行的定位条件：主键相等，有版本列时版本也相等。 */
  locate(ref: object, row: object): Cond {
    const keys = this.version === undefined ? this.ids : [...this.ids, this.version];
    return all(...keys.map((k) => ((ref as Record<string, Expr<unknown>>)[k] as Expr<never>).eq((row as Record<string, never>)[k])));
  }

  /** 一行的 SET 项：主键、版本和生成列以外的字段（skipEmpty 下值为空的省略）；有版本列时加上 `version = version + 1`。 */
  sets(ref: object, row: object): (readonly [Column<unknown>, Expr<unknown>])[] {
    const out: (readonly [Column<unknown>, Expr<unknown>])[] = [];
    const column = (name: string) => (ref as Record<string, Column<unknown>>)[name]!;
    for (const [name, value] of Object.entries(row)) {
      if (this.ids.includes(name) || name === this.version || name === this.generated || value === undefined || skips(value)) continue;
      out.push([column(name), bindValue(column(name).type, value)]);
    }
    if (this.version !== undefined) out.push([column(this.version), plus(column(this.version) as unknown as Expr<number>, lit(1)) as Expr<unknown>]);
    return out;
  }
}

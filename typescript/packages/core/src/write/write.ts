import { columnName, entityMeta, type EntityMeta, type One, type PropertyMeta, type Table } from "../schema/metadata.js";
import { all, bindValue, Column, Cond, Expr, skips, withSkipEmpty } from "../sql/expr.js";
import type { Rendered, SqlWriter } from "../sql/writer.js";
import { filterCondition, type FilterObject } from "../query/filter.js";
import type { Many } from "../query/many.js";
import { conditionsFor, renderNamed, type Query, type SourceFilter } from "../query/query.js";
import { createRef, ownerOf, tableSource, type Ref } from "../query/source.js";

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

  /** 由 {@link insertInto} 和各修改方法调用。 */
  constructor(table: Table<T>, rows: readonly object[], columns: readonly PropertyMeta[], source: Query<unknown> | undefined) {
    super();
    tableSource(table);   // 校验它是带 @table 的表
    this.#table = table;
    this.#rows = rows;
    this.#columns = columns;
    this.#source = source;
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
    return new Insert(this.#table, all, columns, undefined);
  }

  /** INSERT ... SELECT：查询的每一列按名字对应表的同名列，例如 `select((s) => ({ delivery: s.delivery }))`。 */
  from(query: Query<unknown>): Insert<T> {
    if (this.#rows.length > 0) throw new Error("insertInto(...) already has values(...)");
    for (const label of query.labels) this.#column(label, "the query's column");
    return new Insert(this.#table, [], [], query);
  }

  /** 插入不检查影响的行数。 */
  get expected(): number | undefined {
    return undefined;
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
   */
  renderWhere(w: SqlWriter, statement: string): void {
    const own = all(...this.conditions);
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

  /** 由 {@link update} 和各修改方法调用。 */
  constructor(target: Target<T>, sets: readonly (readonly [Column<unknown>, Expr<unknown>])[]) {
    super();
    this.#target = target;
    this.#sets = sets;
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
    return new Update(target, sets);
  }

  /** 追加 WHERE 条件；多次调用之间是 AND。 */
  where(cond: (t: Ref<T>) => Cond): Update<T>;
  /** 条件对象：每个非空字段一个条件，规则同查询的 `where(filter)`。 */
  where<F extends FilterObject>(filter: F): Update<T>;
  where(cond: ((t: Ref<T>) => Cond) | FilterObject): Update<T> {
    return new Update(this.#target.where(cond), this.#sets);
  }

  /** 之后的 set 与 where 里，值为空的 SET 项和与空值比较的条件被省略；要写在它们之前。 */
  skipEmpty(): Update<T> {
    return new Update(this.#target.with({ skipping: true }), this.#sets);
  }

  /** 不套用数据库实例的全局过滤器。 */
  unfiltered(): Update<T> {
    return new Update(this.#target.with({ filtered: false }), this.#sets);
  }

  /** 允许没有 where 条件，即更新全表。 */
  all(): Update<T> {
    return new Update(this.#target.with({ everyRow: true }), this.#sets);
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError；用于"比较后更新"的状态流转。 */
  expect(rows: number): Update<T> {
    return new Update(this.#target.expect(rows), this.#sets);
  }

  get expected(): number | undefined {
    return this.#target.expected;
  }

  /** `UPDATE 表 别名 SET 列 = 值, ... WHERE ...`；没有 SET 项时没有语句。 */
  render(writer: () => SqlWriter): Rendered[] {
    if (this.#sets.length === 0) return [];
    const w = writer();
    w.sql("UPDATE ");
    ownerOf(this.#target.ref as object).renderSource(w);
    w.sql("\nSET ");
    this.#sets.forEach(([column, value], i) => {
      if (i > 0) w.sql(", ");
      w.identifier(columnName(column.column as PropertyMeta, w.naming)).sql(" = ").render(value);
    });
    this.#target.renderWhere(w, "update(...)");
    return [w.statement()];
  }
}

/**
 * DELETE 语句：`deleteFrom(Lips).where((l) => l.delivery.eq("D1"))`。没有 where 条件时报错（清空全表要写 `.all()`）；
 * 全局过滤器的条件也加进 WHERE。不可变，每个方法返回新的语句。
 */
export class Delete<T> extends Write {
  readonly #target: Target<T>;

  /** 由 {@link deleteFrom} 和各修改方法调用。 */
  constructor(target: Target<T>) {
    super();
    this.#target = target;
  }

  /** 追加 WHERE 条件；多次调用之间是 AND。 */
  where(cond: (t: Ref<T>) => Cond): Delete<T>;
  /** 条件对象：每个非空字段一个条件，规则同查询的 `where(filter)`。 */
  where<F extends FilterObject>(filter: F): Delete<T>;
  where(cond: ((t: Ref<T>) => Cond) | FilterObject): Delete<T> {
    return new Delete(this.#target.where(cond));
  }

  /** 之后的 where 里，与空值比较的条件被省略；要写在 where 之前。 */
  skipEmpty(): Delete<T> {
    return new Delete(this.#target.with({ skipping: true }));
  }

  /** 不套用数据库实例的全局过滤器。 */
  unfiltered(): Delete<T> {
    return new Delete(this.#target.with({ filtered: false }));
  }

  /** 允许没有 where 条件，即删除全表。 */
  all(): Delete<T> {
    return new Delete(this.#target.with({ everyRow: true }));
  }

  /** 期望影响 rows 行，不符时 `db.execute` 抛出 UnexpectedRowCountError。 */
  expect(rows: number): Delete<T> {
    return new Delete(this.#target.expect(rows));
  }

  get expected(): number | undefined {
    return this.#target.expected;
  }

  /** `DELETE FROM 表 别名 WHERE ...`。 */
  render(writer: () => SqlWriter): Rendered[] {
    const w = writer();
    w.sql("DELETE FROM ");
    ownerOf(this.#target.ref as object).renderSource(w);
    this.#target.renderWhere(w, "deleteFrom(...)");
    return [w.statement()];
  }
}

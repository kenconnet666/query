import type { NamingStrategy, Table } from "../schema/metadata.js";
import { declaredTable, describeType, findColumn, integral, qualifiedName, sameType, widensTo, type ColumnDef, type ColumnType, type IndexDef, type TableDef } from "./model.js";
import type { Catalog, SchemaSyntax } from "./syntax.js";
import type { Sharding } from "../query/sharding.js";

/** 差异的处理方式：SAFE 自动执行，DESTRUCTIVE 要 {@link SchemaPlan.allowDestructive}，REPORT 只报告。 */
export type Risk = "SAFE" | "DESTRUCTIVE" | "REPORT";

/** 一项差异：表、处理方式、说明和消除它的语句（只报告的为空）。 */
export interface Change {
  readonly table: string;
  readonly risk: Risk;
  readonly message: string;
  readonly statements: readonly string[];
}

/**
 * 声明与实库的差异，以及消除差异的语句：`await db.schema().plan(Note, Doc)`。不可变；{@link apply} 执行。
 * 安全的（建表、加列、放宽类型、加索引和外键、允许 NULL、改默认值、按旧名改名）自动执行；有风险的（缩窄或改类型、
 * 改成 NOT NULL、加没有默认值的 NOT NULL 列）只有 {@link allowDestructive} 之后才执行；只报告的从不生成语句。
 */
export class SchemaPlan {
  constructor(
    readonly changes: readonly Change[],
    readonly destructive: boolean,
    readonly executor: (statements: readonly string[]) => Promise<void>,
  ) {}

  /** 声明与实库是否一致。 */
  get isEmpty(): boolean {
    return this.changes.length === 0;
  }

  /** 允许执行有风险的语句；只报告的差异仍然不执行。 */
  allowDestructive(): SchemaPlan {
    return new SchemaPlan(this.changes, true, this.executor);
  }

  /** {@link apply} 会执行的语句，按顺序；外键排在所有其他语句之后。 */
  get statements(): string[] {
    const head: string[] = [];
    const foreignKeys: string[] = [];
    for (const c of this.changes) {
      if (c.risk === "REPORT" || (c.risk === "DESTRUCTIVE" && !this.destructive)) continue;
      // 只有给已有的表加外键的 ALTER TABLE 放到最后；建表语句里内联的外键（SQLite）随建表执行。
      for (const s of c.statements) (s.startsWith("ALTER TABLE") && s.includes(" FOREIGN KEY (") ? foreignKeys : head).push(s);
    }
    return [...head, ...foreignKeys];
  }

  /** 不会执行的差异：只报告的，以及没有 {@link allowDestructive} 时有风险的。 */
  get pending(): Change[] {
    return this.changes.filter((c) => c.risk === "REPORT" || (c.risk === "DESTRUCTIVE" && !this.destructive));
  }

  /** 执行 {@link statements}，一条一次；PostgreSQL 在一个事务里执行。 */
  async apply(): Promise<void> {
    const statements = this.statements;
    if (statements.length > 0) await this.executor(statements);
  }

  /** 要执行的语句，每条一行，以分号结尾。 */
  toSql(): string {
    return this.statements.map((s) => s + ";").join("\n");
  }

  /** 差异的列表，每项一行。 */
  toString(): string {
    return this.changes.map((c) => `[${c.risk}] ${c.table}: ${c.message}`).join("\n");
  }
}

/** 结构变更的入口，由 `db.schema()` 得到；与 Java 的 SchemaTools 一致。 */
export class SchemaTools {
  constructor(
    readonly syntax: SchemaSyntax,
    readonly naming: NamingStrategy,
    readonly schemas: (schema: string) => string,
    readonly sharding: Sharding,
    readonly catalog: Catalog,
    readonly executor: (statements: readonly string[]) => Promise<void>,
  ) {}

  /** 实体类声明的结构，schema 已按数据库实例的映射换名。 */
  declared(table: Table<unknown>): TableDef {
    return declaredTable(table, this.naming, this.schemas);
  }

  /** 实库里表的结构；表不存在时为 undefined。 */
  read(schema: string, table: string): Promise<TableDef | undefined> {
    return this.syntax.read(this.catalog, schema, table);
  }

  /**
   * 实际存在库里的表：分表时是每张物理表（表名换成物理表名，索引和外键名加上同样的后缀，免得在 schema 里重名），否则就是声明本身。
   */
  physical(table: Table<unknown>): TableDef[] {
    const t = this.declared(table);
    const shards = this.sharding.tables(table);
    if (shards.length === 0) return [t];
    return shards.map((name) => {
      const suffix = name.startsWith(t.name) ? name.slice(t.name.length) : `_${name}`;
      return {
        ...t,
        name,
        indexes: t.indexes.map((i) => ({ ...i, name: i.name + suffix })),
        foreignKeys: t.foreignKeys.map((f) => ({ ...f, name: f.name + suffix })),
      };
    });
  }

  /** 按声明建表的语句（不连库），外键在最后；分表时每张物理表一份；外部表报错。 */
  createTable(table: Table<unknown>): string[] {
    const out: string[] = [];
    const foreignKeys: string[] = [];
    for (const t of this.physical(table)) {
      if (t.external) throw new Error(`${table.name} is an external table; its DDL is not ours to write`);
      out.push(...this.syntax.createTable(t));
      if (!this.syntax.inlineForeignKeys) foreignKeys.push(...t.foreignKeys.map((f) => this.syntax.addForeignKey(t, f)));
    }
    return [...out, ...foreignKeys];
  }

  /** 只检查不改：声明与实库的差异，外部表只核对已声明的列存在、类型相容。 */
  check(...tables: Table<unknown>[]): Promise<SchemaPlan> {
    return this.plan(...tables);
  }

  /** 声明与实库的差异和消除差异的语句，见 {@link SchemaPlan}。 */
  async plan(...tables: Table<unknown>[]): Promise<SchemaPlan> {
    const changes: Change[] = [];
    for (const table of tables) {
      for (const declared of this.physical(table)) {
        const actual = await this.read(declared.schema, declared.name);
        if (declared.external) external(declared, actual, changes);
        else this.#managed(declared, actual, changes);
      }
    }
    return new SchemaPlan(changes, false, this.executor);
  }

  /** 自己管的表：建表，或逐列、逐索引、逐外键比较。 */
  #managed(declared: TableDef, actual: TableDef | undefined, changes: Change[]): void {
    const name = qualifiedName(declared);
    const add = (risk: Risk, message: string, statements: readonly string[] = []) => void changes.push({ table: name, risk, message, statements });
    const s = this.syntax;
    if (actual === undefined) {
      add("SAFE", "table is missing", s.createTable(declared));
      if (s.inlineForeignKeys) return;
      for (const f of declared.foreignKeys) add("SAFE", `foreign key ${f.name} is missing`, [s.addForeignKey(declared, f)]);
      return;
    }
    for (const c of declared.columns) this.#column(declared, actual, c, add);
    const declaredColumns = new Set(declared.columns.map((c) => c.name.toUpperCase()));
    // 按旧名改名的列，旧名也算声明过的。
    for (const c of declared.columns) if (c.previousName !== "" && findColumn(actual, c.name) === undefined) declaredColumns.add(c.previousName.toUpperCase());
    for (const live of actual.columns) if (!declaredColumns.has(live.name.toUpperCase())) add("REPORT", `column ${live.name} is not declared`);
    if (!sameNames(declared.primaryKey, actual.primaryKey)) add("REPORT", `primary key is ${list(actual.primaryKey)} but declared ${list(declared.primaryKey)}`);
    for (const i of declared.indexes) {
      const live = actual.indexes.find((x) => x.name.toUpperCase() === i.name.toUpperCase());
      if (live === undefined) add("SAFE", `index ${i.name} is missing`, [s.createIndex(declared, i)]);
      else if (live.unique !== i.unique || !sameNames(live.columns, i.columns)) add("REPORT", `index ${i.name} is ${describeIndex(live)} but declared ${describeIndex(i)}`);
    }
    for (const f of declared.foreignKeys) {
      if (actual.foreignKeys.some((x) => x.name.toUpperCase() === f.name.toUpperCase())) continue;
      if (s.inlineForeignKeys) add("REPORT", `foreign key ${f.name} is missing; ${REBUILD}`);
      else add("SAFE", `foreign key ${f.name} is missing`, [s.addForeignKey(declared, f)]);
    }
  }

  /** 一列：缺列（按旧名改名或加列）、类型、可空、默认值。 */
  #column(declared: TableDef, actual: TableDef, c: ColumnDef, add: (risk: Risk, message: string, statements?: readonly string[]) => void): void {
    const s = this.syntax;
    let live = findColumn(actual, c.name);
    const previous = c.previousName === "" ? undefined : findColumn(actual, c.previousName);
    if (live === undefined && previous !== undefined) {
      add("SAFE", `column ${c.previousName} is renamed to ${c.name}`, [s.renameColumn(declared, c.previousName, c.name)]);
      live = { ...previous, name: c.name, previousName: "" };
    }
    if (live === undefined) {
      const needsDefault = !c.nullable && c.defaultValue === "" && !c.identity;
      add(
        needsDefault ? "DESTRUCTIVE" : "SAFE",
        `column ${c.name} is missing` + (needsDefault ? "; a NOT NULL column without a default fails on a table with rows: add it nullable, backfill, then make it NOT NULL" : ""),
        [s.addColumn(declared, c)],
      );
      return;
    }
    const wanted = s.stored(c.type);
    if (!sameType(wanted, live.type)) {
      const change = `column ${c.name} is ${describeType(live.type)} but declared ${describeType(wanted)}`;
      if (!s.altersColumns) add("REPORT", `${change}; ${REBUILD}`);
      else if (widensTo(live.type, wanted)) add("SAFE", change, [s.alterType(declared, c)]);
      else if (s.changesTypesInPlace) add("DESTRUCTIVE", change + "; narrowing or changing the type may fail or lose data", [s.alterType(declared, c)]);
      else add("REPORT", change + "; this database cannot change it in place: add a new column, copy the data, drop the old one");
    }
    // 改列的差异：方言能就地改时按 risk 生成语句，不能时（SQLite）只报告并提示重建。
    const alter = (risk: Risk, message: string, statement: () => string) => (s.altersColumns ? add(risk, message, [statement()]) : add("REPORT", `${message}; ${REBUILD}`));
    if (c.nullable && !live.nullable) alter("SAFE", `column ${c.name} is NOT NULL but declared nullable`, () => s.dropNotNull(declared, c));
    if (!c.nullable && live.nullable) alter("DESTRUCTIVE", `column ${c.name} is nullable but declared NOT NULL; this fails while the column holds NULLs`, () => s.setNotNull(declared, c));
    if (c.defaultValue !== "" && s.normalizeDefault(c.defaultValue) !== s.normalizeDefault(live.defaultValue)) {
      alter("SAFE", `column ${c.name} defaults to ${live.defaultValue === "" ? "nothing" : live.defaultValue} but declared ${c.defaultValue}`, () => s.setDefault(declared, c));
    }
    if (c.identity !== live.identity) add("REPORT", `column ${c.name} ${live.identity ? "is" : "is not"} generated by the database`);
  }
}

/** 不能就地改列时的提示（SQLite）。 */
const REBUILD = "this database cannot alter it in place: create the new table, copy the rows, drop the old one and rename the new one";

/** 外部表：缺表、缺列、类型不相容只报告。 */
function external(declared: TableDef, actual: TableDef | undefined, changes: Change[]): void {
  const name = qualifiedName(declared);
  if (actual === undefined) {
    changes.push({ table: name, risk: "REPORT", message: "external table is missing", statements: [] });
    return;
  }
  for (const c of declared.columns) {
    const live = findColumn(actual, c.name);
    if (live === undefined) changes.push({ table: name, risk: "REPORT", message: `column ${c.name} is missing`, statements: [] });
    else if (!compatible(c.type, live.type)) changes.push({ table: name, risk: "REPORT", message: `column ${c.name} is ${describeType(live.type)}, which cannot hold the declared ${describeType(c.type)}`, statements: [] });
  }
}

/** 外部表的列能否装下声明的类型：同族即可，整数可以读进更宽的整数或小数。 */
function compatible(declared: ColumnType, live: ColumnType): boolean {
  if (declared.kind === live.kind) return true;
  switch (declared.kind) {
    case "TEXT":
    case "CLOB":
      return live.kind === "TEXT" || live.kind === "CLOB";
    case "BYTES":
    case "BLOB":
      return live.kind === "BYTES" || live.kind === "BLOB";
    case "BIGINT":
    case "DECIMAL":
      return integral(live);
    case "INTEGER":
      return live.kind === "SMALLINT";
    case "DOUBLE":
      return live.kind === "DECIMAL" || integral(live);
    case "TIMESTAMP":
      return live.kind === "DATE";
    default:
      return false;
  }
}

/** 列名列表相同（不分大小写）。 */
function sameNames(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((x, i) => x.toUpperCase() === b[i]!.toUpperCase());
}

/** 报告里的列表写法，与 Java 的 List.toString 相同。 */
function list(names: readonly string[]): string {
  return `[${names.join(", ")}]`;
}

/** 报告里的索引写法。 */
function describeIndex(i: IndexDef): string {
  return (i.unique ? "UNIQUE " : "") + list(i.columns);
}

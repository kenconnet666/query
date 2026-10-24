import { columnName, entityMeta, type NamingStrategy, type PropertyMeta, type Table } from "../schema/metadata.js";

/** 类型族。TEXT、BYTES 有长度，DECIMAL 有精度和小数位，CLOB、BLOB 不限长度。 */
export type ColumnKind = "TEXT" | "CLOB" | "DECIMAL" | "SMALLINT" | "INTEGER" | "BIGINT" | "DOUBLE" | "BOOLEAN" | "DATE" | "TIMESTAMP" | "BYTES" | "BLOB";

/** 与方言无关的列类型；length 是文本、字节的长度或小数的总位数，不适用时为 0。 */
export interface ColumnType {
  readonly kind: ColumnKind;
  readonly length: number;
  readonly scale: number;
}

/** 构造列类型。 */
export function columnType(kind: ColumnKind, length = 0, scale = 0): ColumnType {
  return Object.freeze({ kind, length, scale });
}

/** 两个列类型相同。 */
export function sameType(a: ColumnType, b: ColumnType): boolean {
  return a.kind === b.kind && a.length === b.length && a.scale === b.scale;
}

/** 是否整数族。 */
export function integral(t: ColumnType): boolean {
  return t.kind === "SMALLINT" || t.kind === "INTEGER" || t.kind === "BIGINT";
}

/** 从 from 改成 to 是否不会丢数据：同族放宽长度、整数放宽、小数的整数位和小数位都不缩小、文本变成不限长度。 */
export function widensTo(from: ColumnType, to: ColumnType): boolean {
  if (sameType(from, to)) return true;
  switch (from.kind) {
    case "TEXT":
      return (to.kind === "TEXT" && to.length >= from.length) || to.kind === "CLOB";
    case "BYTES":
      return (to.kind === "BYTES" && to.length >= from.length) || to.kind === "BLOB";
    case "SMALLINT":
      return to.kind === "INTEGER" || to.kind === "BIGINT";
    case "INTEGER":
      return to.kind === "BIGINT";
    case "DECIMAL":
      return to.kind === "DECIMAL" && to.scale >= from.scale && to.length - to.scale >= from.length - from.scale;
    default:
      return false;
  }
}

/** 便于报告的写法，例如 `TEXT(40)`、`DECIMAL(13,3)`。 */
export function describeType(t: ColumnType): string {
  if (t.kind === "TEXT" || t.kind === "BYTES") return `${t.kind}(${t.length})`;
  if (t.kind === "DECIMAL") return `${t.kind}(${t.length},${t.scale})`;
  return t.kind;
}

/** 一列；名字是数据库里的列名。 */
export interface ColumnDef {
  readonly name: string;
  readonly type: ColumnType;
  readonly nullable: boolean;
  /** 默认值的 SQL 文本，没有时为空串。 */
  readonly defaultValue: string;
  /** 是否由数据库生成（IDENTITY、AUTO_INCREMENT）。 */
  readonly identity: boolean;
  readonly comment: string;
  /** 改名前的列名，没有时为空串（只在声明里有）。 */
  readonly previousName: string;
}

/** 主键以外的索引；columns 是列名。 */
export interface IndexDef {
  readonly name: string;
  readonly columns: readonly string[];
  readonly unique: boolean;
}

/** 外键；columns 与 referencedColumns 是列名。 */
export interface ForeignKeyDef {
  readonly name: string;
  readonly columns: readonly string[];
  readonly referencedSchema: string;
  readonly referencedTable: string;
  readonly referencedColumns: readonly string[];
}

/** 一张表的结构：来自实体类的声明（{@link declaredTable}），或来自系统目录（{@link SchemaSyntax.read}）。 */
export interface TableDef {
  /** schema，空串表示连接当前的 schema。 */
  readonly schema: string;
  readonly name: string;
  readonly columns: readonly ColumnDef[];
  readonly primaryKey: readonly string[];
  readonly indexes: readonly IndexDef[];
  readonly foreignKeys: readonly ForeignKeyDef[];
  readonly comment: string;
  /** 是否不归本库管，见 `@table(name, { external: true })`。 */
  readonly external: boolean;
}

/** 按列名（不分大小写）找列。 */
export function findColumn(t: TableDef, name: string): ColumnDef | undefined {
  return t.columns.find((c) => c.name.toUpperCase() === name.toUpperCase());
}

/** 报告里用的表名：`schema.name`。 */
export function qualifiedName(t: TableDef): string {
  return t.schema === "" ? t.name : `${t.schema}.${t.name}`;
}

/** 没有声明时文本的长度。 */
const DEFAULT_TEXT = 255;

/**
 * 实体类 table 声明的结构：列来自 `@column`（长度、可空等来自它的第三个参数），主键来自 `@id`，
 * 索引和外键来自 `@table` 的选项。主键列在前，其余按字段名排序，与 Java 的建表语句相同。
 */
export function declaredTable(table: Table<unknown>, naming: NamingStrategy, schemas: (schema: string) => string): TableDef {
  const meta = entityMeta(table);
  if (meta.table === undefined) throw new Error(`${table.name} has no @table`);
  const ids = meta.writes.ids;
  const ordered = [...meta.properties].sort((a, b) => Number(!ids.includes(a.name)) - Number(!ids.includes(b.name)) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  const columns = ordered.map((p) => {
    const o = p.ddl ?? {};
    return Object.freeze({
      name: columnName(p, naming),
      type: typeOf(table, p),
      nullable: !ids.includes(p.name) && o.nullable !== false,
      defaultValue: o.default ?? "",
      identity: p.name === meta.writes.generated,
      comment: o.comment ?? "",
      previousName: o.previousName ?? "",
    });
  });
  const names = (target: Table<unknown>, fields: readonly string[]) =>
    fields.map((f) => {
      const p = entityMeta(target).property(f);
      if (p === undefined) throw new Error(`${target.name} has no column field ${f}`);
      return columnName(p, naming);
    });
  const schemaOf = (declared: string | undefined) => (declared === undefined || declared === "" ? "" : schemas(declared));
  const foreignKeys = (meta.table.foreignKeys ?? []).map((f) => {
    const target = f.references();
    const targetMeta = entityMeta(target);
    if (targetMeta.table === undefined) throw new Error(`${table.name}: ${f.name} references ${target.name}, which has no @table`);
    const referenced = f.referencedColumns ?? targetMeta.writes.ids;
    if (referenced.length !== f.columns.length) throw new Error(`${table.name}: ${f.name} has ${f.columns.length} columns but references ${referenced.length}`);
    return Object.freeze({ name: f.name, columns: names(table, f.columns), referencedSchema: schemaOf(targetMeta.table.schema), referencedTable: targetMeta.table.name, referencedColumns: names(target, referenced) });
  });
  return Object.freeze({
    schema: schemaOf(meta.table.schema),
    name: meta.table.name,
    columns,
    primaryKey: names(table, ids),
    indexes: (meta.table.indexes ?? []).map((i) => Object.freeze({ name: i.name, columns: names(table, i.columns), unique: i.unique ?? false })),
    foreignKeys,
    comment: meta.table.comment ?? "",
    external: meta.table.external ?? false,
  });
}

/** 按列的类型族推断列类型，长度、精度来自 `@column` 的选项。 */
function typeOf(table: Table<unknown>, p: PropertyMeta): ColumnType {
  const o = p.ddl ?? {};
  switch (p.type.kind) {
    case "text":
      return o.lob ? columnType("CLOB") : columnType("TEXT", o.length ?? DEFAULT_TEXT);
    case "bytes":
      return o.lob ? columnType("BLOB") : columnType("BYTES", o.length ?? DEFAULT_TEXT);
    case "decimal":
      return o.precision !== undefined ? columnType("DECIMAL", o.precision, o.scale ?? 0) : columnType("DECIMAL", 18, 2);
    case "integer":
      return columnType(p.type.name === "smallint" ? "SMALLINT" : "INTEGER");
    case "bigint":
      return columnType("BIGINT");
    case "double":
      return columnType("DOUBLE");
    case "boolean":
      return columnType("BOOLEAN");
    case "date":
      return columnType("DATE");
    case "timestamp":
      return columnType("TIMESTAMP");
    default:
      throw new Error(`${table.name}.${p.name} is a ${p.type.name} column, which has no column type`);
  }
}

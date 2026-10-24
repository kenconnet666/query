import { catalogNumber, catalogText, columnType, groupRows, SchemaSyntax, type Catalog, type ColumnDef, type ColumnType, type ForeignKeyDef, type IndexDef, type TableDef } from "@zerodep-dev/query-core";

/** 声明的类型名：名字和括号里的一到两个数字。 */
const TYPE = /^\s*([A-Za-z ]+?)\s*(?:\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\))?\s*$/;

/** 建表语句里的具名外键：`CONSTRAINT 名 FOREIGN KEY (列, ...)`。 */
const FOREIGN_KEY = /CONSTRAINT\s+("?)([^"\s]+)\1\s+FOREIGN\s+KEY\s*\(([^)]*)\)/gi;

/**
 * SQLite 的结构变更写法，与 Java 的 `SqliteSchemaSyntax` 一致：列类型写成 `DECIMAL(13,3)`、`DATE` 这类名字；
 * 自增主键只能是 `INTEGER PRIMARY KEY AUTOINCREMENT`；外键只能写在建表语句里；`ALTER TABLE` 只能加列、改名，
 * 所以改类型、可空、默认值和给已有的表加外键都只报告。DDL 在事务里。系统目录读 `pragma_table_info` 等。
 */
export class SqliteSchemaSyntax extends SchemaSyntax {
  typeName(type: ColumnType): string {
    switch (type.kind) {
      case "TEXT":
        return `VARCHAR(${type.length})`;
      case "CLOB":
        return "TEXT";
      case "DECIMAL":
        return `DECIMAL(${type.length},${type.scale})`;
      case "INTEGER":
      case "BIGINT":
        return "INTEGER";
      case "BYTES":
      case "BLOB":
        return "BLOB";
      default:
        return type.kind;
    }
  }

  /** SQLite 的整数都是 64 位，定长字节存成 BLOB：比较时按这两种类型看。 */
  override stored(declared: ColumnType): ColumnType {
    if (declared.kind === "INTEGER") return columnType("BIGINT");
    if (declared.kind === "BYTES") return columnType("BLOB");
    return declared;
  }

  override get changesTypesInPlace(): boolean {
    return false;
  }

  override get altersColumns(): boolean {
    return false;
  }

  override get inlineForeignKeys(): boolean {
    return true;
  }

  override get storesComments(): boolean {
    return false;
  }

  override get transactionalDdl(): boolean {
    return true;
  }

  /** 自增列写成 `INTEGER PRIMARY KEY AUTOINCREMENT`，这时它就是主键。 */
  override columnDefinition(c: ColumnDef): string {
    let sql = `${this.quote(c.name)} ${c.identity ? "INTEGER PRIMARY KEY AUTOINCREMENT" : this.typeName(c.type)}`;
    if (c.defaultValue !== "") sql += ` DEFAULT ${c.defaultValue}`;
    if (!c.nullable) sql += " NOT NULL";
    return sql;
  }

  /** 有自增列时主键已经写在列定义里；自增列不是唯一的主键列时报错（SQLite 只给单列的整数主键生成值）。 */
  protected override inlinePrimaryKey(t: TableDef): boolean {
    const identity = t.columns.filter((c) => c.identity);
    if (identity.length === 0) return false;
    if (identity.length > 1 || t.primaryKey.length !== 1 || t.primaryKey[0]!.toUpperCase() !== identity[0]!.name.toUpperCase()) {
      throw new Error(`${t.schema === "" ? t.name : `${t.schema}.${t.name}`}: SQLite generates values only for a single-column INTEGER PRIMARY KEY`);
    }
    return true;
  }

  /** SQLite 没有表和列的注释：略过（Java 端第一次略过时记警告；这里没有写入器，不警告）。 */
  protected override comments(_t: TableDef): string[] {
    return [];
  }

  /** 外键的目标表不能带 schema（只能是同一个库里的表）。 */
  protected override foreignKeyClause(f: ForeignKeyDef): string {
    return `CONSTRAINT ${this.quote(f.name)} FOREIGN KEY (${this.names(f.columns)}) REFERENCES ${this.quote(f.referencedTable)} (${this.names(f.referencedColumns)})`;
  }

  /** schema 写在索引名上，表名不带 schema：`CREATE INDEX s.IX ON T (...)`。 */
  override createIndex(t: TableDef, i: IndexDef): string {
    const name = t.schema === "" ? this.quote(i.name) : `${this.quote(t.schema)}.${this.quote(i.name)}`;
    return `CREATE ${i.unique ? "UNIQUE " : ""}INDEX ${name} ON ${this.quote(t.name)} (${this.names(i.columns)})`;
  }

  async read(catalog: Catalog, schema: string, table: string): Promise<TableDef | undefined> {
    const s = schema === "" ? "main" : schema;
    const columns = await catalog('SELECT name, type, "notnull" AS not_null, dflt_value, pk FROM pragma_table_info(?, ?) ORDER BY cid', table, s);
    if (columns.length === 0) return undefined;
    const ddl = await catalog(`SELECT sql FROM ${this.quote(s)}.sqlite_master WHERE type = 'table' AND name = ?`, table);
    const create = catalogText(ddl[0]?.sql);
    const keys = columns.filter((c) => catalogNumber(c.pk) > 0).sort((a, b) => catalogNumber(a.pk) - catalogNumber(b.pk)).map((c) => catalogText(c.name));
    const indexes: IndexDef[] = [];
    for (const i of await catalog('SELECT name, "unique" AS is_unique, origin FROM pragma_index_list(?, ?) ORDER BY name', table, s)) {
      if (catalogText(i.origin) !== "c") continue; // 只看 CREATE INDEX 建的，主键和 UNIQUE 约束自带的索引不算
      const name = catalogText(i.name);
      const cols = (await catalog("SELECT name FROM pragma_index_info(?, ?) ORDER BY seqno", name, s)).map((r) => catalogText(r.name));
      indexes.push({ name, columns: cols, unique: catalogNumber(i.is_unique) === 1 });
    }
    const names = new Map<string, string>();
    for (const m of create.matchAll(FOREIGN_KEY)) names.set(columnsKey(m[3]!.split(",")), m[2]!);
    const foreignRows = await catalog('SELECT id, seq, "table" AS ref_table, "from" AS from_column, "to" AS to_column FROM pragma_foreign_key_list(?, ?) ORDER BY id, seq', table, s);
    return {
      schema,
      name: table,
      columns: columns.map((c) => {
        const name = catalogText(c.name);
        return { name, type: sqliteType(catalogText(c.type)), nullable: catalogNumber(c.not_null) === 0, defaultValue: catalogText(c.dflt_value), identity: identity(create, name), comment: "", previousName: "" };
      }),
      primaryKey: keys,
      indexes,
      foreignKeys: groupRows(foreignRows, (r) => String(r.id), (rows) => {
        const from = rows.map((r) => catalogText(r.from_column));
        return { name: names.get(columnsKey(from)) ?? "", columns: from, referencedSchema: schema, referencedTable: catalogText(rows[0]!.ref_table), referencedColumns: rows.map((r) => catalogText(r.to_column)) };
      }),
      comment: "",
      external: false,
    };
  }
}

/** 列名清单的比较键：去掉引号和空白，不分大小写。 */
function columnsKey(columns: readonly string[]): string {
  return columns.map((c) => c.replaceAll('"', "").trim().toUpperCase()).join(",");
}

/** 这一列在建表语句里是不是 `INTEGER PRIMARY KEY AUTOINCREMENT`。 */
function identity(create: string, column: string): boolean {
  const escaped = column.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`"?${escaped}"?\\s+INTEGER\\s+PRIMARY\\s+KEY\\s+AUTOINCREMENT`, "i").test(create);
}

/** 声明的类型名还原成列类型；不认识的按文本。 */
function sqliteType(declared: string): ColumnType {
  const m = TYPE.exec(declared);
  if (m === null) return columnType("TEXT", 0);
  const name = m[1]!.trim().toUpperCase();
  const a = m[2] === undefined ? 0 : Number(m[2]);
  const b = m[3] === undefined ? 0 : Number(m[3]);
  switch (name) {
    case "VARCHAR":
    case "CHARACTER VARYING":
    case "NVARCHAR":
    case "CHAR":
    case "CHARACTER":
    case "NCHAR":
      return columnType("TEXT", a);
    case "TEXT":
    case "CLOB":
      return columnType("CLOB");
    case "DECIMAL":
    case "NUMERIC":
      return a === 0 ? columnType("DECIMAL", 18, 2) : columnType("DECIMAL", a, b);
    case "SMALLINT":
      return columnType("SMALLINT");
    case "INT":
    case "INTEGER":
    case "BIGINT":
      return columnType("BIGINT");
    case "DOUBLE":
    case "DOUBLE PRECISION":
    case "REAL":
    case "FLOAT":
      return columnType("DOUBLE");
    case "BOOLEAN":
      return columnType("BOOLEAN");
    case "DATE":
      return columnType("DATE");
    case "TIMESTAMP":
    case "DATETIME":
      return columnType("TIMESTAMP");
    case "BLOB":
    case "":
      return columnType("BLOB");
    default:
      return columnType("TEXT", 0);
  }
}

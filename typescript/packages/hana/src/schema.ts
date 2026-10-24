import { catalogDefault, catalogNumber, catalogText, columnType, groupRows, SchemaSyntax, type Catalog, type ColumnDef, type ColumnType, type IndexDef, type TableDef } from "@zerodep-dev/query-core";

/** NVARCHAR 的最大长度。 */
const MAX_NVARCHAR = 5000;

/**
 * HANA 的结构变更写法：默认建列存表；文本用 NVARCHAR（更长的用 NCLOB）；改列写成 `ALTER TABLE t ALTER (整列定义)`；
 * 不能就地缩短列或改类型族；从 SYS 视图读结构。与 Java 端一致。
 */
export class HanaSchemaSyntax extends SchemaSyntax {
  typeName(type: ColumnType): string {
    switch (type.kind) {
      case "TEXT":
        return type.length > MAX_NVARCHAR ? "NCLOB" : `NVARCHAR(${type.length})`;
      case "CLOB":
        return "NCLOB";
      case "DECIMAL":
        return `DECIMAL(${type.length},${type.scale})`;
      case "BYTES":
        return `VARBINARY(${type.length})`;
      default:
        return type.kind;
    }
  }

  /** 超过 5000 的文本存成 NCLOB。 */
  override stored(declared: ColumnType): ColumnType {
    return declared.kind === "TEXT" && declared.length > MAX_NVARCHAR ? columnType("CLOB") : declared;
  }

  /** HANA 不能缩短列或改类型族，只能放宽。 */
  override get changesTypesInPlace(): boolean {
    return false;
  }

  /** HANA 默认建列存表。 */
  protected override get createKeyword(): string {
    return "CREATE COLUMN TABLE";
  }

  override addColumn(t: TableDef, c: ColumnDef): string {
    return `ALTER TABLE ${this.table(t)} ADD (${this.columnDefinition(c)})`;
  }

  override alterType(t: TableDef, c: ColumnDef): string {
    return this.#alter(t, c);
  }

  override dropNotNull(t: TableDef, c: ColumnDef): string {
    return this.#alter(t, c);
  }

  override setNotNull(t: TableDef, c: ColumnDef): string {
    return this.#alter(t, c);
  }

  override setDefault(t: TableDef, c: ColumnDef): string {
    return this.#alter(t, c);
  }

  /** `ALTER TABLE t ALTER (列 类型 [DEFAULT x] [NOT NULL | NULL])`。 */
  #alter(t: TableDef, c: ColumnDef): string {
    return `ALTER TABLE ${this.table(t)} ALTER (${this.quote(c.name)} ${this.typeName(c.type)}${c.defaultValue === "" ? "" : ` DEFAULT ${c.defaultValue}`}${c.nullable ? " NULL" : " NOT NULL"})`;
  }

  override renameColumn(t: TableDef, from: string, to: string): string {
    return `RENAME COLUMN ${this.table(t)}.${this.quote(from)} TO ${this.quote(to)}`;
  }

  /** 索引名在 schema 里唯一，写成 `schema.名字`。 */
  override createIndex(t: TableDef, i: IndexDef): string {
    const name = t.schema === "" ? this.quote(i.name) : `${this.quote(t.schema)}.${this.quote(i.name)}`;
    return `CREATE ${i.unique ? "UNIQUE " : ""}INDEX ${name} ON ${this.table(t)} (${this.names(i.columns)})`;
  }

  async read(catalog: Catalog, schema: string, table: string): Promise<TableDef | undefined> {
    const s = schema === "" ? "CURRENT_SCHEMA" : "?";
    const key = schema === "" ? [table] : [schema, table];
    const columns = await catalog(
      "SELECT COLUMN_NAME, DATA_TYPE_NAME, LENGTH, SCALE, IS_NULLABLE, DEFAULT_VALUE, GENERATION_TYPE, COMMENTS " +
        `FROM SYS.TABLE_COLUMNS WHERE SCHEMA_NAME = ${s} AND TABLE_NAME = ? AND IS_HIDDEN = 'FALSE' ORDER BY POSITION`,
      ...key,
    );
    if (columns.length === 0) return undefined;
    const indexRows = await catalog(`SELECT INDEX_NAME, COLUMN_NAME, "CONSTRAINT" AS KIND FROM SYS.INDEX_COLUMNS WHERE SCHEMA_NAME = ${s} AND TABLE_NAME = ? ORDER BY INDEX_NAME, POSITION`, ...key);
    const foreignRows = await catalog(
      "SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_SCHEMA_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME " +
        `FROM SYS.REFERENTIAL_CONSTRAINTS WHERE SCHEMA_NAME = ${s} AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME, POSITION`,
      ...key,
    );
    const [described] = await catalog(`SELECT COMMENTS FROM SYS.TABLES WHERE SCHEMA_NAME = ${s} AND TABLE_NAME = ?`, ...key);
    return {
      schema,
      name: table,
      columns: columns.map((c) => {
        const type = hanaType(catalogText(c.data_type_name), catalogNumber(c.length), catalogNumber(c.scale));
        return {
        name: catalogText(c.column_name),
        type,
        nullable: c.is_nullable === "TRUE",
        defaultValue: catalogDefault(c.default_value, type),
        identity: catalogText(c.generation_type).includes("IDENTITY"),
        comment: catalogText(c.comments),
        previousName: "",
      };
      }),
      primaryKey: indexRows.filter((i) => i.kind === "PRIMARY KEY").map((i) => catalogText(i.column_name)),
      indexes: groupRows(
        indexRows.filter((i) => i.kind !== "PRIMARY KEY"),
        (r) => catalogText(r.index_name),
        (rows) => ({ name: catalogText(rows[0]!.index_name), columns: rows.map((r) => catalogText(r.column_name)), unique: catalogText(rows[0]!.kind).includes("UNIQUE") }),
      ),
      foreignKeys: groupRows(foreignRows, (r) => catalogText(r.constraint_name), (rows) => ({
        name: catalogText(rows[0]!.constraint_name),
        columns: rows.map((r) => catalogText(r.column_name)),
        referencedSchema: catalogText(rows[0]!.referenced_schema_name),
        referencedTable: catalogText(rows[0]!.referenced_table_name),
        referencedColumns: rows.map((r) => catalogText(r.referenced_column_name)),
      })),
      comment: catalogText(described?.comments),
      external: false,
    };
  }
}

/** SYS.TABLE_COLUMNS 的 DATA_TYPE_NAME 读回列类型。 */
function hanaType(dataType: string, length: number, scale: number): ColumnType {
  switch (dataType) {
    case "NVARCHAR":
    case "VARCHAR":
    case "ALPHANUM":
    case "SHORTTEXT":
    case "NCHAR":
    case "CHAR":
      return columnType("TEXT", length);
    case "NCLOB":
    case "CLOB":
    case "TEXT":
      return columnType("CLOB");
    case "DECIMAL":
    case "SMALLDECIMAL":
      return columnType("DECIMAL", length, scale);
    case "TINYINT":
    case "SMALLINT":
      return columnType("SMALLINT");
    case "INTEGER":
      return columnType("INTEGER");
    case "BIGINT":
      return columnType("BIGINT");
    case "DOUBLE":
    case "REAL":
      return columnType("DOUBLE");
    case "BOOLEAN":
      return columnType("BOOLEAN");
    case "DATE":
      return columnType("DATE");
    case "TIMESTAMP":
    case "SECONDDATE":
      return columnType("TIMESTAMP");
    case "VARBINARY":
    case "BINARY":
      return columnType("BYTES", length);
    case "BLOB":
      return columnType("BLOB");
    default:
      return columnType("TEXT", 0);
  }
}

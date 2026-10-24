import { catalogDefault, catalogNumber, catalogText, columnType, groupRows, SchemaSyntax, type Catalog, type ColumnDef, type ColumnType, type TableDef } from "@zerodep-dev/query-core";

/**
 * MySQL 的结构变更写法：列定义里写 NOT NULL、DEFAULT、AUTO_INCREMENT 和注释，改列用 MODIFY COLUMN 重写整列定义，
 * 从 information_schema 读表结构；与 Java 端一致。
 */
export class MysqlSchemaSyntax extends SchemaSyntax {
  typeName(type: ColumnType): string {
    switch (type.kind) {
      case "TEXT":
        return `VARCHAR(${type.length})`;
      case "CLOB":
        return "LONGTEXT";
      case "DECIMAL":
        return `DECIMAL(${type.length},${type.scale})`;
      case "INTEGER":
        return "INT";
      case "TIMESTAMP":
        return "DATETIME(6)";
      case "BYTES":
        return `VARBINARY(${type.length})`;
      case "BLOB":
        return "LONGBLOB";
      default:
        return type.kind;
    }
  }

  /** MySQL 的列定义顺序：类型、NOT NULL、DEFAULT、AUTO_INCREMENT、COMMENT。 */
  override columnDefinition(c: ColumnDef): string {
    let sql = `${this.quote(c.name)} ${this.typeName(c.type)}`;
    if (!c.nullable) sql += " NOT NULL";
    if (c.defaultValue !== "") sql += ` DEFAULT ${c.defaultValue}`;
    if (c.identity) sql += " AUTO_INCREMENT";
    if (c.comment !== "") sql += ` COMMENT ${this.literal(c.comment)}`;
    return sql;
  }

  /** 表注释写在建表语句末尾。 */
  protected override tableOptions(t: TableDef): string {
    return t.comment === "" ? "" : ` COMMENT=${this.literal(t.comment)}`;
  }

  /** 注释已经写在列定义和表选项里。 */
  protected override comments(): string[] {
    return [];
  }

  override alterType(t: TableDef, c: ColumnDef): string {
    return this.#modify(t, c);
  }

  override dropNotNull(t: TableDef, c: ColumnDef): string {
    return this.#modify(t, c);
  }

  override setNotNull(t: TableDef, c: ColumnDef): string {
    return this.#modify(t, c);
  }

  /** `ALTER TABLE t MODIFY COLUMN 整列定义`。 */
  #modify(t: TableDef, c: ColumnDef): string {
    return `ALTER TABLE ${this.table(t)} MODIFY COLUMN ${this.columnDefinition(c)}`;
  }

  async read(catalog: Catalog, schema: string, table: string): Promise<TableDef | undefined> {
    const s = schema === "" ? "DATABASE()" : "?";
    const key = schema === "" ? [table] : [schema, table];
    const columns = await catalog(
      "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, " +
        `IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ${s} AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION`,
      ...key,
    );
    if (columns.length === 0) return undefined;
    const foreignRows = await catalog(
      "SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME " +
        `FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = ${s} AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION`,
      ...key,
    );
    const foreignKeys = groupRows(foreignRows, (r) => catalogText(r.constraint_name), (rows) => ({
      name: catalogText(rows[0]!.constraint_name),
      columns: rows.map((r) => catalogText(r.column_name)),
      referencedSchema: catalogText(rows[0]!.referenced_table_schema),
      referencedTable: catalogText(rows[0]!.referenced_table_name),
      referencedColumns: rows.map((r) => catalogText(r.referenced_column_name)),
    }));
    // MySQL 为每个外键自动建一个同名索引，不算作声明之外的索引。
    const foreignNames = new Set(foreignKeys.map((f) => f.name));
    const indexRows = await catalog(`SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ${s} AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX`, ...key);
    const [described] = await catalog(`SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ${s} AND TABLE_NAME = ?`, ...key);
    return {
      schema,
      name: table,
      columns: columns.map((c) => {
        const type = mysqlType(catalogText(c.data_type), catalogText(c.column_type), catalogNumber(c.character_maximum_length), catalogNumber(c.numeric_precision), catalogNumber(c.numeric_scale));
        const extra = catalogText(c.extra);
        return {
        name: catalogText(c.column_name),
        type,
        nullable: c.is_nullable === "YES",
        // 表达式默认值（EXTRA 带 DEFAULT_GENERATED）在目录里就是 SQL；常量则去掉了引号。
        defaultValue: extra.includes("DEFAULT_GENERATED") ? catalogText(c.column_default) : catalogDefault(c.column_default, type),
        identity: extra.includes("auto_increment"),
        comment: catalogText(c.column_comment),
        previousName: "",
      };
      }),
      primaryKey: indexRows.filter((i) => i.index_name === "PRIMARY").map((i) => catalogText(i.column_name)),
      indexes: groupRows(
        indexRows.filter((i) => i.index_name !== "PRIMARY" && !foreignNames.has(catalogText(i.index_name))),
        (r) => catalogText(r.index_name),
        (rows) => ({ name: catalogText(rows[0]!.index_name), columns: rows.map((r) => catalogText(r.column_name)), unique: catalogNumber(rows[0]!.non_unique) === 0 }),
      ),
      foreignKeys,
      comment: catalogText(described?.table_comment),
      external: false,
    };
  }
}

/** information_schema 的 DATA_TYPE 读回列类型；tinyint(1) 是 BOOLEAN。 */
function mysqlType(dataType: string, columnTypeText: string, length: number, precision: number, scale: number): ColumnType {
  switch (dataType) {
    case "varchar":
    case "char":
      return columnType("TEXT", length);
    case "text":
    case "tinytext":
    case "mediumtext":
    case "longtext":
      return columnType("CLOB");
    case "decimal":
      return columnType("DECIMAL", precision, scale);
    case "tinyint":
      return columnTypeText.startsWith("tinyint(1)") ? columnType("BOOLEAN") : columnType("SMALLINT");
    case "smallint":
      return columnType("SMALLINT");
    case "int":
    case "mediumint":
      return columnType("INTEGER");
    case "bigint":
      return columnType("BIGINT");
    case "double":
    case "float":
      return columnType("DOUBLE");
    case "date":
      return columnType("DATE");
    case "datetime":
    case "timestamp":
      return columnType("TIMESTAMP");
    case "varbinary":
    case "binary":
      return columnType("BYTES", length);
    case "blob":
    case "tinyblob":
    case "mediumblob":
    case "longblob":
      return columnType("BLOB");
    default:
      return columnType("TEXT", 0);
  }
}

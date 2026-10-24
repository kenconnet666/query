import { catalogNumber, catalogText, columnType, withoutCast, groupRows, SchemaSyntax, type Catalog, type ColumnType, type TableDef } from "@zerodep-dev/query-core";

/** PostgreSQL 的结构变更写法：类型名、ALTER COLUMN 的写法，以及从 information_schema 和 pg_catalog 读表结构；与 Java 端一致。 */
export class PostgresSchemaSyntax extends SchemaSyntax {
  typeName(type: ColumnType): string {
    switch (type.kind) {
      case "TEXT":
        return `VARCHAR(${type.length})`;
      case "CLOB":
        return "TEXT";
      case "DECIMAL":
        return `NUMERIC(${type.length},${type.scale})`;
      case "DOUBLE":
        return "DOUBLE PRECISION";
      case "TIMESTAMP":
        return "TIMESTAMP";
      case "BYTES":
      case "BLOB":
        return "BYTEA";
      default:
        return type.kind;
    }
  }

  /** DDL 能在事务里回滚。 */
  override get transactionalDdl(): boolean {
    return true;
  }

  /** 定长字节也存成不限长度的 BYTEA。 */
  override stored(declared: ColumnType): ColumnType {
    return declared.kind === "BYTES" ? columnType("BLOB") : declared;
  }

  async read(catalog: Catalog, schema: string, table: string): Promise<TableDef | undefined> {
    const s = schema === "" ? "current_schema()" : this.parameter(1);
    const t = this.parameter(schema === "" ? 1 : 2);
    const key = schema === "" ? [table] : [schema, table];
    const columns = await catalog(
      "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, " +
        "column_default, is_identity, col_description(format('%I.%I', table_schema, table_name)::regclass, ordinal_position) AS column_comment " +
        `FROM information_schema.columns WHERE table_schema = ${s} AND table_name = ${t} ORDER BY ordinal_position`,
      ...key,
    );
    if (columns.length === 0) return undefined;
    const indexRows = await catalog(
      "SELECT i.relname AS index_name, ix.indisunique AS is_unique, ix.indisprimary AS is_primary, a.attname AS column_name " +
        "FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace JOIN pg_index ix ON ix.indrelid = t.oid JOIN pg_class i ON i.oid = ix.indexrelid " +
        `JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) WHERE n.nspname = ${s} AND t.relname = ${t} ` +
        "ORDER BY i.relname, array_position(ix.indkey::int2[], a.attnum)",
      ...key,
    );
    const foreignRows = await catalog(
      "SELECT c.conname, a.attname AS column_name, rn.nspname AS ref_schema, rt.relname AS ref_table, ra.attname AS ref_column " +
        "FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace " +
        "JOIN pg_class rt ON rt.oid = c.confrelid JOIN pg_namespace rn ON rn.oid = rt.relnamespace " +
        "CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(col, refcol, ord) " +
        "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.col JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = k.refcol " +
        `WHERE c.contype = 'f' AND n.nspname = ${s} AND t.relname = ${t} ORDER BY c.conname, k.ord`,
      ...key,
    );
    const [described] = await catalog(
      `SELECT obj_description(t.oid, 'pg_class') AS table_comment FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = ${s} AND t.relname = ${t}`,
      ...key,
    );
    return {
      schema,
      name: table,
      columns: columns.map((c) => ({
        name: catalogText(c.column_name),
        type: pgType(catalogText(c.data_type), catalogNumber(c.character_maximum_length), catalogNumber(c.numeric_precision), catalogNumber(c.numeric_scale)),
        nullable: c.is_nullable === "YES",
        defaultValue: withoutCast(c.column_default),
        identity: c.is_identity === "YES",
        comment: catalogText(c.column_comment),
        previousName: "",
      })),
      primaryKey: indexRows.filter((i) => i.is_primary === true).map((i) => catalogText(i.column_name)),
      indexes: groupRows(
        indexRows.filter((i) => i.is_primary !== true),
        (r) => catalogText(r.index_name),
        (rows) => ({ name: catalogText(rows[0]!.index_name), columns: rows.map((r) => catalogText(r.column_name)), unique: rows[0]!.is_unique === true }),
      ),
      foreignKeys: groupRows(foreignRows, (r) => catalogText(r.conname), (rows) => ({
        name: catalogText(rows[0]!.conname),
        columns: rows.map((r) => catalogText(r.column_name)),
        referencedSchema: catalogText(rows[0]!.ref_schema),
        referencedTable: catalogText(rows[0]!.ref_table),
        referencedColumns: rows.map((r) => catalogText(r.ref_column)),
      })),
      comment: catalogText(described?.table_comment),
      external: false,
    };
  }
}

/** information_schema 的 data_type 读回列类型。 */
function pgType(dataType: string, length: number, precision: number, scale: number): ColumnType {
  switch (dataType) {
    case "character varying":
    case "character":
      return columnType("TEXT", length);
    case "text":
      return columnType("CLOB");
    case "numeric":
      return columnType("DECIMAL", precision, scale);
    case "smallint":
      return columnType("SMALLINT");
    case "integer":
      return columnType("INTEGER");
    case "bigint":
      return columnType("BIGINT");
    case "double precision":
    case "real":
      return columnType("DOUBLE");
    case "boolean":
      return columnType("BOOLEAN");
    case "date":
      return columnType("DATE");
    case "bytea":
      return columnType("BLOB");
    default:
      return dataType.startsWith("timestamp") ? columnType("TIMESTAMP") : columnType("TEXT", 0);
  }
}

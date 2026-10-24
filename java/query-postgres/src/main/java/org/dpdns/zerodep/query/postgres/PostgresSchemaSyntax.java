package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.ddl.ColumnType;
import org.dpdns.zerodep.query.ddl.ColumnType.Kind;
import org.dpdns.zerodep.query.ddl.SchemaSyntax;
import org.dpdns.zerodep.query.ddl.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL 的结构变更写法：类型名、ALTER COLUMN 的写法，以及从 information_schema 和 pg_catalog 读表结构。 */
final class PostgresSchemaSyntax extends SchemaSyntax {
    PostgresSchemaSyntax(PostgresDialect dialect) { super(dialect); }

    @Override public String typeName(ColumnType type) {
        return switch (type.kind()) {
            case TEXT -> "VARCHAR(" + type.length() + ")";
            case CLOB -> "TEXT";
            case DECIMAL -> "NUMERIC(" + type.length() + "," + type.scale() + ")";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case DOUBLE -> "DOUBLE PRECISION";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case BYTES, BLOB -> "BYTEA";
        };
    }

    /** DDL 能在事务里回滚。 */
    @Override public boolean transactionalDdl() { return true; }

    /** 定长字节也存成不限长度的 BYTEA。 */
    @Override public ColumnType stored(ColumnType declared) { return declared.kind() == Kind.BYTES ? ColumnType.of(Kind.BLOB) : declared; }

    @Override public TableDef read(Catalog catalog, String schema, String table) {
        String s = schema.isEmpty() ? "current_schema()" : "?";
        Object[] key = schema.isEmpty() ? new Object[] {table} : new Object[] {schema, table};
        List<Map<String, Object>> columns = catalog.rows("SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, "
            + "column_default, is_identity FROM information_schema.columns WHERE table_schema = " + s + " AND table_name = ? ORDER BY ordinal_position", key);
        if (columns.isEmpty()) return null;
        List<TableDef.Column> out = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            out.add(new TableDef.Column(text(c.get("column_name")), type(text(c.get("data_type")), number(c.get("character_maximum_length")), number(c.get("numeric_precision")),
                number(c.get("numeric_scale"))), "YES".equals(c.get("is_nullable")), text(c.get("column_default")), "YES".equals(c.get("is_identity")), "", ""));
        }
        List<String> primaryKey = new ArrayList<>();
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (Map<String, Object> i : catalog.rows("SELECT i.relname AS index_name, ix.indisunique AS is_unique, ix.indisprimary AS is_primary, a.attname AS column_name "
            + "FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace JOIN pg_index ix ON ix.indrelid = t.oid JOIN pg_class i ON i.oid = ix.indexrelid "
            + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) WHERE n.nspname = " + s + " AND t.relname = ? "
            + "ORDER BY i.relname, array_position(ix.indkey::int2[], a.attnum)", key)) {
            if (Boolean.TRUE.equals(i.get("is_primary"))) primaryKey.add(text(i.get("column_name")));
            else {
                indexColumns.computeIfAbsent(text(i.get("index_name")), k -> new ArrayList<>()).add(text(i.get("column_name")));
                unique.put(text(i.get("index_name")), Boolean.TRUE.equals(i.get("is_unique")));
            }
        }
        List<TableDef.Index> indexes = indexColumns.entrySet().stream().map(e -> new TableDef.Index(e.getKey(), e.getValue(), unique.get(e.getKey()))).toList();
        Map<String, TableDef.ForeignKey> foreignKeys = new LinkedHashMap<>();
        for (Map<String, Object> f : catalog.rows("SELECT c.conname, a.attname AS column_name, rn.nspname AS ref_schema, rt.relname AS ref_table, ra.attname AS ref_column "
            + "FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid JOIN pg_namespace n ON n.oid = t.relnamespace "
            + "JOIN pg_class rt ON rt.oid = c.confrelid JOIN pg_namespace rn ON rn.oid = rt.relnamespace "
            + "CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(col, refcol, ord) "
            + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.col JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = k.refcol "
            + "WHERE c.contype = 'f' AND n.nspname = " + s + " AND t.relname = ? ORDER BY c.conname, k.ord", key)) {
            String name = text(f.get("conname"));
            TableDef.ForeignKey known = foreignKeys.get(name);
            List<String> cols = new ArrayList<>(known == null ? List.of() : known.columns());
            List<String> refs = new ArrayList<>(known == null ? List.of() : known.referencedColumns());
            cols.add(text(f.get("column_name")));
            refs.add(text(f.get("ref_column")));
            foreignKeys.put(name, new TableDef.ForeignKey(name, cols, text(f.get("ref_schema")), text(f.get("ref_table")), refs));
        }
        return new TableDef(schema, table, out, primaryKey, indexes, List.copyOf(foreignKeys.values()), "", false);
    }

    /** information_schema 的 data_type 读回列类型。 */
    private static ColumnType type(String dataType, int length, int precision, int scale) {
        return switch (dataType) {
            case "character varying", "character" -> ColumnType.text(length);
            case "text" -> ColumnType.of(Kind.CLOB);
            case "numeric" -> ColumnType.decimal(precision, scale);
            case "smallint" -> ColumnType.of(Kind.SMALLINT);
            case "integer" -> ColumnType.of(Kind.INTEGER);
            case "bigint" -> ColumnType.of(Kind.BIGINT);
            case "double precision", "real" -> ColumnType.of(Kind.DOUBLE);
            case "boolean" -> ColumnType.of(Kind.BOOLEAN);
            case "date" -> ColumnType.of(Kind.DATE);
            case "bytea" -> ColumnType.of(Kind.BLOB);
            default -> dataType.startsWith("timestamp") ? ColumnType.of(Kind.TIMESTAMP) : ColumnType.text(0);
        };
    }
}

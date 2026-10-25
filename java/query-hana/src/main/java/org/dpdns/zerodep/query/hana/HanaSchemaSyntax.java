package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.ddl.ColumnType.Kind;
import org.dpdns.zerodep.query.ddl.ColumnType;
import org.dpdns.zerodep.query.ddl.SchemaSyntax;
import org.dpdns.zerodep.query.ddl.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HANA 的结构变更写法（前身仓库在 2.00.088 上实测过的边界）：默认建列存表；文本用 NVARCHAR（上限 5000，更长的用 NCLOB）；
 * 改列写成 {@code ALTER TABLE t ALTER (整列定义)}；从 SYS.TABLE_COLUMNS、SYS.INDEX_COLUMNS、SYS.REFERENTIAL_CONSTRAINTS 读结构。
 * DDL 默认自动提交。
 */
final class HanaSchemaSyntax extends SchemaSyntax {
    /** NVARCHAR 的最大长度。 */
    private static final int MAX_NVARCHAR = 5000;

    HanaSchemaSyntax(HanaDialect dialect) { super(dialect); }

    @Override public String typeName(ColumnType type) {
        return switch (type.kind()) {
            case TEXT -> type.length() > MAX_NVARCHAR ? "NCLOB" : "NVARCHAR(" + type.length() + ")";
            case CLOB -> "NCLOB";
            case DECIMAL -> "DECIMAL(" + type.length() + "," + type.scale() + ")";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case BYTES -> "VARBINARY(" + type.length() + ")";
            case BLOB -> "BLOB";
        };
    }

    /** 超过 5000 的文本存成 NCLOB。 */
    @Override public ColumnType stored(ColumnType declared) {
        return declared.kind() == Kind.TEXT && declared.length() > MAX_NVARCHAR ? ColumnType.of(Kind.CLOB) : declared;
    }

    /** HANA 不能缩短列或改类型族，只能放宽。 */
    @Override public boolean changesTypesInPlace() { return false; }

    /** HANA 默认建列存表。 */
    @Override protected String createKeyword() { return "CREATE COLUMN TABLE"; }

    @Override public String addColumn(TableDef t, TableDef.Column c) { return "ALTER TABLE " + table(t) + " ADD (" + columnDefinition(c) + ")"; }

    /** 改类型、可空和默认值都写成重写整列定义的 ALTER。 */
    @Override public String alterType(TableDef t, TableDef.Column c) { return alter(t, c); }

    @Override public String dropNotNull(TableDef t, TableDef.Column c) { return alter(t, c); }

    @Override public String setNotNull(TableDef t, TableDef.Column c) { return alter(t, c); }

    @Override public String setDefault(TableDef t, TableDef.Column c) { return alter(t, c); }

    /** {@code ALTER TABLE t ALTER (列 类型 [DEFAULT x] [NOT NULL | NULL])}；不写 NOT NULL 时显式写 NULL，免得保留原来的约束。 */
    private String alter(TableDef t, TableDef.Column c) {
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(table(t)).append(" ALTER (").append(quote(c.name())).append(" ").append(typeName(c.type()));
        if (!c.defaultValue().isEmpty()) sql.append(" DEFAULT ").append(c.defaultValue());
        return sql.append(c.nullable() ? " NULL" : " NOT NULL").append(")").toString();
    }

    @Override public String renameColumn(TableDef t, String from, String to) { return "RENAME COLUMN " + table(t) + "." + quote(from) + " TO " + quote(to); }

    /** 索引名在 schema 里唯一，写成 {@code schema.名字}。 */
    @Override public String createIndex(TableDef t, TableDef.Index i) {
        String name = t.schema().isEmpty() ? quote(i.name()) : quote(t.schema()) + "." + quote(i.name());
        return "CREATE " + (i.unique() ? "UNIQUE " : "") + "INDEX " + name + " ON " + table(t) + " (" + names(i.columns()) + ")";
    }

    @Override public TableDef read(Catalog catalog, String schema, String table) {
        String s = schema.isEmpty() ? "CURRENT_SCHEMA" : "?";
        Object[] key = schema.isEmpty() ? new Object[] {table} : new Object[] {schema, table};
        List<Map<String, Object>> columns = catalog.rows("SELECT COLUMN_NAME, DATA_TYPE_NAME, LENGTH, SCALE, IS_NULLABLE, DEFAULT_VALUE, GENERATION_TYPE, COMMENTS "
            + "FROM SYS.TABLE_COLUMNS WHERE SCHEMA_NAME = " + s + " AND TABLE_NAME = ? AND IS_HIDDEN = 'FALSE' ORDER BY POSITION", key);
        if (columns.isEmpty()) return null;
        List<TableDef.Column> out = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            ColumnType type = type(text(c.get("data_type_name")), number(c.get("length")), number(c.get("scale")));
            out.add(new TableDef.Column(text(c.get("column_name")), type, "TRUE".equals(c.get("is_nullable")), sqlDefault(text(c.get("default_value")), type), text(c.get("generation_type")).contains("IDENTITY"), text(c.get("comments")), ""));
        }
        List<String> primaryKey = new ArrayList<>();
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (Map<String, Object> i : catalog.rows("SELECT INDEX_NAME, COLUMN_NAME, \"CONSTRAINT\" AS KIND FROM SYS.INDEX_COLUMNS "
            + "WHERE SCHEMA_NAME = " + s + " AND TABLE_NAME = ? ORDER BY INDEX_NAME, POSITION", key)) {
            String kind = text(i.get("kind"));
            if (kind.equals("PRIMARY KEY")) primaryKey.add(text(i.get("column_name")));
            else {
                indexColumns.computeIfAbsent(text(i.get("index_name")), k -> new ArrayList<>()).add(text(i.get("column_name")));
                unique.put(text(i.get("index_name")), kind.contains("UNIQUE"));
            }
        }
        List<TableDef.Index> indexes = indexColumns.entrySet().stream().map(e -> new TableDef.Index(e.getKey(), e.getValue(), unique.get(e.getKey()))).toList();
        Map<String, TableDef.ForeignKey> foreignKeys = new LinkedHashMap<>();
        for (Map<String, Object> f : catalog.rows("SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_SCHEMA_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
            + "FROM SYS.REFERENTIAL_CONSTRAINTS WHERE SCHEMA_NAME = " + s + " AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME, POSITION", key)) {
            String name = text(f.get("constraint_name"));
            TableDef.ForeignKey known = foreignKeys.get(name);
            List<String> cols = new ArrayList<>(known == null ? List.of() : known.columns());
            List<String> refs = new ArrayList<>(known == null ? List.of() : known.referencedColumns());
            cols.add(text(f.get("column_name")));
            refs.add(text(f.get("referenced_column_name")));
            foreignKeys.put(name, new TableDef.ForeignKey(name, cols, text(f.get("referenced_schema_name")), text(f.get("referenced_table_name")), refs));
        }
        String comment = text(catalog.rows("SELECT COMMENTS FROM SYS.TABLES WHERE SCHEMA_NAME = " + s + " AND TABLE_NAME = ?", key).get(0).get("comments"));
        return new TableDef(schema, table, out, primaryKey, indexes, List.copyOf(foreignKeys.values()), comment, false);
    }

    /** SYS.TABLE_COLUMNS 的 DATA_TYPE_NAME 读回列类型；裸 DECIMAL（浮点小数）的 SCALE 为 NULL，读成小数位 0。 */
    private static ColumnType type(String dataType, int length, int scale) {
        return switch (dataType) {
            case "NVARCHAR", "VARCHAR", "ALPHANUM", "SHORTTEXT", "NCHAR", "CHAR" -> ColumnType.text(length);
            case "NCLOB", "CLOB", "TEXT" -> ColumnType.of(Kind.CLOB);
            case "DECIMAL", "SMALLDECIMAL" -> ColumnType.decimal(length, scale);
            case "TINYINT", "SMALLINT" -> ColumnType.of(Kind.SMALLINT);
            case "INTEGER" -> ColumnType.of(Kind.INTEGER);
            case "BIGINT" -> ColumnType.of(Kind.BIGINT);
            case "DOUBLE", "REAL" -> ColumnType.of(Kind.DOUBLE);
            case "BOOLEAN" -> ColumnType.of(Kind.BOOLEAN);
            case "DATE" -> ColumnType.of(Kind.DATE);
            case "TIMESTAMP", "SECONDDATE" -> ColumnType.of(Kind.TIMESTAMP);
            case "VARBINARY", "BINARY" -> new ColumnType(Kind.BYTES, length, 0);
            case "BLOB" -> ColumnType.of(Kind.BLOB);
            default -> ColumnType.text(0);
        };
    }
}

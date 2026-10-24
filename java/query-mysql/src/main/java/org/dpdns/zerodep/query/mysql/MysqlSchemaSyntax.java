package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.ddl.ColumnType;
import org.dpdns.zerodep.query.ddl.ColumnType.Kind;
import org.dpdns.zerodep.query.ddl.SchemaSyntax;
import org.dpdns.zerodep.query.ddl.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MySQL 的结构变更写法：列定义里写 NOT NULL、DEFAULT、AUTO_INCREMENT 和注释，改列用 MODIFY COLUMN 重写整列定义，
 * 从 information_schema 读表结构。每条 DDL 隐式提交。
 */
final class MysqlSchemaSyntax extends SchemaSyntax {
    MysqlSchemaSyntax(MysqlDialect dialect) { super(dialect); }

    @Override public String typeName(ColumnType type) {
        return switch (type.kind()) {
            case TEXT -> "VARCHAR(" + type.length() + ")";
            case CLOB -> "LONGTEXT";
            case DECIMAL -> "DECIMAL(" + type.length() + "," + type.scale() + ")";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INT";
            case BIGINT -> "BIGINT";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIMESTAMP -> "DATETIME(6)";
            case BYTES -> "VARBINARY(" + type.length() + ")";
            case BLOB -> "LONGBLOB";
        };
    }

    /** MySQL 的列定义顺序：类型、NOT NULL、DEFAULT、AUTO_INCREMENT、COMMENT。 */
    @Override public String columnDefinition(TableDef.Column c) {
        StringBuilder sql = new StringBuilder(quote(c.name())).append(" ").append(typeName(c.type()));
        if (!c.nullable()) sql.append(" NOT NULL");
        if (!c.defaultValue().isEmpty()) sql.append(" DEFAULT ").append(c.defaultValue());
        if (c.identity()) sql.append(" AUTO_INCREMENT");
        if (!c.comment().isEmpty()) sql.append(" COMMENT ").append(literal(c.comment()));
        return sql.toString();
    }

    /** 表注释写在建表语句末尾。 */
    @Override protected String tableOptions(TableDef t) { return t.comment().isEmpty() ? "" : " COMMENT=" + literal(t.comment()); }

    /** 注释已经写在列定义和表选项里。 */
    @Override protected List<String> comments(TableDef t) { return List.of(); }

    /** 改类型、可空都要重写整列定义。 */
    @Override public String alterType(TableDef t, TableDef.Column c) { return modify(t, c); }

    @Override public String dropNotNull(TableDef t, TableDef.Column c) { return modify(t, c); }

    @Override public String setNotNull(TableDef t, TableDef.Column c) { return modify(t, c); }

    /** {@code ALTER TABLE t MODIFY COLUMN 整列定义}。 */
    private String modify(TableDef t, TableDef.Column c) { return "ALTER TABLE " + table(t) + " MODIFY COLUMN " + columnDefinition(c); }

    @Override public TableDef read(Catalog catalog, String schema, String table) {
        String s = schema.isEmpty() ? "DATABASE()" : "?";
        Object[] key = schema.isEmpty() ? new Object[] {table} : new Object[] {schema, table};
        List<Map<String, Object>> columns = catalog.rows("SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, "
            + "IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = " + s + " AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION", key);
        if (columns.isEmpty()) return null;
        List<TableDef.Column> out = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            out.add(new TableDef.Column(text(c.get("column_name")), type(text(c.get("data_type")), text(c.get("column_type")), number(c.get("character_maximum_length")),
                number(c.get("numeric_precision")), number(c.get("numeric_scale"))), "YES".equals(c.get("is_nullable")), text(c.get("column_default")),
                text(c.get("extra")).contains("auto_increment"), text(c.get("column_comment")), ""));
        }
        Map<String, TableDef.ForeignKey> foreignKeys = new LinkedHashMap<>();
        for (Map<String, Object> f : catalog.rows("SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
            + "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = " + s + " AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL "
            + "ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION", key)) {
            String name = text(f.get("constraint_name"));
            TableDef.ForeignKey known = foreignKeys.get(name);
            List<String> cols = new ArrayList<>(known == null ? List.of() : known.columns());
            List<String> refs = new ArrayList<>(known == null ? List.of() : known.referencedColumns());
            cols.add(text(f.get("column_name")));
            refs.add(text(f.get("referenced_column_name")));
            foreignKeys.put(name, new TableDef.ForeignKey(name, cols, text(f.get("referenced_table_schema")), text(f.get("referenced_table_name")), refs));
        }
        // MySQL 为每个外键自动建一个同名索引，不算作声明之外的索引。
        Set<String> foreignKeyNames = foreignKeys.keySet();
        List<String> primaryKey = new ArrayList<>();
        Map<String, List<String>> indexColumns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (Map<String, Object> i : catalog.rows("SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS "
            + "WHERE TABLE_SCHEMA = " + s + " AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX", key)) {
            String name = text(i.get("index_name"));
            if (name.equals("PRIMARY")) primaryKey.add(text(i.get("column_name")));
            else if (!foreignKeyNames.contains(name)) {
                indexColumns.computeIfAbsent(name, k -> new ArrayList<>()).add(text(i.get("column_name")));
                unique.put(name, number(i.get("non_unique")) == 0);
            }
        }
        List<TableDef.Index> indexes = indexColumns.entrySet().stream().map(e -> new TableDef.Index(e.getKey(), e.getValue(), unique.get(e.getKey()))).toList();
        return new TableDef(schema, table, out, primaryKey, indexes, List.copyOf(foreignKeys.values()), "", false);
    }

    /** information_schema 的 DATA_TYPE 读回列类型；tinyint(1) 是 BOOLEAN。 */
    private static ColumnType type(String dataType, String columnType, int length, int precision, int scale) {
        return switch (dataType) {
            case "varchar", "char" -> ColumnType.text(length);
            case "text", "tinytext", "mediumtext", "longtext" -> ColumnType.of(Kind.CLOB);
            case "decimal" -> ColumnType.decimal(precision, scale);
            case "tinyint" -> columnType.startsWith("tinyint(1)") ? ColumnType.of(Kind.BOOLEAN) : ColumnType.of(Kind.SMALLINT);
            case "smallint" -> ColumnType.of(Kind.SMALLINT);
            case "int", "mediumint" -> ColumnType.of(Kind.INTEGER);
            case "bigint" -> ColumnType.of(Kind.BIGINT);
            case "double", "float" -> ColumnType.of(Kind.DOUBLE);
            case "date" -> ColumnType.of(Kind.DATE);
            case "datetime", "timestamp" -> ColumnType.of(Kind.TIMESTAMP);
            case "varbinary", "binary" -> new ColumnType(Kind.BYTES, length, 0);
            case "blob", "tinyblob", "mediumblob", "longblob" -> ColumnType.of(Kind.BLOB);
            default -> ColumnType.text(0);
        };
    }
}

package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.ddl.ColumnType.Kind;
import org.dpdns.zerodep.query.ddl.ColumnType;
import org.dpdns.zerodep.query.ddl.SchemaSyntax;
import org.dpdns.zerodep.query.ddl.TableDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLite 的结构变更写法。列类型写成 {@code DECIMAL(13,3)}、{@code DATE} 这类名字（SQLite 按名字决定亲和，目录里也读得回来）；
 * 自增主键只能是 {@code INTEGER PRIMARY KEY AUTOINCREMENT}；外键只能写在建表语句里；{@code ALTER TABLE} 只能加列、改名，
 * 所以改类型、可空、默认值和给已有的表加外键都只报告。DDL 在事务里。系统目录读 {@code pragma_table_info} 等。
 */
final class SqliteSchemaSyntax extends SchemaSyntax {
    /** 声明的类型名：名字和括号里的一到两个数字。 */
    private static final Pattern TYPE = Pattern.compile("\\s*([A-Za-z ]+?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?\\s*");

    /** 建表语句里的具名外键：{@code CONSTRAINT 名 FOREIGN KEY (列, ...)}。 */
    private static final Pattern FOREIGN_KEY = Pattern.compile("CONSTRAINT\\s+(\"?)([^\"\\s]+)\\1\\s+FOREIGN\\s+KEY\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private final SqliteDialect sqlite;

    SqliteSchemaSyntax(SqliteDialect dialect) {
        super(dialect);
        this.sqlite = dialect;
    }

    @Override public String typeName(ColumnType type) {
        return switch (type.kind()) {
            case TEXT -> "VARCHAR(" + type.length() + ")";
            case CLOB -> "TEXT";
            case DECIMAL -> "DECIMAL(" + type.length() + "," + type.scale() + ")";
            case SMALLINT -> "SMALLINT";
            case INTEGER, BIGINT -> "INTEGER";
            case DOUBLE -> "DOUBLE";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIMESTAMP -> "TIMESTAMP";
            case BYTES, BLOB -> "BLOB";
        };
    }

    /** SQLite 的整数都是 64 位，定长字节存成 BLOB：比较时按这两种类型看。 */
    @Override public ColumnType stored(ColumnType declared) {
        return switch (declared.kind()) {
            case INTEGER -> ColumnType.of(Kind.BIGINT);
            case BYTES -> ColumnType.of(Kind.BLOB);
            default -> declared;
        };
    }

    @Override public boolean changesTypesInPlace() { return false; }

    @Override public boolean altersColumns() { return false; }

    @Override public boolean inlineForeignKeys() { return true; }

    @Override public boolean storesComments() { return false; }

    @Override public boolean transactionalDdl() { return true; }

    /** 自增列写成 {@code INTEGER PRIMARY KEY AUTOINCREMENT}，这时它就是主键。 */
    @Override public String columnDefinition(TableDef.Column c) {
        StringBuilder sql = new StringBuilder(quote(c.name())).append(" ");
        sql.append(c.identity() ? "INTEGER PRIMARY KEY AUTOINCREMENT" : typeName(c.type()));
        if (!c.defaultValue().isEmpty()) sql.append(" DEFAULT ").append(c.defaultValue());
        if (!c.nullable()) sql.append(" NOT NULL");
        return sql.toString();
    }

    /** 有自增列时主键已经写在列定义里；自增列不是唯一的主键列时报错（SQLite 只给单列的整数主键生成值）。 */
    @Override protected boolean inlinePrimaryKey(TableDef t) {
        List<TableDef.Column> identity = t.columns().stream().filter(TableDef.Column::identity).toList();
        if (identity.isEmpty()) return false;
        if (identity.size() > 1 || t.primaryKey().size() != 1 || !t.primaryKey().get(0).equalsIgnoreCase(identity.get(0).name())) {
            throw new IllegalArgumentException(t.qualifiedName() + ": SQLite generates values only for a single-column INTEGER PRIMARY KEY");
        }
        return true;
    }

    /** SQLite 没有表和列的注释：略过，第一次略过时记一条警告。 */
    @Override protected List<String> comments(TableDef t) {
        boolean any = !t.comment().isEmpty() || t.columns().stream().anyMatch(c -> !c.comment().isEmpty());
        if (any) sqlite.warn("comments", () -> "SQLite has no table or column comments; " + t.qualifiedName() + " is created without them");
        return List.of();
    }

    /** 外键的目标表不能带 schema（只能是同一个库里的表）。 */
    @Override protected String foreignKeyClause(TableDef.ForeignKey f) {
        return "CONSTRAINT " + quote(f.name()) + " FOREIGN KEY (" + names(f.columns()) + ") REFERENCES " + quote(f.referencedTable())
            + " (" + names(f.referencedColumns()) + ")";
    }

    /** schema 写在索引名上，表名不带 schema：{@code CREATE INDEX s.IX ON T (...)}。 */
    @Override public String createIndex(TableDef t, TableDef.Index i) {
        String name = t.schema().isEmpty() ? quote(i.name()) : quote(t.schema()) + "." + quote(i.name());
        return "CREATE " + (i.unique() ? "UNIQUE " : "") + "INDEX " + name + " ON " + quote(t.name()) + " (" + names(i.columns()) + ")";
    }

    @Override public TableDef read(Catalog catalog, String schema, String table) {
        String s = schema.isEmpty() ? "main" : schema;
        List<Map<String, Object>> columns = catalog.rows(
            "SELECT name, type, \"notnull\" AS not_null, dflt_value, pk FROM pragma_table_info(?, ?) ORDER BY cid", table, s);
        if (columns.isEmpty()) return null;
        List<Map<String, Object>> ddl = catalog.rows("SELECT sql FROM " + quote(s) + ".sqlite_master WHERE type = 'table' AND name = ?", table);
        String create = ddl.isEmpty() ? "" : text(ddl.get(0).get("sql"));
        List<TableDef.Column> out = new ArrayList<>();
        Map<Integer, String> keys = new java.util.TreeMap<>();
        for (Map<String, Object> c : columns) {
            String name = text(c.get("name"));
            ColumnType type = type(text(c.get("type")));
            out.add(new TableDef.Column(name, type, number(c.get("not_null")) == 0, text(c.get("dflt_value")), identity(create, name), "", ""));
            if (number(c.get("pk")) > 0) keys.put(number(c.get("pk")), name);
        }
        List<TableDef.Index> indexes = new ArrayList<>();
        for (Map<String, Object> i : catalog.rows("SELECT name, \"unique\" AS is_unique, origin FROM pragma_index_list(?, ?) ORDER BY name", table, s)) {
            if (!"c".equals(text(i.get("origin")))) continue;   // 只看 CREATE INDEX 建的，主键和 UNIQUE 约束自带的索引不算
            String name = text(i.get("name"));
            List<String> cols = catalog.rows("SELECT name FROM pragma_index_info(?, ?) ORDER BY seqno", name, s).stream().map(r -> text(r.get("name"))).toList();
            indexes.add(new TableDef.Index(name, cols, number(i.get("is_unique")) == 1));
        }
        return new TableDef(schema, table, out, List.copyOf(keys.values()), indexes, foreignKeys(catalog, s, schema, table, create), "", false);
    }

    /** 外键：列来自 {@code pragma_foreign_key_list}，名字只保存在建表语句里，按列对上。 */
    private static List<TableDef.ForeignKey> foreignKeys(Catalog catalog, String s, String schema, String table, String create) {
        Map<String, String> names = new LinkedHashMap<>();
        Matcher m = FOREIGN_KEY.matcher(create);
        while (m.find()) names.put(columnsKey(List.of(m.group(3).split(","))), m.group(2));
        Map<Integer, List<Map<String, Object>>> byId = new LinkedHashMap<>();
        for (Map<String, Object> f : catalog.rows(
                "SELECT id, seq, \"table\" AS ref_table, \"from\" AS from_column, \"to\" AS to_column FROM pragma_foreign_key_list(?, ?) ORDER BY id, seq", table, s)) {
            byId.computeIfAbsent(number(f.get("id")), k -> new ArrayList<>()).add(f);
        }
        List<TableDef.ForeignKey> out = new ArrayList<>();
        for (List<Map<String, Object>> rows : byId.values()) {
            List<String> from = rows.stream().map(r -> text(r.get("from_column"))).toList();
            List<String> to = rows.stream().map(r -> text(r.get("to_column"))).toList();
            String name = names.getOrDefault(columnsKey(from), "");
            out.add(new TableDef.ForeignKey(name, from, schema, text(rows.get(0).get("ref_table")), to));
        }
        return out;
    }

    /** 列名清单的比较键：去掉引号和空白，不分大小写。 */
    private static String columnsKey(List<String> columns) {
        return String.join(",", columns.stream().map(c -> c.replace("\"", "").trim().toUpperCase(Locale.ROOT)).toList());
    }

    /** 这一列在建表语句里是不是 {@code INTEGER PRIMARY KEY AUTOINCREMENT}。 */
    private static boolean identity(String create, String column) {
        return Pattern.compile("\"?" + Pattern.quote(column) + "\"?\\s+INTEGER\\s+PRIMARY\\s+KEY\\s+AUTOINCREMENT", Pattern.CASE_INSENSITIVE).matcher(create).find();
    }

    /** 声明的类型名还原成列类型；不认识的按文本。 */
    private static ColumnType type(String declared) {
        Matcher m = TYPE.matcher(declared);
        if (!m.matches()) return ColumnType.text(0);
        String name = m.group(1).trim().toUpperCase(Locale.ROOT);
        int a = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int b = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return switch (name) {
            case "VARCHAR", "CHARACTER VARYING", "NVARCHAR", "CHAR", "CHARACTER", "NCHAR" -> ColumnType.text(a);
            case "TEXT", "CLOB" -> ColumnType.of(Kind.CLOB);
            case "DECIMAL", "NUMERIC" -> a == 0 ? ColumnType.decimal(18, 2) : ColumnType.decimal(a, b);
            case "SMALLINT" -> ColumnType.of(Kind.SMALLINT);
            case "INT", "INTEGER", "BIGINT" -> ColumnType.of(Kind.BIGINT);
            case "DOUBLE", "DOUBLE PRECISION", "REAL", "FLOAT" -> ColumnType.of(Kind.DOUBLE);
            case "BOOLEAN" -> ColumnType.of(Kind.BOOLEAN);
            case "DATE" -> ColumnType.of(Kind.DATE);
            case "TIMESTAMP", "DATETIME" -> ColumnType.of(Kind.TIMESTAMP);
            case "BLOB", "" -> ColumnType.of(Kind.BLOB);
            default -> ColumnType.text(0);
        };
    }
}

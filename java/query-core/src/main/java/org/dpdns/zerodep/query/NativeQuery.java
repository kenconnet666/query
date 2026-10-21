package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Binding;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.exec.MappingPlan;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written SQL with {@code :name} parameters, mapped by column label. A collection value
 * expands to {@code ?, ?, ...} for {@code IN (:names)}. The escape hatch for statements the
 * builder cannot express, and a migration path for existing SQL text.
 */
public final class NativeQuery {
    private final Database database;
    private final String sql;
    private final Map<String, Binding> parameters = new HashMap<>();

    NativeQuery(Database database, String sql) {
        this.database = database;
        this.sql = sql;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public NativeQuery bind(String name, Object value) {
        if (value == null) throw new IllegalArgumentException(":" + name + " is null; use bindNull(name, Type.class)");
        parameters.put(name, new Binding(value, value instanceof Collection<?> ? null : SqlType.of((Class) value.getClass())));
        return this;
    }

    public NativeQuery bindNull(String name, Class<?> type) {
        parameters.put(name, new Binding(null, SqlType.of(type)));
        return this;
    }

    public <R> List<R> list(Class<R> type) {
        Rendered statement = render();
        TypeRegistry types = database.executor().types();
        return database.executor().query(statement, rs -> read(rs, type, types), List::size);
    }

    public <R> Optional<R> first(Class<R> type) {
        List<R> rows = list(type);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public <R> R one(Class<R> type) {
        List<R> rows = list(type);
        if (rows.size() != 1) throw new IllegalStateException("Expected exactly one row, got " + rows.size());
        return rows.get(0);
    }

    private static <R> List<R> read(ResultSet rs, Class<R> type, TypeRegistry types) throws java.sql.SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) labels.add(meta.getColumnLabel(i));
        MappingPlan<R> plan = MappingPlan.of(type, labels);
        List<R> out = new ArrayList<>();
        Object[] row = new Object[labels.size()];
        while (rs.next()) {
            for (int i = 0; i < row.length; i++) {
                SqlType<?> t = plan.columnType(i);
                row[i] = t == null ? rs.getObject(i + 1) : t.fromDatabase(types.read(rs, i + 1, t.storageType()));
            }
            out.add(plan.map(row));
        }
        return out;
    }

    /** Replaces :name outside quotes, comments and PostgreSQL's :: casts. */
    Rendered render() {
        StringBuilder out = new StringBuilder();
        List<Binding> bindings = new ArrayList<>();
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                int end = sql.indexOf(c, i + 1);
                while (end >= 0 && end + 1 < n && sql.charAt(end + 1) == c) end = sql.indexOf(c, end + 2);
                if (end < 0) end = n - 1;
                out.append(sql, i, end + 1);
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) end = n - 1;
                out.append(sql, i, end + 1);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                end = end < 0 ? n - 1 : end + 1;
                out.append(sql, i, end + 1);
                i = end;
            } else if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
                out.append("::");
                i++;
            } else if (c == ':' && i + 1 < n && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
                int end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(sql.charAt(end))) end++;
                String name = sql.substring(i + 1, end);
                Binding b = parameters.get(name);
                if (b == null) throw new IllegalArgumentException("Parameter :" + name + " is not bound");
                expand(name, b, out, bindings);
                i = end - 1;
            } else {
                out.append(c);
            }
        }
        return new Rendered(out.toString(), bindings);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void expand(String name, Binding b, StringBuilder out, List<Binding> bindings) {
        if (!(b.value() instanceof Collection<?> values)) {
            out.append('?');
            bindings.add(b);
            return;
        }
        if (values.isEmpty()) throw new IllegalArgumentException(":" + name + " is an empty collection; IN () is invalid SQL");
        int k = 0;
        for (Object v : values) {
            if (v == null) throw new IllegalArgumentException(":" + name + " contains null");
            if (k++ > 0) out.append(", ");
            out.append('?');
            bindings.add(new Binding(v, SqlType.of((Class) v.getClass())));
        }
    }
}

package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Binding;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.exec.MappingPlan;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 手写 SQL，用 {@code :name} 形式的命名参数，结果按列标签映射。集合类型的值会展开成
 * {@code ?, ?, ...}，用于 {@code IN (:names)}。既是构造器表达不了的语句的后路，也是迁移已有 SQL 文本的途径。
 */
public final class NativeQuery {
    /** 执行本语句的数据库。 */
    private final Database database;

    /** 含 :name 参数的 SQL 文本。 */
    private final String sql;

    /** 已绑定的参数，键是不带冒号的名字。 */
    private final Map<String, Binding> parameters = new HashMap<>();

    /** 本语句的超时，null 表示用数据库实例的默认值。 */
    private Duration timeout;

    /** 由 {@link Database#sql} 创建。 */
    NativeQuery(Database database, String sql) {
        this.database = database;
        this.sql = sql;
    }

    /** 绑定命名参数，类型取自值的运行时类；集合会展开成 IN 列表。value 为 null 时报错，请改用 {@link #bindNull}。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public NativeQuery bind(String name, Object value) {
        if (value == null) throw new IllegalArgumentException(":" + name + " is null; use bindNull(name, Type.class)");
        parameters.put(name, new Binding(value, value instanceof Collection<?> ? null : SqlType.of((Class) value.getClass())));
        return this;
    }

    /** 把命名参数绑定为指定类型的 NULL。 */
    public NativeQuery bindNull(String name, Class<?> type) {
        parameters.put(name, new Binding(null, SqlType.of(type)));
        return this;
    }

    /** 语句运行超过这个时间就被取消；优先于数据库实例的 queryTimeout。 */
    public NativeQuery timeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        this.timeout = timeout;
        return this;
    }

    /** 执行语句，把每一行按列标签映射成 type 的实例。 */
    public <R> List<R> list(Class<R> type) {
        Rendered statement = render();
        TypeRegistry types = database.executor().types();
        return database.executor().query(statement, rs -> read(rs, type, types), List::size, timeout);
    }

    /** 第一行，没有时为空。 */
    public <R> Optional<R> first(Class<R> type) {
        List<R> rows = list(type);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 恰好一行，没有或多于一行都抛出异常。 */
    public <R> R one(Class<R> type) {
        List<R> rows = list(type);
        if (rows.size() != 1) throw new IllegalStateException("Expected exactly one row, got " + rows.size());
        return rows.get(0);
    }

    /** 读完结果集：按列标签建立映射计划，再把每行的值转换成 type 的实例。 */
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

    /** 把 :name 替换成 ? 占位符。方言的引号部分、注释和 PostgreSQL 的 :: 类型转换里的冒号不处理。 */
    Rendered render() {
        StringBuilder out = new StringBuilder();
        List<Binding> bindings = new ArrayList<>();
        Dialect dialect = database.dialect();
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            int quoted = dialect.quotedEnd(sql, i);
            if (quoted >= 0) {
                out.append(sql, i, quoted + 1);
                i = quoted;
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

    /** 写出一个参数：普通值是一个 ?，集合是逗号分隔的多个 ?；空集合和含 null 的集合都会报错。 */
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

package org.dpdns.zerodep.query.dialect;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 最终的 SQL 文本，以及按占位符顺序排列的参数。打印时用 {@link #toString()}（SQL 加一行带类型的参数），
 * 排查问题时用 {@link #debugSql()}（参数写进 SQL，可以直接粘到数据库客户端执行）。
 *
 * @param sql      完整的 SQL 文本
 * @param bindings 与 {@code ?} 占位符一一对应的参数
 * @param dialect  渲染所用的方言，{@link #debugSql()} 按它写字面量
 */
public record Rendered(String sql, List<Binding> bindings, Dialect dialect) {
    /** 复制参数列表，使记录不可变。 */
    public Rendered {
        bindings = List.copyOf(bindings);
    }

    /** 数据库侧的参数值，已经过转换器。 */
    public List<Object> parameters() {
        return bindings.stream().map(Binding::databaseValue).collect(Collectors.toList());
    }

    /** 带类型的参数文本，形如 {@code M1(String), 20260115(String), 3(Integer)}；值是经过转换器、实际发给数据库的值。 */
    public String parameterText() {
        return parameters().stream()
            .map(v -> v == null ? "null" : v + "(" + v.getClass().getSimpleName() + ")")
            .collect(Collectors.joining(", "));
    }

    /**
     * 调试用的 SQL：把每个 {@code ?} 换成参数的字面量，可以直接粘到数据库客户端里执行。写不成字面量的参数
     * （例如字节）保留为 {@code ?}。只用于排查问题；程序执行语句时仍然绑定参数。
     */
    public String debugSql() {
        StringBuilder out = new StringBuilder();
        int next = 0;
        for (int i = 0; i < sql.length(); i++) {
            int quoted = dialect.quotedEnd(sql, i);
            if (quoted >= 0) {
                out.append(sql, i, quoted + 1);
                i = quoted;
            } else if (sql.charAt(i) == '?' && next < bindings.size()) {
                Object value = bindings.get(next++).databaseValue();
                out.append(value == null || dialect.hasLiteral(value) ? new SqlWriter(dialect).literal(value).text() : "?");
            } else {
                out.append(sql.charAt(i));
            }
        }
        return out.toString();
    }

    /** SQL 后面附一行带类型的参数，便于打印和对照。 */
    @Override public String toString() { return bindings.isEmpty() ? sql : sql + "\n-- parameters: " + parameterText(); }
}

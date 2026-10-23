package org.dpdns.zerodep.query.dialect;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 最终的 SQL 文本，以及按占位符顺序排列的参数。
 *
 * @param sql      完整的 SQL 文本
 * @param bindings 与 {@code ?} 占位符一一对应的参数
 */
public record Rendered(String sql, List<Binding> bindings) {
    /** 复制参数列表，使记录不可变。 */
    public Rendered {
        bindings = List.copyOf(bindings);
    }

    /** 数据库侧的参数值，已经过转换器。 */
    public List<Object> parameters() {
        return bindings.stream().map(Binding::databaseValue).collect(Collectors.toList());
    }

    /** SQL 后面附一行参数，方便调试和金样例对照。 */
    @Override public String toString() { return sql + "\n-- parameters " + parameters(); }
}

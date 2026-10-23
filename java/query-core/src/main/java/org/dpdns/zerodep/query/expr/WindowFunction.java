package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.List;
import java.util.function.UnaryOperator;

/** ROW_NUMBER、RANK、LAG 等：必须用 {@link #over} 给出窗口之后才是表达式。 */
public final class WindowFunction<T> {
    /** 结果类型。 */
    private final SqlType<T> type;

    /** 函数名，例如 ROW_NUMBER。 */
    private final String name;

    /** 函数参数，可以为空。 */
    private final List<? extends Expr<?>> args;

    /** 由 {@link Sql} 里的窗口函数方法调用。 */
    public WindowFunction(SqlType<T> type, String name, List<? extends Expr<?>> args) {
        this.type = type;
        this.name = name;
        this.args = List.copyOf(args);
    }

    /** 给出窗口定义，得到 {@code NAME(args) OVER (...)} 表达式；window 从空窗口开始配置。 */
    public Expr<T> over(UnaryOperator<Window> window) {
        return new Aggregate.Over<>(Nodes.call(type, name, args), window.apply(Window.EMPTY));
    }
}

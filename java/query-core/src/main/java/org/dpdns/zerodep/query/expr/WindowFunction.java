package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * ROW_NUMBER、RANK、LAG 等：必须用 {@link #over} 给出窗口之后才是表达式，结果的类型接口是 F，
 * 例如 {@code lag(l.quantity(), 1).over(...)} 是 {@code ExprDecimal}，可以接着 {@code .minus(...)}。
 *
 * @param <T> 结果的值类型
 * @param <F> 结果的类型接口
 */
public final class WindowFunction<T, F extends Expr<T>> {
    /** 结果类型。 */
    private final SqlType<T> type;

    /** 函数名，例如 ROW_NUMBER；用函数定义时为 null。 */
    private final String name;

    /** 方言决定写法的函数定义；按名字调用时为 null。 */
    private final FunctionDef function;

    /** 函数参数，可以为空。 */
    private final List<? extends Expr<?>> args;

    /** 把窗口函数节点包成结果的类型接口。 */
    private final Function<Expr<T>, F> family;

    /** 由 {@link Sql} 里的窗口函数方法调用。 */
    public WindowFunction(SqlType<T> type, String name, List<? extends Expr<?>> args, Function<Expr<T>, F> family) {
        this.type = type;
        this.name = name;
        this.function = null;
        this.args = List.copyOf(args);
        this.family = family;
    }

    /** 由函数定义决定写法的窗口函数，例如方言包里的 {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x)}。 */
    public WindowFunction(SqlType<T> type, FunctionDef function, List<? extends Expr<?>> args, Function<Expr<T>, F> family) {
        this.type = type;
        this.name = null;
        this.function = function;
        this.args = List.copyOf(args);
        this.family = family;
    }

    /** 给出窗口定义，得到 {@code NAME(args) OVER (...)} 表达式；window 从空窗口开始配置。 */
    public F over(UnaryOperator<Window> window) {
        Expr<T> call = function == null ? Nodes.call(type, name, args) : Nodes.call(type, function, args);
        return family.apply(new Aggregate.Over<>(call, window.apply(Window.EMPTY)));
    }
}

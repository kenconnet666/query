package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.type.SqlType;

import java.util.List;
import java.util.function.UnaryOperator;

/** ROW_NUMBER, RANK, LAG and friends: not an expression until {@link #over} gives it a window. */
public final class WindowFunction<T> {
    private final SqlType<T> type;
    private final String name;
    private final List<? extends Expr<?>> args;

    WindowFunction(SqlType<T> type, String name, List<? extends Expr<?>> args) {
        this.type = type;
        this.name = name;
        this.args = List.copyOf(args);
    }

    public Expr<T> over(UnaryOperator<Window> window) {
        return new Aggregate.Over<>(Nodes.call(type, name, args), window.apply(Window.EMPTY));
    }
}

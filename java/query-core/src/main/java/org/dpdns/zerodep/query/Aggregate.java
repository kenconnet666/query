package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.List;
import java.util.function.UnaryOperator;

/** An aggregate such as SUM(x); usable as is, or as a window function through {@link #over}. */
public final class Aggregate<T> extends Expr<T> {
    private final String name;
    private final boolean distinct;
    private final List<? extends Expr<?>> args;

    Aggregate(SqlType<T> type, String name, boolean distinct, List<? extends Expr<?>> args) {
        super(type);
        this.name = name;
        this.distinct = distinct;
        this.args = List.copyOf(args);
    }

    public Expr<T> over(UnaryOperator<Window> window) { return new Over<>(this, window.apply(Window.EMPTY)); }

    @Override public void render(SqlWriter w) {
        w.sql(name).sql("(");
        if (distinct) w.sql("DISTINCT ");
        if (args.isEmpty()) w.sql("*");
        else w.join(args, ", ");
        w.sql(")");
    }

    static final class Over<T> extends Expr<T> {
        private final Expr<T> function;
        private final Window window;

        Over(Expr<T> function, Window window) {
            super(function.type());
            this.function = function;
            this.window = window;
        }

        @Override public void render(SqlWriter w) { w.render(function).sql(" OVER (").render(window).sql(")"); }
    }
}

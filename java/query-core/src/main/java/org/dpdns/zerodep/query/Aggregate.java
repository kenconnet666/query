package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.List;
import java.util.function.UnaryOperator;

/** 聚合函数，例如 SUM(x)。可以直接使用，也可以通过 {@link #over} 变成窗口函数。 */
public final class Aggregate<T> extends Expr<T> {
    /** 函数名，例如 SUM。 */
    private final String name;

    /** 是否写成 {@code NAME(DISTINCT ...)}。 */
    private final boolean distinct;

    /** 参数；为空时写成 {@code NAME(*)}，例如 COUNT(*)。 */
    private final List<? extends Expr<?>> args;

    /** 由 {@link Sql} 里的聚合函数方法调用。 */
    Aggregate(SqlType<T> type, String name, boolean distinct, List<? extends Expr<?>> args) {
        super(type);
        this.name = name;
        this.distinct = distinct;
        this.args = List.copyOf(args);
    }

    /** 加上 OVER (...) 变成窗口函数；window 从空窗口开始配置，例如 {@code over(w -> w.partitionBy(x))}。 */
    public Expr<T> over(UnaryOperator<Window> window) { return new Over<>(this, window.apply(Window.EMPTY)); }

    /** 写出 {@code NAME([DISTINCT ]args)}。 */
    @Override public void render(SqlWriter w) {
        w.sql(name).sql("(");
        if (distinct) w.sql("DISTINCT ");
        if (args.isEmpty()) w.sql("*");
        else w.join(args, ", ");
        w.sql(")");
    }

    /** 只有一个参数的聚合沿用该列的列名：sum(l.quantity()) 在 cols(...) 里叫 quantity。 */
    @Override String nameHint() { return args.size() == 1 ? args.get(0).nameHint() : null; }

    /** 聚合或窗口函数加上 OVER (...) 后的表达式。 */
    static final class Over<T> extends Expr<T> {
        /** 被加上窗口的聚合或窗口函数。 */
        private final Expr<T> function;

        /** OVER 括号里的内容。 */
        private final Window window;

        Over(Expr<T> function, Window window) {
            super(function.type());
            this.function = function;
            this.window = window;
        }

        /** 写出 {@code function OVER (window)}。 */
        @Override public void render(SqlWriter w) { w.render(function).sql(" OVER (").render(window).sql(")"); }

        /** 窗口化的聚合沿用聚合的列名。 */
        @Override String nameHint() { return function.nameHint(); }
    }
}

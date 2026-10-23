package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.List;
import java.util.function.UnaryOperator;

/** 聚合函数，例如 SUM(x)。可以直接使用，用 {@link #filter} 只聚合部分行，或通过 {@link #over} 变成窗口函数。 */
public final class Aggregate<T> extends Expr<T> {
    /** 函数名，例如 SUM。 */
    private final String name;

    /** 是否写成 {@code NAME(DISTINCT ...)}。 */
    private final boolean distinct;

    /** 参数；为空时写成 {@code NAME(*)}，例如 COUNT(*)。 */
    private final List<? extends Expr<?>> args;

    /** 只聚合满足它的行；null 表示不过滤。 */
    private final Cond filter;

    /** 由 {@link Sql} 里的聚合函数方法调用。 */
    public Aggregate(SqlType<T> type, String name, boolean distinct, List<? extends Expr<?>> args) {
        this(type, name, distinct, args, null);
    }

    /** 由 {@link #filter} 调用，带上过滤条件。 */
    private Aggregate(SqlType<T> type, String name, boolean distinct, List<? extends Expr<?>> args, Cond filter) {
        super(type);
        this.name = name;
        this.distinct = distinct;
        this.args = List.copyOf(args);
        this.filter = filter;
    }

    /**
     * 条件聚合：只聚合满足 condition 的行，例如 {@code sum(l.quantity()).filter(l.unit().eq("KG")).as("kg")}。
     * 三个库都写成 {@code SUM(CASE WHEN 条件 THEN x END)}，COUNT(*) 写成 {@code COUNT(CASE WHEN 条件 THEN 1 END)}；
     * 多次调用按 AND 合并，空条件（例如 skipEmpty 省略掉的）不过滤。
     */
    public Aggregate<T> filter(Cond condition) {
        if (condition.isEmpty()) return this;
        Cond combined = filter == null ? condition : Sql.all(filter, condition);
        return new Aggregate<>(type(), name, distinct, args, combined);
    }

    /** 加上 OVER (...) 变成窗口函数；window 从空窗口开始配置，例如 {@code over(w -> w.partitionBy(x))}。 */
    public Expr<T> over(UnaryOperator<Window> window) { return new Over<>(this, window.apply(Window.EMPTY)); }

    /** 写出 {@code NAME([DISTINCT ]args)}；有过滤条件时参数换成 {@code CASE WHEN 条件 THEN 参数 END}。 */
    @Override public void render(SqlWriter w) {
        w.sql(name).sql("(");
        if (distinct) w.sql("DISTINCT ");
        if (filter != null) {
            w.sql("CASE WHEN ").render(filter).sql(" THEN ");
            if (args.isEmpty()) w.sql("1");
            else w.join(args, ", ");
            w.sql(" END");
        } else if (args.isEmpty()) w.sql("*");
        else w.join(args, ", ");
        w.sql(")");
    }

    /** 只有一个参数的聚合沿用该列的列名：sum(l.quantity()) 在 cols(...) 里叫 quantity。 */
    @Override public String nameHint() { return args.size() == 1 ? args.get(0).nameHint() : null; }

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
        @Override public String nameHint() { return function.nameHint(); }
    }
}

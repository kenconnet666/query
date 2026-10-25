package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Aggregation;
import org.dpdns.zerodep.query.dialect.FunctionRenderer;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 聚合函数节点，例如 SUM(x)，来自 {@code l.quantity().sum()}、{@code count()} 等。表达式上的 {@code filter(() -> ...)} 只聚合部分行，
 * {@code over(...)} 把它变成窗口函数；两者只能用在聚合上。
 */
public final class Aggregate<T> extends ExprNode<T> {
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
     * 条件聚合：只聚合满足 condition 的行。三个库都写成 {@code SUM(CASE WHEN 条件 THEN x END)}，COUNT(*) 写成
     * {@code COUNT(CASE WHEN 条件 THEN 1 END)}；多次调用按 AND 合并，空条件（例如 skipEmpty 省略掉的）不过滤。
     */
    public Aggregate<T> filter(Cond condition) {
        if (condition.isEmpty()) return this;
        Cond combined = filter == null ? condition : Nodes.all(List.of(filter, condition));
        return new Aggregate<>(type(), name, distinct, args, combined);
    }

    /** 加上 OVER (...) 变成窗口函数；window 从空窗口开始配置，例如 {@code over(w -> w.partitionBy(x))}。 */
    public Expr<T> over(UnaryOperator<Window> window) { return new Over<>(this, window.apply(Window.EMPTY)); }

    /** 表达式上的 {@code filter(() -> ...)}：e 必须是聚合，收集块里的条件作为过滤条件。 */
    public static <T> Expr<T> filtered(Expr<T> e, Runnable condition) { return of(e, "filter(...)").filter(Conditions.collect(condition)); }

    /** 表达式上的 {@code over(...)}：e 必须是聚合。 */
    public static <T> Expr<T> windowed(Expr<T> e, UnaryOperator<Window> window) { return of(e, "over(...)").over(window); }

    /** e 背后的聚合节点；不是聚合时报错，use 是调用的方法。 */
    private static <T> Aggregate<T> of(Expr<T> e, String use) {
        if (Nodes.unwrap(e) instanceof Aggregate<T> a) return a;
        throw new IllegalArgumentException(use + " needs an aggregate such as sum(), count() or max(), got " + e);
    }

    /**
     * 写出 {@code NAME([DISTINCT ]args)}；有过滤条件时参数换成 {@code CASE WHEN 条件 THEN 参数 END}。
     * 方言按名字登记了写法时（不带 DISTINCT）交给它，参数同样先套上过滤条件。
     */
    @Override public void render(SqlWriter w) {
        Aggregation.aggregate(w);
        FunctionRenderer custom = distinct ? null : w.dialect().callRenderer(name);
        if (custom != null) {
            custom.render(w, filter == null ? args : args.stream().map(a -> (Renderable) x -> x.sql("CASE WHEN ").render(filter).sql(" THEN ").render(a).sql(" END")).toList());
            return;
        }
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

    /** node 是否是加了 OVER (...) 的窗口函数；QUALIFY 用它找出条件里的窗口函数。 */
    public static boolean isWindow(Renderable node) { return node instanceof Over; }

    /** 只有一个参数的聚合沿用该列的列名：l.quantity().sum() 在 cols(...) 里叫 quantity。 */
    @Override public String nameHint() { return args.size() == 1 ? args.get(0).nameHint() : null; }

    /** 聚合或窗口函数加上 OVER (...) 后的表达式。 */
    static final class Over<T> extends ExprNode<T> {
        /** 被加上窗口的聚合或窗口函数。 */
        private final Expr<T> function;

        /** OVER 括号里的内容。 */
        private final Window window;

        Over(Expr<T> function, Window window) {
            super(function.type());
            this.function = function;
            this.window = window;
        }

        /** 写出 {@code function OVER (window)}；方言不能把这个聚合当窗口函数时报错。 */
        @Override public void render(SqlWriter w) {
            if (function instanceof Aggregate<?> a && !w.dialect().windowable(a.name)) {
                throw new UnsupportedOperationException(a.name + "(...) OVER (...) is not supported on " + w.dialect().name());
            }
            Aggregation.window(w, () -> w.render(function).sql(" OVER (").render(window).sql(")"));
        }

        /** 窗口化的聚合沿用聚合的列名。 */
        @Override public String nameHint() { return function.nameHint(); }
    }
}

package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 一个 SQL 表达式，值的 Java 类型是 T。
 *
 * <p>操作数必须同类型 T 的运算（比较、COALESCE、NULLIF、IN）都写成实例方法：
 * 接收者先固定 T，javac 再推断参数。若写成静态的 {@code coalesce(Expr<T>, Expr<T>)}，
 * javac 会把 T 放宽成共同的父类型，于是 String 列和 BigDecimal 列也能放在一起。
 *
 * <p>与 null 比较是错误，除非查询开了 {@code skipEmpty()}：此时与 null、空白字符串或空集合的比较不产生任何条件。
 *
 * <p>要添加自己的表达式节点，继承本类并实现 {@link #render}。
 */
public abstract non-sealed class Expr<T> implements Renderable, Keys, OrderItem, Selection<T> {
    /** 表达式的值类型，决定参数的绑定和结果的读取。 */
    private final SqlType<T> type;

    /** 供表达式节点继承；type 不能为 null。 */
    protected Expr(SqlType<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** 值类型。 */
    public final SqlType<T> type() { return type; }

    /** 投影本表达式时使用的列名：列的名字，或 null 表示没有。 */
    public String label() { return null; }

    /** 在 {@code cols(...)} 里使用的名字：列名，或函数、聚合所包着的那一列的名字。 */
    String nameHint() { return label(); }

    // ---------- 比较 ----------

    /** 等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    public final Cond eq(T value) { return compare("=", value); }

    /** 等于另一个表达式。 */
    public final Cond eq(Expr<T> other) { return Nodes.compare(this, "=", other); }

    /** 不等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    public final Cond ne(T value) { return compare("<>", value); }

    /** 不等于另一个表达式。 */
    public final Cond ne(Expr<T> other) { return Nodes.compare(this, "<>", other); }

    /** 大于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    public final Cond gt(T value) { return compare(">", value); }

    /** 大于另一个表达式。 */
    public final Cond gt(Expr<T> other) { return Nodes.compare(this, ">", other); }

    /** 大于等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    public final Cond ge(T value) { return compare(">=", value); }

    /** 大于等于另一个表达式。 */
    public final Cond ge(Expr<T> other) { return Nodes.compare(this, ">=", other); }

    /** 小于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    public final Cond lt(T value) { return compare("<", value); }

    /** 小于另一个表达式。 */
    public final Cond lt(Expr<T> other) { return Nodes.compare(this, "<", other); }

    /** 小于等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    public final Cond le(T value) { return compare("<=", value); }

    /** 小于等于另一个表达式。 */
    public final Cond le(Expr<T> other) { return Nodes.compare(this, "<=", other); }

    /** 介于 low 和 high 之间（含两端）。开启 skipEmpty 时缺少的一端被忽略，只按另一端比较。 */
    public final Cond between(T low, T high) {
        if (SkipEmpty.skips(low) || SkipEmpty.skips(high)) return Sql.all(ge(low), le(high));
        return Nodes.between(this, param(low), param(high));
    }

    /** 介于两个表达式之间（含两端）。 */
    public final Cond between(Expr<T> low, Expr<T> high) { return Nodes.between(this, low, high); }

    /** 在 values 之中。空集合恒为假（开启 skipEmpty 则不产生条件）；过长的列表按方言的 IN 上限拆开。 */
    public final Cond in(Collection<? extends T> values) {
        if (SkipEmpty.skips(values)) return Cond.EMPTY;
        return Nodes.in(this, params(values), false);
    }

    /** 在子查询的结果之中。 */
    public final Cond in(Query<T> subquery) { return Nodes.inQuery(this, subquery, false); }

    /** 不在 values 之中。空集合恒为真（开启 skipEmpty 则不产生条件）。 */
    public final Cond notIn(Collection<? extends T> values) {
        if (SkipEmpty.skips(values)) return Cond.EMPTY;
        return Nodes.in(this, params(values), true);
    }

    /** 不在子查询的结果之中。 */
    public final Cond notIn(Query<T> subquery) { return Nodes.inQuery(this, subquery, true); }

    /** IS NULL。 */
    public final Cond isNull() { return Nodes.postfix(this, " IS NULL"); }

    /** IS NOT NULL。 */
    public final Cond isNotNull() { return Nodes.postfix(this, " IS NOT NULL"); }

    /** 与常量比较；开启 skipEmpty 且 value 为空时不产生条件，否则把 value 绑定为参数。 */
    private Cond compare(String operator, T value) {
        if (SkipEmpty.skips(value)) return Cond.EMPTY;
        return Nodes.compare(this, operator, param(value));
    }

    // ---------- 同类型函数 ----------

    /** COALESCE(this, fallback)：本表达式为 NULL 时取常量 fallback。 */
    public final Expr<T> coalesce(T fallback) { return Nodes.call(type, "COALESCE", List.of(this, param(fallback))); }

    /** COALESCE(this, fallback)：本表达式为 NULL 时取表达式 fallback。 */
    public final Expr<T> coalesce(Expr<T> fallback) { return Nodes.call(type, "COALESCE", List.of(this, fallback)); }

    /** COALESCE(this, a, b, ...)：依次取第一个非 NULL 的值；最后一个备选若是常量，写成 {@code val(x)}。 */
    @SafeVarargs
    public final Expr<T> coalesce(Expr<T> fallback, Expr<T>... more) {
        List<Expr<?>> args = new ArrayList<>(List.of(this, fallback));
        args.addAll(List.of(more));
        return Nodes.call(type, "COALESCE", args);
    }

    /** NULLIF(this, value)：等于 value 时得到 NULL，否则是本表达式的值。 */
    public final Expr<T> nullif(T value) { return Nodes.call(type, "NULLIF", List.of(this, param(value))); }

    // ---------- 命名、排序、转换 ----------

    /** 指定投影时的列名，表达式本身的渲染不变。 */
    public final Expr<T> as(String label) { return new Nodes.Labeled<>(this, label); }

    /** 升序排序项。 */
    public final Order asc() { return new Order(this, false, null); }

    /** 降序排序项。 */
    public final Order desc() { return new Order(this, true, null); }

    /** CAST(this AS target)，结果是 U 类型的表达式。 */
    public final <U> Expr<U> cast(CastType<U> target) { return new Nodes.Cast<>(this, target); }

    /** 按本表达式的类型绑定的参数，所以挂在类型上的转换器会作用于这个值。value 为 null 时报错。 */
    protected final Expr<T> param(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Comparing with NULL never matches; use isNull(), or skipEmpty() on the query to drop conditions on empty values");
        }
        return new Nodes.Param<>(type, value);
    }

    /** 把集合里的每个值绑定为参数。 */
    private List<Expr<T>> params(Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        return values.stream().map(v -> param(v)).toList();
    }
}

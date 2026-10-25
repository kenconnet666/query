package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.expr.typed.ExprBigint;
import org.dpdns.zerodep.query.expr.typed.ExprDate;
import org.dpdns.zerodep.query.expr.typed.ExprDecimal;
import org.dpdns.zerodep.query.expr.typed.ExprDouble;
import org.dpdns.zerodep.query.expr.typed.ExprInteger;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.expr.typed.ExprTimestamp;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.Selection;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 一个 SQL 表达式，值的 Java 类型是 T。
 *
 * <p>本接口只放所有类型共有的方法；按类型才有的运算在各类型的接口上：文本 {@link ExprText}、数值 {@link ExprInteger}、
 * {@link ExprDecimal} 等、日期 {@link ExprDate}、时间戳 {@link ExprTimestamp}。各类型接口把"结果仍是同一类型"的方法
 * （{@code coalesce}、{@code max}、{@code filter} 等）重写为返回本类型，所以 {@code l.unit().coalesce("EA").upper()} 能接着写。
 *
 * <p>比较、IN、IS NULL 等条件方法不返回值，而是写进当前的条件块，见 {@link Conditions}：
 * {@code where(l -> { l.unit().eq("KG"); l.quantity().gt(BigDecimal.ONE); })}。
 * 与 null 比较是错误，除非查询开了 {@code skipEmpty()}：此时与 null、空白字符串或空集合的比较不产生任何条件。
 *
 * <p>操作数必须同类型 T 的运算（比较、COALESCE、NULLIF、IN）都是实例方法：接收者先固定 T，javac 再检查参数。
 * 若写成静态的 {@code coalesce(Expr<T>, Expr<T>)}，javac 会把 T 放宽成共同的父类型，于是 String 列和 BigDecimal 列也能放在一起。
 *
 * <p>要添加自己的表达式节点，继承 {@link ExprNode} 并实现 {@link #render}。
 *
 * @param <T> 值的 Java 类型
 */
public non-sealed interface Expr<T> extends Renderable, Keys, OrderItem, Selection<T> {
    /** 值类型，决定参数的绑定和结果的读取。 */
    SqlType<T> type();

    /** 投影本表达式时使用的列名：列的名字，或 null 表示没有。 */
    default String label() { return null; }

    /** 在 {@code cols(...)} 里使用的名字：列名，或函数、聚合所包着的那一列的名字。 */
    default String nameHint() { return label(); }

    // ---------- 条件：写进当前的条件块 ----------

    /** 等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    default void eq(T value) { compare("=", value); }

    /** 等于另一个表达式。 */
    default void eq(Expr<T> other) { Conditions.add(Nodes.compare(this, "=", other)); }

    /** 不等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    default void ne(T value) { compare("<>", value); }

    /** 不等于另一个表达式。 */
    default void ne(Expr<T> other) { Conditions.add(Nodes.compare(this, "<>", other)); }

    /** 大于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    default void gt(T value) { compare(">", value); }

    /** 大于另一个表达式。 */
    default void gt(Expr<T> other) { Conditions.add(Nodes.compare(this, ">", other)); }

    /** 大于等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    default void ge(T value) { compare(">=", value); }

    /** 大于等于另一个表达式。 */
    default void ge(Expr<T> other) { Conditions.add(Nodes.compare(this, ">=", other)); }

    /** 小于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    default void lt(T value) { compare("<", value); }

    /** 小于另一个表达式。 */
    default void lt(Expr<T> other) { Conditions.add(Nodes.compare(this, "<", other)); }

    /** 小于等于 value；value 为 null 时报错，开启 skipEmpty 则不产生条件。 */
    default void le(T value) { compare("<=", value); }

    /** 小于等于另一个表达式。 */
    default void le(Expr<T> other) { Conditions.add(Nodes.compare(this, "<=", other)); }

    /** 介于 low 和 high 之间（含两端）。开启 skipEmpty 时缺少的一端被忽略，只按另一端比较。 */
    default void between(T low, T high) {
        if (SkipEmpty.skips(low) || SkipEmpty.skips(high)) {
            // 合成一个条件写进块里：在 any(() -> ...) 里两个条件会成为两个 OR 选项
            Conditions.add(Conditions.collect(() -> {
                ge(low);
                le(high);
            }));
            return;
        }
        Conditions.add(Nodes.between(this, param(low), param(high)));
    }

    /** 介于两个表达式之间（含两端）。 */
    default void between(Expr<T> low, Expr<T> high) { Conditions.add(Nodes.between(this, low, high)); }

    /**
     * 半开区间 {@code [from, until)}：写成 {@code x >= from AND x < until}，适合日期和时间段，
     * 例如 {@code e.onDay().range(monthStart, nextMonthStart)}。开启 skipEmpty 时缺少的一端被忽略。
     */
    default void range(T from, T until) {
        // 与 between 相同，两端合成一个条件
        Conditions.add(Conditions.collect(() -> {
            ge(from);
            lt(until);
        }));
    }

    /** 在 values 之中。空集合恒为假（开启 skipEmpty 则不产生条件）；过长的列表按方言的 IN 上限拆开。 */
    default void in(Collection<? extends T> values) {
        if (SkipEmpty.skips(values)) {
            Conditions.add(Cond.EMPTY);
            return;
        }
        Conditions.add(Nodes.in(this, params(values), false));
    }

    /** 在子查询的结果之中。 */
    default void in(Query<T> subquery) { Conditions.add(Nodes.inQuery(this, subquery, false)); }

    /** 不在 values 之中。空集合恒为真（开启 skipEmpty 则不产生条件）。 */
    default void notIn(Collection<? extends T> values) {
        if (SkipEmpty.skips(values)) {
            Conditions.add(Cond.EMPTY);
            return;
        }
        Conditions.add(Nodes.in(this, params(values), true));
    }

    /** 不在子查询的结果之中。 */
    default void notIn(Query<T> subquery) { Conditions.add(Nodes.inQuery(this, subquery, true)); }

    /** IS NULL。 */
    default void isNull() { Conditions.add(Nodes.postfix(this, " IS NULL")); }

    /** IS NOT NULL。 */
    default void isNotNull() { Conditions.add(Nodes.postfix(this, " IS NOT NULL")); }

    /** NULL 也相等的比较：两侧都为 NULL 时为真，只有一侧为 NULL 时为假（标准 SQL 的 {@code IS NOT DISTINCT FROM}）。 */
    default void isNotDistinctFrom(Expr<T> other) { Conditions.add(Nodes.notDistinct(this, other, false)); }

    /** {@link #isNotDistinctFrom} 的否定：值不同，或只有一侧为 NULL 时为真。 */
    default void isDistinctFrom(Expr<T> other) { Conditions.add(Nodes.notDistinct(this, other, true)); }

    // ---------- 结果仍是同一类型的函数（各类型接口重写为返回本类型） ----------

    /** COALESCE(this, fallback)：本表达式为 NULL 时取常量 fallback。 */
    default Expr<T> coalesce(T fallback) { return Nodes.coalesce(this, param(fallback)); }

    /** COALESCE(this, fallback)：本表达式为 NULL 时取另一个表达式。 */
    default Expr<T> coalesce(Expr<T> fallback) { return Nodes.coalesce(this, fallback); }

    /** COALESCE(this, fallback, last)：依次取第一个非 NULL 的值；last 是常量时写成 {@code val(x)}。 */
    default Expr<T> coalesce(Expr<T> fallback, Expr<T> last) { return Nodes.coalesce(this, fallback, last); }

    /** NULLIF(this, value)：等于 value 时得到 NULL，否则是本表达式的值。 */
    default Expr<T> nullif(T value) { return Nodes.nullif(this, value); }

    /** 最大值（聚合）。 */
    default Expr<T> max() { return Nodes.aggregate(this, "MAX"); }

    /** 最小值（聚合）。 */
    default Expr<T> min() { return Nodes.aggregate(this, "MIN"); }

    /**
     * 条件聚合：只聚合满足条件的行，例如 {@code l.quantity().sum().filter(() -> l.unit().eq("KG")).as("kg")}。
     * 三个库都写成 {@code SUM(CASE WHEN 条件 THEN x END)}；多次调用按 AND 合并，块里没有条件（例如都被 skipEmpty 省略）时不过滤。
     * 只能用在聚合（sum、count、max 等）上，否则报错。
     */
    default Expr<T> filter(Runnable condition) { return Aggregate.filtered(this, condition); }

    /** 加上 OVER (...) 变成窗口函数；window 从空窗口开始配置，例如 {@code l.quantity().sum().over(w -> w.partitionBy(l.material()))}。只能用在聚合上。 */
    default Expr<T> over(UnaryOperator<Window> window) { return Aggregate.windowed(this, window); }

    // ---------- 与类型无关的聚合 ----------

    /** COUNT(this)：不计 NULL。 */
    default ExprBigint<Long> count() { return ExprBigint.of(new Aggregate<>(SqlType.of(Long.class), "COUNT", false, List.of(this))); }

    /** COUNT(DISTINCT this)。 */
    default ExprBigint<Long> countDistinct() { return ExprBigint.of(new Aggregate<>(SqlType.of(Long.class), "COUNT", true, List.of(this))); }

    // ---------- 命名、排序、转换 ----------

    /** 指定投影时的列名，表达式本身的渲染不变。 */
    default Expr<T> as(String label) { return new Nodes.Labeled<>(this, label); }

    /** 升序排序项。 */
    default Order asc() { return new Order(this, false, null); }

    /** 降序排序项。 */
    default Order desc() { return new Order(this, true, null); }

    /** CAST(this AS target)，结果是目标类型的表达式，例如 {@code l.quantity().cast(CastType.doublePrecision())} 是 {@code ExprDouble}。 */
    default <U, F extends Expr<U>> F cast(CastType<U, F> target) { return target.wrap(new Nodes.Cast<>(this, target)); }

    /** 按本表达式的类型绑定的参数，所以挂在类型上的转换器会作用于这个值。value 为 null 时报错。 */
    default Expr<T> param(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Comparing with NULL never matches; use isNull(), or skipEmpty() on the query to drop conditions on empty values");
        }
        return new Nodes.Param<>(type(), value);
    }

    /** 与常量比较；开启 skipEmpty 且 value 为空时不产生条件，否则把 value 绑定为参数。 */
    private void compare(String operator, T value) {
        if (SkipEmpty.skips(value)) {
            Conditions.add(Cond.EMPTY);
            return;
        }
        Conditions.add(Nodes.compare(this, operator, param(value)));
    }

    /** 把集合里的每个值绑定为参数。 */
    private List<Expr<T>> params(Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        return values.stream().map(this::param).toList();
    }
}

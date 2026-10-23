package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An SQL expression producing values of Java type T.
 *
 * <p>Operations whose operands must share T (comparison, COALESCE, NULLIF, IN) are instance methods:
 * the receiver fixes T before javac infers the argument. A static {@code coalesce(Expr<T>, Expr<T>)}
 * would let javac widen T to a common supertype and accept a String column next to a BigDecimal one.
 *
 * <p>Comparing with null is an error, unless the query runs with {@code skipEmpty()}: then a
 * comparison with null, a blank string or an empty collection is no condition at all.
 *
 * <p>Extend this class to add your own expression node; implement {@link #render}.
 */
public abstract non-sealed class Expr<T> implements Renderable, Keys, OrderItem, Selection<T> {
    private final SqlType<T> type;

    protected Expr(SqlType<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public final SqlType<T> type() { return type; }

    /** The column label used when this expression is projected; a column's name, or null. */
    public String label() { return null; }

    /** The name in {@code cols(...)}: the label, or the name of the one column a function or aggregate wraps. */
    String nameHint() { return label(); }

    // ---------- comparison ----------

    public final Cond eq(T value) { return compare("=", value); }
    public final Cond eq(Expr<T> other) { return Nodes.compare(this, "=", other); }

    public final Cond ne(T value) { return compare("<>", value); }
    public final Cond ne(Expr<T> other) { return Nodes.compare(this, "<>", other); }

    public final Cond gt(T value) { return compare(">", value); }
    public final Cond gt(Expr<T> other) { return Nodes.compare(this, ">", other); }

    public final Cond ge(T value) { return compare(">=", value); }
    public final Cond ge(Expr<T> other) { return Nodes.compare(this, ">=", other); }

    public final Cond lt(T value) { return compare("<", value); }
    public final Cond lt(Expr<T> other) { return Nodes.compare(this, "<", other); }

    public final Cond le(T value) { return compare("<=", value); }
    public final Cond le(Expr<T> other) { return Nodes.compare(this, "<=", other); }

    /** With {@code skipEmpty()}, a missing bound leaves the other one alone. */
    public final Cond between(T low, T high) {
        if (SkipEmpty.skips(low) || SkipEmpty.skips(high)) return Sql.all(ge(low), le(high));
        return Nodes.between(this, param(low), param(high));
    }
    public final Cond between(Expr<T> low, Expr<T> high) { return Nodes.between(this, low, high); }

    /** An empty collection is always false (no condition with {@code skipEmpty()}); long lists are split by the dialect's IN limit. */
    public final Cond in(Collection<? extends T> values) {
        if (SkipEmpty.skips(values)) return Cond.EMPTY;
        return Nodes.in(this, params(values), false);
    }
    public final Cond in(Query<T> subquery) { return Nodes.inQuery(this, subquery, false); }

    /** An empty collection is always true (no condition with {@code skipEmpty()}). */
    public final Cond notIn(Collection<? extends T> values) {
        if (SkipEmpty.skips(values)) return Cond.EMPTY;
        return Nodes.in(this, params(values), true);
    }
    public final Cond notIn(Query<T> subquery) { return Nodes.inQuery(this, subquery, true); }

    public final Cond isNull() { return Nodes.postfix(this, " IS NULL"); }
    public final Cond isNotNull() { return Nodes.postfix(this, " IS NOT NULL"); }

    private Cond compare(String operator, T value) {
        if (SkipEmpty.skips(value)) return Cond.EMPTY;
        return Nodes.compare(this, operator, param(value));
    }

    // ---------- same-type functions ----------

    public final Expr<T> coalesce(T fallback) { return Nodes.call(type, "COALESCE", List.of(this, param(fallback))); }
    public final Expr<T> coalesce(Expr<T> fallback) { return Nodes.call(type, "COALESCE", List.of(this, fallback)); }

    /** COALESCE(this, a, b, ...): the first non-null of several fallbacks; a constant last one is {@code val(x)}. */
    @SafeVarargs
    public final Expr<T> coalesce(Expr<T> fallback, Expr<T>... more) {
        List<Expr<?>> args = new ArrayList<>(List.of(this, fallback));
        args.addAll(List.of(more));
        return Nodes.call(type, "COALESCE", args);
    }
    public final Expr<T> nullif(T value) { return Nodes.call(type, "NULLIF", List.of(this, param(value))); }

    // ---------- naming, ordering, conversion ----------

    public final Expr<T> as(String label) { return new Nodes.Labeled<>(this, label); }

    public final Order asc() { return new Order(this, false, null); }
    public final Order desc() { return new Order(this, true, null); }

    public final <U> Expr<U> cast(CastType<U> target) { return new Nodes.Cast<>(this, target); }

    /** A parameter bound with this expression's type, so a mounted converter applies to the value. */
    protected final Expr<T> param(T value) {
        if (value == null) {
            throw new IllegalArgumentException("Comparing with NULL never matches; use isNull(), or skipEmpty() on the query to drop conditions on empty values");
        }
        return new Nodes.Param<>(type, value);
    }

    private List<Expr<T>> params(Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        return values.stream().map(v -> param(v)).toList();
    }
}

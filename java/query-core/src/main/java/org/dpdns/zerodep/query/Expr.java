package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.meta.Getter;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An SQL expression producing values of Java type T.
 *
 * <p>An abstract class rather than a functional interface, so overloads taking a getter reference
 * are never ambiguous. Operations whose operands must share T (comparison, COALESCE, NULLIF, IN)
 * are instance methods: the receiver fixes T before javac infers the argument. A static
 * {@code coalesce(Expr<T>, Expr<T>)} would let javac widen T to a common supertype and accept a
 * String column next to a BigDecimal one.
 *
 * <p>Extend this class to add your own expression node; implement {@link #render}.
 */
public abstract class Expr<T> implements Renderable {
    private final SqlType<T> type;

    protected Expr(SqlType<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public final SqlType<T> type() { return type; }

    /** The column label used when this expression is projected; a column's property name, or null. */
    public String label() { return null; }

    // ---------- comparison ----------

    public final Cond eq(T value) { return Nodes.compare(this, "=", param(value)); }
    public final Cond eq(Expr<T> other) { return Nodes.compare(this, "=", other); }
    public final <E> Cond eq(Ref<E> ref, Getter<? super E, T> getter) { return eq(ref.col(getter)); }

    public final Cond ne(T value) { return Nodes.compare(this, "<>", param(value)); }
    public final Cond ne(Expr<T> other) { return Nodes.compare(this, "<>", other); }
    public final <E> Cond ne(Ref<E> ref, Getter<? super E, T> getter) { return ne(ref.col(getter)); }

    public final Cond gt(T value) { return Nodes.compare(this, ">", param(value)); }
    public final Cond gt(Expr<T> other) { return Nodes.compare(this, ">", other); }
    public final Cond ge(T value) { return Nodes.compare(this, ">=", param(value)); }
    public final Cond ge(Expr<T> other) { return Nodes.compare(this, ">=", other); }
    public final Cond lt(T value) { return Nodes.compare(this, "<", param(value)); }
    public final Cond lt(Expr<T> other) { return Nodes.compare(this, "<", other); }
    public final Cond le(T value) { return Nodes.compare(this, "<=", param(value)); }
    public final Cond le(Expr<T> other) { return Nodes.compare(this, "<=", other); }

    public final Cond between(T low, T high) { return Nodes.between(this, param(low), param(high)); }
    public final Cond between(Expr<T> low, Expr<T> high) { return Nodes.between(this, low, high); }

    /** An empty collection is always false; long lists are split by the dialect's IN limit. */
    public final Cond in(Collection<? extends T> values) { return Nodes.in(this, params(values), false); }
    public final Cond in(Query<T> subquery) { return Nodes.inQuery(this, subquery, false); }
    /** An empty collection is always true. */
    public final Cond notIn(Collection<? extends T> values) { return Nodes.in(this, params(values), true); }
    public final Cond notIn(Query<T> subquery) { return Nodes.inQuery(this, subquery, true); }

    public final Cond isNull() { return Nodes.postfix(this, " IS NULL"); }
    public final Cond isNotNull() { return Nodes.postfix(this, " IS NOT NULL"); }

    // ---------- optional filters: a null (or empty) argument means "no condition" ----------

    public final Cond eqIfPresent(T value) { return value == null ? Cond.EMPTY : eq(value); }
    public final Cond neIfPresent(T value) { return value == null ? Cond.EMPTY : ne(value); }
    public final Cond gtIfPresent(T value) { return value == null ? Cond.EMPTY : gt(value); }
    public final Cond geIfPresent(T value) { return value == null ? Cond.EMPTY : ge(value); }
    public final Cond ltIfPresent(T value) { return value == null ? Cond.EMPTY : lt(value); }
    public final Cond leIfPresent(T value) { return value == null ? Cond.EMPTY : le(value); }

    /** Each bound applies only when present. */
    public final Cond betweenIfPresent(T low, T high) { return Sql.all(geIfPresent(low), leIfPresent(high)); }

    public final Cond inIfPresent(Collection<? extends T> values) {
        return values == null || values.isEmpty() ? Cond.EMPTY : in(values);
    }

    // ---------- same-type functions ----------

    public final Expr<T> coalesce(T fallback) { return Nodes.call(type, "COALESCE", List.of(this, param(fallback))); }
    public final Expr<T> coalesce(Expr<T> fallback) { return Nodes.call(type, "COALESCE", List.of(this, fallback)); }
    public final <E> Expr<T> coalesce(Ref<E> ref, Getter<? super E, T> getter) { return coalesce(ref.col(getter)); }
    public final Expr<T> nullif(T value) { return Nodes.call(type, "NULLIF", List.of(this, param(value))); }

    // ---------- naming, ordering, conversion ----------

    public final Expr<T> as(String label) { return new Nodes.Labeled<>(this, label); }

    /** Labels the expression after a target property; javac checks the property accepts T. */
    public final <V> Expr<T> as(Getter<V, ? super T> property) { return as(Metadata.propertyName(property)); }

    public final Order asc() { return new Order(this, false, null); }
    public final Order desc() { return new Order(this, true, null); }

    public final <U> Expr<U> cast(CastType<U> target) { return new Nodes.Cast<>(this, target); }

    /** A parameter bound with this expression's type, so a mounted converter applies to the value. */
    protected final Expr<T> param(T value) {
        if (value == null) throw new IllegalArgumentException("Comparing with NULL never matches; use isNull() or an ...IfPresent method");
        return new Nodes.Param<>(type, value);
    }

    private List<Expr<T>> params(Collection<? extends T> values) {
        Objects.requireNonNull(values, "values");
        return values.stream().map(v -> param(v)).toList();
    }
}

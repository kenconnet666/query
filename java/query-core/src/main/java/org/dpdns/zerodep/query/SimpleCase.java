package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code CASE subject WHEN value THEN result ... END}, a code table written inline. Started by
 * {@link Sql#caseOf}; the first branch fixes the result type T.
 */
public final class SimpleCase<V, T> {
    private final Expr<V> subject;
    private final SqlType<T> type;
    private final List<Expr<V>> values;
    private final List<Expr<T>> results;

    private SimpleCase(Expr<V> subject, SqlType<T> type, List<Expr<V>> values, List<Expr<T>> results) {
        this.subject = subject;
        this.type = type;
        this.values = values;
        this.results = results;
    }

    /** The subject before its first branch. */
    public static final class Start<V> {
        private final Expr<V> subject;

        Start(Expr<V> subject) { this.subject = subject; }

        public <T> SimpleCase<V, T> when(V value, Expr<T> result) {
            return new SimpleCase<>(subject, result.type(), List.<Expr<V>>of(), List.<Expr<T>>of()).when(value, result);
        }

        @SuppressWarnings("unchecked")
        public <T> SimpleCase<V, T> when(V value, T result) {
            return when(value, new Nodes.Param<>((SqlType<T>) SqlType.of(result.getClass()), result));
        }
    }

    public SimpleCase<V, T> when(V value, Expr<T> result) {
        List<Expr<V>> v = new ArrayList<>(values);
        List<Expr<T>> r = new ArrayList<>(results);
        v.add(subject.param(value));
        r.add(result);
        return new SimpleCase<>(subject, type, v, r);
    }

    public SimpleCase<V, T> when(V value, T result) { return when(value, new Nodes.Param<>(type, result)); }

    public Expr<T> otherwise(Expr<T> result) { return new Case.CaseExpr<>(type, subject, values, results, result); }

    public Expr<T> otherwise(T result) { return otherwise(new Nodes.Param<>(type, result)); }

    /** No ELSE: unmatched rows give NULL. */
    public Expr<T> end() { return new Case.CaseExpr<>(type, subject, values, results, null); }
}

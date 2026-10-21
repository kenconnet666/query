package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;

/** CASE WHEN ... THEN ... builder; the first branch fixes T for all others. */
public final class Case<T> {
    private final SqlType<T> type;
    private final List<Cond> conditions;
    private final List<Expr<T>> results;

    Case(SqlType<T> type, List<Cond> conditions, List<Expr<T>> results) {
        this.type = type;
        this.conditions = conditions;
        this.results = results;
    }

    public Case<T> when(Cond condition, Expr<T> result) {
        List<Cond> c = new ArrayList<>(conditions);
        List<Expr<T>> r = new ArrayList<>(results);
        c.add(condition);
        r.add(result);
        return new Case<>(type, c, r);
    }

    public Case<T> when(Cond condition, T result) { return when(condition, new Nodes.Param<>(type, result)); }

    public Expr<T> otherwise(Expr<T> result) { return new CaseExpr<>(type, conditions, results, result); }

    public Expr<T> otherwise(T result) { return otherwise(new Nodes.Param<>(type, result)); }

    /** No ELSE: unmatched rows give NULL. */
    public Expr<T> end() { return new CaseExpr<>(type, conditions, results, null); }

    private static final class CaseExpr<T> extends Expr<T> {
        private final List<Cond> conditions;
        private final List<Expr<T>> results;
        private final Expr<T> otherwise;

        CaseExpr(SqlType<T> type, List<Cond> conditions, List<Expr<T>> results, Expr<T> otherwise) {
            super(type);
            this.conditions = List.copyOf(conditions);
            this.results = List.copyOf(results);
            this.otherwise = otherwise;
        }

        @Override public void render(SqlWriter w) {
            w.sql("CASE");
            for (int i = 0; i < conditions.size(); i++) {
                Cond c = conditions.get(i);
                if (c.isEmpty()) throw new IllegalStateException("A CASE branch has no condition");
                w.sql(" WHEN ").render(c).sql(" THEN ").render(results.get(i));
            }
            if (otherwise != null) w.sql(" ELSE ").render(otherwise);
            w.sql(" END");
        }
    }
}

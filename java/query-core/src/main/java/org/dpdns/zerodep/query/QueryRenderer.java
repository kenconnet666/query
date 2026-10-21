package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;

import java.util.ArrayList;
import java.util.List;

/** Writes SELECT statements. Database-wide filters reach it through the writer's attributes. */
final class QueryRenderer {
    private QueryRenderer() { }

    static Rendered statement(SqlWriter w, Query<?> q) {
        query(w, q, true, true);
        return w.statement();
    }

    /** SELECT COUNT(*) over the query; ORDER BY is dropped unless a LIMIT needs it. */
    static Rendered count(SqlWriter w, Query<?> q) {
        w.sql("SELECT COUNT(*) FROM (");
        query(w, q, false, true);
        w.sql(") ").alias(w.aliasFor(new Object(), "counted"));
        return w.statement();
    }

    /** A query whose column names matter: a statement, a CTE body or a derived table. */
    static void named(SqlWriter w, Query<?> q) { query(w, q, true, true); }

    /** A query used as a value: EXISTS, IN or a scalar subquery; its columns need no names. */
    static void subquery(SqlWriter w, Query<?> q) { query(w, q, true, false); }

    /** @param ordered false drops ORDER BY from arms that have no LIMIT or OFFSET */
    private static void query(SqlWriter w, Query<?> q, boolean ordered, boolean labeled) {
        List<Query.Arm> arms = q.arms();
        for (int i = 0; i < arms.size(); i++) {
            if (i > 0) w.sql("\n").sql(q.operators().get(i - 1)).sql("\n");
            arm(w, arms.get(i), i == 0 && labeled ? q.labels() : null, ordered);
        }
        if (arms.size() > 1) w.dialect().limit(w, q.limitValue(), q.offsetValue());
    }

    private static void arm(SqlWriter w, Query.Arm arm, List<String> labels, boolean ordered) {
        SelectSpec s = arm.spec();
        // Aliases in join order, so the FROM table gets the plain name even if SELECT mentions a joined one first.
        for (SelectSpec.Join j : s.joins) j.ref().alias(w);
        for (Clause c : s.clauses) {
            if (c.dialect() != null && !c.dialect().isInstance(w.dialect())) {
                throw new UnsupportedOperationException("A " + c.dialect().getSimpleName() + " clause cannot run on " + w.dialect().name());
            }
        }
        w.sql("SELECT ");
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.HINT) w.render(c).sql(" ");
        }
        boolean replacedDistinct = false;
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.DISTINCT) {
                w.render(c).sql(" ");
                replacedDistinct = true;
            }
        }
        if (s.distinct && !replacedDistinct) w.sql("DISTINCT ");
        // Constants in the SELECT list are literals: HANA cannot type a parameter there (COALESCE(x, ?) in a
        // CTE is not numeric to an outer SUM). GROUP BY, DISTINCT and their ORDER BY match expressions
        // textually, so their constants must be literals too.
        boolean grouped = !s.groupBy.isEmpty() || s.distinct || replacedDistinct;
        boolean outer = w.inlining(true);
        List<Expr<?>> items = arm.items();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) w.sql(", ");
            w.render(items.get(i));
            if (labels != null) w.sql(" AS ").identifier(labels.get(i));
        }
        w.inlining(false);
        if (s.joins.isEmpty()) w.dialect().fromNothing(w);
        SourceFilters filters = s.filtered ? (SourceFilters) w.attribute(SourceFilters.class) : null;
        if (filters == null) filters = SourceFilters.NONE;
        List<Cond> where = new ArrayList<>();
        for (SelectSpec.Join j : s.joins) {
            List<Cond> extra = filters.conditionsFor(j.ref());
            switch (j.kind()) {
                case FROM -> {
                    w.sql("\nFROM ");
                    j.ref().renderSource(w);
                    where.addAll(extra);
                }
                case CROSS -> {
                    w.sql("\nCROSS JOIN ");
                    j.ref().renderSource(w);
                    where.addAll(extra);
                }
                default -> {
                    w.sql("\n").sql(j.kind().sql).sql(" ");
                    j.ref().renderSource(w);
                    // Filters of a joined table belong to ON, so a LEFT JOIN stays a LEFT JOIN.
                    List<Cond> on = new ArrayList<>();
                    on.add(j.on());
                    on.addAll(extra);
                    w.sql(" ON ").render(Nodes.all(on));
                }
            }
        }
        where.addAll(s.where);
        Cond whereAll = Nodes.all(where);
        if (!whereAll.isEmpty()) w.sql("\nWHERE ").render(whereAll);
        w.inlining(grouped);
        if (!s.groupBy.isEmpty()) w.sql("\nGROUP BY ").join(s.groupBy, ", ");
        Cond having = Nodes.all(s.having);
        if (!having.isEmpty()) w.sql("\nHAVING ").render(having);
        boolean limited = s.limit != null || s.offset != null;
        if (!s.orderBy.isEmpty() && (ordered || limited)) w.sql("\nORDER BY ").join(s.orderBy, ", ");
        w.inlining(outer);
        w.dialect().limit(w, s.limit, s.offset);
        for (Clause c : s.clauses) {
            if (c.position() == Clause.Position.END) w.sql("\n").render(c);
        }
    }
}

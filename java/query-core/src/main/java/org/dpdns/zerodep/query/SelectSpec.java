package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Clause;

import java.util.ArrayList;
import java.util.List;

/** Everything of one SELECT except its projection. Immutable: each change returns a copy. */
final class SelectSpec {
    enum JoinKind {
        FROM("FROM"), INNER("JOIN"), LEFT("LEFT JOIN"), CROSS("CROSS JOIN");

        final String sql;

        JoinKind(String sql) { this.sql = sql; }
    }

    record Join(JoinKind kind, Ref<?> ref, Cond on) { }

    final List<Join> joins;
    final List<Cond> where;
    final List<Expr<?>> groupBy;
    /** GROUP BY ROLLUP over all keys: subtotals per prefix of the keys and a grand total. */
    final boolean rollup;
    final List<Cond> having;
    final List<Order> orderBy;
    final boolean distinct;
    final Integer limit;
    final Integer offset;
    final boolean filtered;
    final List<Clause> clauses;

    private SelectSpec(List<Join> joins, List<Cond> where, List<Expr<?>> groupBy, boolean rollup, List<Cond> having, List<Order> orderBy,
                       boolean distinct, Integer limit, Integer offset, boolean filtered, List<Clause> clauses) {
        this.joins = joins;
        this.where = where;
        this.groupBy = groupBy;
        this.rollup = rollup;
        this.having = having;
        this.orderBy = orderBy;
        this.distinct = distinct;
        this.limit = limit;
        this.offset = offset;
        this.filtered = filtered;
        this.clauses = clauses;
    }

    /** A SELECT without a table, such as one row of constants. */
    static SelectSpec none() {
        return new SelectSpec(List.of(), List.of(), List.of(), false, List.of(), List.of(), false, null, null, true, List.of());
    }

    static SelectSpec from(Ref<?> ref) {
        return new SelectSpec(List.of(new Join(JoinKind.FROM, ref, Cond.EMPTY)), List.of(), List.of(), false, List.of(), List.of(),
            false, null, null, true, List.of());
    }

    SelectSpec join(JoinKind kind, Ref<?> ref, Cond on) {
        if (kind != JoinKind.CROSS && (on == null || on.isEmpty())) {
            throw new IllegalArgumentException(kind.sql + " needs a condition; use crossJoin for a cartesian product");
        }
        return new SelectSpec(plus(joins, new Join(kind, ref, on == null ? Cond.EMPTY : on)), where, groupBy, rollup, having, orderBy,
            distinct, limit, offset, filtered, clauses);
    }

    SelectSpec where(Cond c) {
        return new SelectSpec(joins, plus(where, nonNull(c)), groupBy, rollup, having, orderBy, distinct, limit, offset, filtered, clauses);
    }

    SelectSpec groupBy(List<? extends Expr<?>> keys) {
        if (rollup) throw new IllegalStateException("groupByRollup already lists every grouping key");
        List<Expr<?>> all = new ArrayList<>(groupBy);
        all.addAll(keys);
        return new SelectSpec(joins, where, List.copyOf(all), false, having, orderBy, distinct, limit, offset, filtered, clauses);
    }

    SelectSpec groupByRollup(List<? extends Expr<?>> keys) {
        if (!groupBy.isEmpty()) throw new IllegalStateException("groupByRollup must list every grouping key; it cannot follow groupBy");
        if (keys.isEmpty()) throw new IllegalArgumentException("groupByRollup needs at least one key");
        return new SelectSpec(joins, where, List.copyOf(keys), true, having, orderBy, distinct, limit, offset, filtered, clauses);
    }

    SelectSpec having(Cond c) {
        return new SelectSpec(joins, where, groupBy, rollup, plus(having, nonNull(c)), orderBy, distinct, limit, offset, filtered, clauses);
    }

    SelectSpec orderBy(List<Order> orders) {
        List<Order> all = new ArrayList<>(orderBy);
        all.addAll(orders);
        return new SelectSpec(joins, where, groupBy, rollup, having, List.copyOf(all), distinct, limit, offset, filtered, clauses);
    }

    SelectSpec distinct() { return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, true, limit, offset, filtered, clauses); }

    SelectSpec limit(int rows) {
        if (rows < 0) throw new IllegalArgumentException("limit must not be negative");
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, rows, offset, filtered, clauses);
    }

    SelectSpec offset(int rows) {
        if (rows < 0) throw new IllegalArgumentException("offset must not be negative");
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, limit, rows, filtered, clauses);
    }

    SelectSpec unfiltered() { return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, limit, offset, false, clauses); }

    SelectSpec clause(Clause c) {
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, limit, offset, filtered, plus(clauses, c));
    }

    boolean hasOrderOrLimit() { return !orderBy.isEmpty() || limit != null || offset != null; }

    private static Cond nonNull(Cond c) {
        if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
        return c;
    }

    private static <T> List<T> plus(List<T> list, T item) {
        List<T> copy = new ArrayList<>(list);
        copy.add(item);
        return List.copyOf(copy);
    }
}

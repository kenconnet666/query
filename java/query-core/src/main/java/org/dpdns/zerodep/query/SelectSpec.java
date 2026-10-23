package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Clause;

import java.util.ArrayList;
import java.util.List;

/** 一条 SELECT 除投影以外的全部内容。不可变：每次修改都返回副本。 */
final class SelectSpec {
    /** FROM 与各种 JOIN，sql 是渲染时写出的关键字。 */
    enum JoinKind {
        FROM("FROM"), INNER("JOIN"), LEFT("LEFT JOIN"), CROSS("CROSS JOIN");

        /** 渲染时写出的关键字。 */
        final String sql;

        /** 指定渲染时的关键字。 */
        JoinKind(String sql) { this.sql = sql; }
    }

    /** 一个来源出现在 FROM 或 JOIN 里，on 是连接条件（FROM 和 CROSS JOIN 为空条件）。 */
    record Join(JoinKind kind, Ref<?> ref, Cond on) { }

    /** FROM 与各 JOIN，按出现顺序；第一个是 FROM，没有来源时为空。 */
    final List<Join> joins;

    /** WHERE 条件，渲染时以 AND 连接。 */
    final List<Cond> where;

    /** GROUP BY 的分组键。 */
    final List<Expr<?>> groupBy;
    /** GROUP BY ROLLUP：按分组键的每个前缀出小计，最后出总计。 */
    final boolean rollup;

    /** HAVING 条件，渲染时以 AND 连接。 */
    final List<Cond> having;

    /** ORDER BY 的排序项。 */
    final List<Order> orderBy;

    /** 是否 SELECT DISTINCT。 */
    final boolean distinct;

    /** 最多返回的行数，null 表示不限。 */
    final Integer limit;

    /** 跳过的行数，null 表示不跳过。 */
    final Integer offset;
    /** 是否套用数据库实例的全局过滤器。 */
    final boolean filtered;
    /** 是否开启 skipEmpty：与空值比较的条件直接省略。 */
    final boolean skipEmpty;

    /** 方言专属的子句，例如 HANA 的提示。 */
    final List<Clause> clauses;

    /** 全部字段逐个赋值；每个修改方法都通过它构造副本。 */
    private SelectSpec(List<Join> joins, List<Cond> where, List<Expr<?>> groupBy, boolean rollup, List<Cond> having, List<Order> orderBy,
                       boolean distinct, Integer limit, Integer offset, boolean filtered, boolean skipEmpty, List<Clause> clauses) {
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
        this.skipEmpty = skipEmpty;
        this.clauses = clauses;
    }

    /** 没有来源的 SELECT，例如一行常量。 */
    static SelectSpec none() {
        return new SelectSpec(List.of(), List.of(), List.of(), false, List.of(), List.of(), false, null, null, true, false, List.of());
    }

    /** 以一个来源开始的 SELECT。 */
    static SelectSpec from(Ref<?> ref) {
        return new SelectSpec(List.of(new Join(JoinKind.FROM, ref, Cond.EMPTY)), List.of(), List.of(), false, List.of(), List.of(),
            false, null, null, true, false, List.of());
    }

    /** 追加一个连接；除 CROSS JOIN 外必须有条件。 */
    SelectSpec join(JoinKind kind, Ref<?> ref, Cond on) {
        if (kind != JoinKind.CROSS && (on == null || on.isEmpty())) {
            throw new IllegalArgumentException(kind.sql + " needs a condition; use crossJoin for a cartesian product");
        }
        return new SelectSpec(plus(joins, new Join(kind, ref, on == null ? Cond.EMPTY : on)), where, groupBy, rollup, having, orderBy,
            distinct, limit, offset, filtered, skipEmpty, clauses);
    }

    /** 追加 WHERE 条件，多次调用之间是 AND。 */
    SelectSpec where(Cond c) {
        return new SelectSpec(joins, plus(where, nonNull(c)), groupBy, rollup, having, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses);
    }

    /** 追加分组键。 */
    SelectSpec groupBy(List<? extends Expr<?>> keys) {
        if (rollup) throw new IllegalStateException("groupByRollup already lists every grouping key");
        List<Expr<?>> all = new ArrayList<>(groupBy);
        all.addAll(keys);
        return new SelectSpec(joins, where, List.copyOf(all), false, having, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses);
    }

    /** 一次列出全部分组键的 ROLLUP，不能与 groupBy 混用。 */
    SelectSpec groupByRollup(List<? extends Expr<?>> keys) {
        if (!groupBy.isEmpty()) throw new IllegalStateException("groupByRollup must list every grouping key; it cannot follow groupBy");
        if (keys.isEmpty()) throw new IllegalArgumentException("groupByRollup needs at least one key");
        return new SelectSpec(joins, where, List.copyOf(keys), true, having, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses);
    }

    /** 追加 HAVING 条件，多次调用之间是 AND。 */
    SelectSpec having(Cond c) {
        return new SelectSpec(joins, where, groupBy, rollup, plus(having, nonNull(c)), orderBy, distinct, limit, offset, filtered, skipEmpty, clauses);
    }

    /** 追加排序项。 */
    SelectSpec orderBy(List<Order> orders) {
        List<Order> all = new ArrayList<>(orderBy);
        all.addAll(orders);
        return new SelectSpec(joins, where, groupBy, rollup, having, List.copyOf(all), distinct, limit, offset, filtered, skipEmpty, clauses);
    }

    /** SELECT DISTINCT。 */
    SelectSpec distinct() {
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, true, limit, offset, filtered, skipEmpty, clauses);
    }

    /** 最多返回的行数。 */
    SelectSpec limit(int rows) {
        if (rows < 0) throw new IllegalArgumentException("limit must not be negative");
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, rows, offset, filtered, skipEmpty, clauses);
    }

    /** 跳过的行数。 */
    SelectSpec offset(int rows) {
        if (rows < 0) throw new IllegalArgumentException("offset must not be negative");
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, limit, rows, filtered, skipEmpty, clauses);
    }

    /** 本条 SELECT 不套用全局过滤器。 */
    SelectSpec unfiltered() {
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, limit, offset, false, skipEmpty, clauses);
    }

    /** 之后的 WHERE、HAVING、ON 里，与空值比较的条件直接省略。 */
    SelectSpec skipEmpty() {
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, limit, offset, filtered, true, clauses);
    }

    /** 追加方言子句，例如 HANA 的提示。 */
    SelectSpec clause(Clause c) {
        return new SelectSpec(joins, where, groupBy, rollup, having, orderBy, distinct, limit, offset, filtered, skipEmpty, plus(clauses, c));
    }

    /** 是否已有 ORDER BY、LIMIT 或 OFFSET；UNION 的分支不允许有。 */
    boolean hasOrderOrLimit() { return !orderBy.isEmpty() || limit != null || offset != null; }

    /** 条件不能为 null；没有条件要明确写 {@link Cond#EMPTY}。 */
    private static Cond nonNull(Cond c) {
        if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
        return c;
    }

    /** 在列表末尾追加一项，返回新的不可变列表。 */
    private static <T> List<T> plus(List<T> list, T item) {
        List<T> copy = new ArrayList<>(list);
        copy.add(item);
        return List.copyOf(copy);
    }
}

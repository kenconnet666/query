package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Order;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.table.Ref;

import java.util.ArrayList;
import java.util.List;

/** 一条 SELECT 除投影以外的全部内容。不可变：每次修改都返回副本。 */
public final class SelectSpec {
    /** FROM 与各种 JOIN，sql 是渲染时写出的关键字。 */
    public enum JoinKind {
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

    /** 窗口函数上的条件（QUALIFY）：渲染时整段查询包一层派生表，在外层按它过滤。 */
    final List<Cond> qualify;

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
    public final boolean skipEmpty;

    /** 方言专属的子句，例如 HANA 的提示。 */
    final List<Clause> clauses;

    /** groupJoin 的一对多关系：它们上的集合运算合成按连接键分组的派生表。 */
    final List<Many<?>> groupJoins;

    /** 全部字段逐个赋值；每个修改方法都通过它构造副本。 */
    private SelectSpec(List<Join> joins, List<Cond> where, List<Expr<?>> groupBy, boolean rollup, List<Cond> having, List<Cond> qualify, List<Order> orderBy,
                       boolean distinct, Integer limit, Integer offset, boolean filtered, boolean skipEmpty, List<Clause> clauses,
                       List<Many<?>> groupJoins) {
        this.joins = joins;
        this.where = where;
        this.groupBy = groupBy;
        this.rollup = rollup;
        this.having = having;
        this.qualify = qualify;
        this.orderBy = orderBy;
        this.distinct = distinct;
        this.limit = limit;
        this.offset = offset;
        this.filtered = filtered;
        this.skipEmpty = skipEmpty;
        this.clauses = clauses;
        this.groupJoins = groupJoins;
    }

    /** 没有来源的 SELECT，例如一行常量。 */
    static SelectSpec none() {
        return new SelectSpec(List.of(), List.of(), List.of(), false, List.of(), List.of(), List.of(), false, null, null, true, false, List.of(), List.of());
    }

    /** 以一个来源开始的 SELECT。 */
    public static SelectSpec from(Ref<?> ref) {
        return new SelectSpec(List.of(new Join(JoinKind.FROM, ref, Cond.EMPTY)), List.of(), List.of(), false, List.of(), List.of(), List.of(),
            false, null, null, true, false, List.of(), List.of());
    }

    /** 追加一个连接；除 CROSS JOIN 外必须有条件。 */
    public SelectSpec join(JoinKind kind, Ref<?> ref, Cond on) {
        if (kind != JoinKind.CROSS && (on == null || on.isEmpty())) {
            throw new IllegalArgumentException(kind.sql + " needs a condition; use crossJoin for a cartesian product");
        }
        return new SelectSpec(plus(joins, new Join(kind, ref, on == null ? Cond.EMPTY : on)), where, groupBy, rollup, having, qualify, orderBy,
            distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 追加 WHERE 条件，多次调用之间是 AND。 */
    public SelectSpec where(Cond c) {
        return new SelectSpec(joins, plus(where, nonNull(c)), groupBy, rollup, having, qualify, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 追加分组键。 */
    public SelectSpec groupBy(List<? extends Expr<?>> keys) {
        if (rollup) throw new IllegalStateException("groupByRollup already lists every grouping key");
        List<Expr<?>> all = new ArrayList<>(groupBy);
        all.addAll(keys);
        return new SelectSpec(joins, where, List.copyOf(all), false, having, qualify, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 一次列出全部分组键的 ROLLUP，不能与 groupBy 混用。 */
    public SelectSpec groupByRollup(List<? extends Expr<?>> keys) {
        if (!groupBy.isEmpty()) throw new IllegalStateException("groupByRollup must list every grouping key; it cannot follow groupBy");
        if (keys.isEmpty()) throw new IllegalArgumentException("groupByRollup needs at least one key");
        return new SelectSpec(joins, where, List.copyOf(keys), true, having, qualify, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 追加 HAVING 条件，多次调用之间是 AND。 */
    public SelectSpec having(Cond c) {
        return new SelectSpec(joins, where, groupBy, rollup, plus(having, nonNull(c)), qualify, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 让 relation 上的集合运算合成按连接键分组的派生表。 */
    public SelectSpec groupJoin(Many<?> relation) {
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, orderBy, distinct, limit, offset, filtered, skipEmpty, clauses,
            plus(groupJoins, relation));
    }

    /** 追加窗口函数上的条件，多次调用之间是 AND。 */
    public SelectSpec qualify(Cond c) {
        return new SelectSpec(joins, where, groupBy, rollup, having, plus(qualify, nonNull(c)), orderBy, distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** QUALIFY 包出的派生表里的那段查询：去掉 QUALIFY、ORDER BY、LIMIT、OFFSET 和写在语句末尾的子句，它们写在外层。 */
    SelectSpec inner() {
        List<Clause> kept = clauses.stream().filter(c -> c.position() != Clause.Position.END).toList();
        return new SelectSpec(joins, where, groupBy, rollup, having, List.of(), List.of(), distinct, null, null, filtered, skipEmpty, kept, groupJoins);
    }

    /** 追加排序项。 */
    public SelectSpec orderBy(List<Order> orders) {
        List<Order> all = new ArrayList<>(orderBy);
        all.addAll(orders);
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, List.copyOf(all), distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 用 orders 替换已有的排序项。 */
    public SelectSpec reorder(List<Order> orders) {
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, List.copyOf(orders), distinct, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** SELECT DISTINCT。 */
    public SelectSpec distinct() {
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, orderBy, true, limit, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 最多返回的行数。 */
    public SelectSpec limit(int rows) {
        if (rows < 0) throw new IllegalArgumentException("limit must not be negative");
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, orderBy, distinct, rows, offset, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 跳过的行数。 */
    public SelectSpec offset(int rows) {
        if (rows < 0) throw new IllegalArgumentException("offset must not be negative");
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, orderBy, distinct, limit, rows, filtered, skipEmpty, clauses, groupJoins);
    }

    /** 本条 SELECT 不套用全局过滤器。 */
    public SelectSpec unfiltered() {
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, orderBy, distinct, limit, offset, false, skipEmpty, clauses, groupJoins);
    }

    /** 之后的 WHERE、HAVING、ON 里，与空值比较的条件直接省略。 */
    public SelectSpec skipEmpty() {
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, orderBy, distinct, limit, offset, filtered, true, clauses, groupJoins);
    }

    /** 追加方言子句，例如 HANA 的提示。 */
    public SelectSpec clause(Clause c) {
        return new SelectSpec(joins, where, groupBy, rollup, having, qualify, orderBy, distinct, limit, offset, filtered, skipEmpty, plus(clauses, c), groupJoins);
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

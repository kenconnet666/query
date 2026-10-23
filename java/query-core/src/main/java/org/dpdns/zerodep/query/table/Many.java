package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.OrderItem;
import org.dpdns.zerodep.query.scope.From1;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.SelectSpec;
import org.dpdns.zerodep.query.type.SqlType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 一对多关系：目标表里满足连接条件的那些行，来自表接口的 {@code many(...)}。它不连接进主查询（那会让主查询的行重复），
 * 只能通过集合运算使用：
 * <ul>
 *   <li>{@link #any()}、{@link #none()}：渲染为 EXISTS / NOT EXISTS；</li>
 *   <li>{@link #count()}、{@link #sum}、{@link #max}、{@link #min}、{@link #avg}：渲染为相关标量子查询；</li>
 *   <li>{@link #first}：每组按顺序取第一行，渲染为 ROW_NUMBER 派生表再 LEFT JOIN 回来，返回那一行的表接口。</li>
 * </ul>
 * {@link #where} 先筛选目标行，例如 {@code h.items().where(i -> i.blocked().eq(true)).count()}。
 *
 * @param <R> 目标表接口
 */
public final class Many<R extends Entity> {
    /** 关系所属的那次表出现。 */
    private final Ref<?> owner;

    /** 目标表接口。 */
    private final Class<R> target;

    /** 连接条件：目标行与所属行如何对应。 */
    private final Function<R, Cond> on;

    /** 追加的筛选条件，没有时为 null。 */
    private final Function<R, Cond> filter;

    /** 由表接口的 {@code many(...)} 创建。 */
    Many(Ref<?> owner, Class<R> target, Function<R, Cond> on, Function<R, Cond> filter) {
        this.owner = owner;
        this.target = target;
        this.on = on;
        this.filter = filter;
    }

    /** 只看满足 condition 的目标行，返回新的 Many。 */
    public Many<R> where(Function<R, Cond> condition) {
        Function<R, Cond> before = filter;
        return new Many<>(owner, target, on, before == null ? condition : r -> Sql.all(before.apply(r), condition.apply(r)));
    }

    /** 至少有一行：{@code EXISTS (...)}。 */
    public Cond any() { return Sql.exists(scope()); }

    /** 至少有一行满足 condition。 */
    public Cond any(Function<R, Cond> condition) { return where(condition).any(); }

    /** 一行也没有：{@code NOT EXISTS (...)}。 */
    public Cond none() { return Sql.notExists(scope()); }

    /** 没有一行满足 condition。 */
    public Cond none(Function<R, Cond> condition) { return where(condition).none(); }

    /** 行数，相关子查询 {@code (SELECT COUNT(*) ...)}。 */
    public Expr<Long> count() { return Sql.scalar(scope().select(r -> Sql.count())); }

    /** 某个值的合计；没有行时为 NULL。 */
    public <N extends Number> Expr<N> sum(Function<R, Expr<N>> value) { return Sql.scalar(scope().select(r -> Sql.sum(value.apply(r)))); }

    /** 某个值的最大值；没有行时为 NULL。 */
    public <T> Expr<T> max(Function<R, Expr<T>> value) { return Sql.scalar(scope().select(r -> Sql.max(value.apply(r)))); }

    /** 某个值的最小值；没有行时为 NULL。 */
    public <T> Expr<T> min(Function<R, Expr<T>> value) { return Sql.scalar(scope().select(r -> Sql.min(value.apply(r)))); }

    /** 某个值的平均值；没有行时为 NULL。 */
    public Expr<BigDecimal> avg(Function<R, Expr<? extends Number>> value) { return Sql.scalar(scope().select(r -> Sql.avg(value.apply(r)))); }

    /**
     * 每组按 order 排序后的第一行，例如最新的一条记录：{@code m.purchases().first(p -> p.createdOn().desc()).vendor()}。
     * 连接条件里"目标列 = 所属表的列"的部分作为分组键，其余条件只筛选目标行。渲染为
     * {@code LEFT JOIN (SELECT 目标表各列, ROW_NUMBER() OVER (PARTITION BY 键 ORDER BY ...) ...) ON 键相等 AND 行号 = 1}，
     * 所以没有匹配行时各列为 NULL。HANA 没有 LATERAL，三个库都用这一种写法。
     */
    public R first(Function<R, ? extends OrderItem> order) {
        Ref<R> inner = Ref.table(target);
        R row = inner.param();
        Cond condition = filter == null ? on.apply(row) : Sql.all(on.apply(row), filter.apply(row));
        List<Col<?>> keys = new ArrayList<>();
        List<Expr<?>> outer = new ArrayList<>();
        List<Cond> filters = new ArrayList<>();
        for (Cond c : Nodes.conjuncts(condition)) {
            if (c instanceof Nodes.Comparison cmp && cmp.operator.equals("=") && link(cmp.left, cmp.right, inner, keys, outer)) continue;
            if (c instanceof Nodes.Comparison cmp && cmp.operator.equals("=") && link(cmp.right, cmp.left, inner, keys, outer)) continue;
            filters.add(c);
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("first() needs an equality between a column of " + target.getSimpleName()
                + " and a column of the table the relation belongs to, e.g. i -> i.delivery().eq(delivery())");
        }
        Expr<?>[] partition = keys.toArray(Expr<?>[]::new);
        Query<?> ranked = Query.select(SelectSpec.from(inner).where(Sql.all(filters)),
            Sql.cols(((Entity) row).columns(), Sql.rowNumber().over(w -> w.partitionBy(partition).orderBy(order.apply(row))).as(ROW_NUMBER)));
        Ref<R> first = Ref.of(new Derived<>(ranked, target, target.getSimpleName().toLowerCase(java.util.Locale.ROOT) + "_first"));
        List<Cond> join = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) join.add(Nodes.compare(first.column(keys.get(i).property().name()), "=", outer.get(i)));
        join.add(Nodes.compare(new RowNumber(first), "=", Sql.lit(1L)));
        owner.relate(first, Sql.all(join));
        return first.param();
    }

    /** 行号列在排名子查询里的名字。 */
    private static final String ROW_NUMBER = "rn__";

    /** maybeInner 是目标表的列、other 不是时，记为一对分组键，返回 true。 */
    private static boolean link(Expr<?> maybeInner, Expr<?> other, Ref<?> inner, List<Col<?>> keys, List<Expr<?>> outer) {
        if (!(maybeInner instanceof Col<?> col) || col.ref() != inner) return false;
        if (other instanceof Col<?> o && o.ref() == inner) return false;
        if (!(other instanceof Col<?>)) return false;
        keys.add(col);
        outer.add(other);
        return true;
    }

    /** 目标表的一个查询作用域，条件是连接条件加筛选条件。 */
    private From1<R> scope() { return Sql.from(target).where(r -> filter == null ? on.apply(r) : Sql.all(on.apply(r), filter.apply(r))); }

    /** 排名子查询里的行号列。 */
    private static final class RowNumber extends Expr<Long> {
        /** 排名子查询的那次出现。 */
        private final Ref<?> ranked;

        RowNumber(Ref<?> ranked) {
            super(SqlType.of(Long.class));
            this.ranked = ranked;
        }

        /** 写出 {@code 别名.rn__}。 */
        @Override public void render(SqlWriter w) { w.alias(ranked.alias(w)).sql(".").identifier(ROW_NUMBER); }
    }
}

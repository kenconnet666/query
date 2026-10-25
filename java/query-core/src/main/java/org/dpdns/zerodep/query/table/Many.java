package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Aggregate;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.ExprNode;
import org.dpdns.zerodep.query.expr.Family;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.OrderItem;
import org.dpdns.zerodep.query.expr.OrderList;
import org.dpdns.zerodep.query.expr.typed.ExprBigint;
import org.dpdns.zerodep.query.expr.typed.ExprDecimal;
import org.dpdns.zerodep.query.expr.typed.ExprNumber;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.scope.From1;
import org.dpdns.zerodep.query.select.Children;
import org.dpdns.zerodep.query.select.FilterObject;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.SelectSpec;
import org.dpdns.zerodep.query.select.Selection;
import org.dpdns.zerodep.query.table.column.Col;
import org.dpdns.zerodep.query.type.SqlType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 一对多关系：目标表里满足连接条件的那些行，来自表接口的 {@code many(...)}。它不连接进主查询（那会让主查询的行重复），
 * 只能通过集合运算使用：
 * <ul>
 *   <li>{@link #any()}、{@link #none()}：写进当前的条件块，渲染为 EXISTS / NOT EXISTS；</li>
 *   <li>{@link #count()}、{@link #countDistinct}、{@link #sum}、{@link #max}、{@link #min}、{@link #avg}、{@link #stringAgg}：渲染为相关标量子查询；
 *       {@code sum}、{@code max}、{@code min} 的值由 lambda 给出，Java 推不出它的具体类型（见各方法），要用某种类型的方法时包一层，
 *       例如 {@code ExprDecimal.of(m.deliveries().sum(l -> l.quantity())).times(price)}；</li>
 *   <li>{@link #first}：每组按顺序取第一行，支持 LATERAL 的库写成按父行取一行的 LATERAL 子查询，其余写成 ROW_NUMBER 派生表再 LEFT JOIN 回来，返回那一行的表接口；</li>
 *   <li>{@link #list}：每个父行一个子行列表，用一条按键批量的查询加载。</li>
 * </ul>
 * {@link #where} 先筛选目标行，例如 {@code h.items().where(i -> i.blocked().eq(true)).count()}，也可以给条件对象；
 * {@link #orderBy} 决定 list 与 stringAgg 的顺序，{@link #limit} 限定 list 每个父行的子行数。
 * 查询上 {@code groupJoin(m -> m.deliveries())} 时，同一关系上的 count、sum、any 等合成一个按连接键分组的派生表 LEFT JOIN 一次。
 *
 * <p>多对多经过中间表：到中间表的一对多再 {@link #through} 中间表的多对一关系，得到目标表的 Many，例如
 * {@code default Many<Role> roles() { return userRoles().through(UserRole::role); }}。这时查询的是中间表，
 * 交给 lambda 的是经过导航的目标行，导航在子查询里写成 LEFT JOIN。
 *
 * @param <R> 目标表接口
 */
public final class Many<R extends Entity> {
    /** 关系所属的那次表出现。 */
    private final Ref<?> owner;

    /** 查询的表：一对多是目标表，多对多是中间表。 */
    private final Class<? extends Entity> table;

    /** 连接条件块：table 的行与所属行如何对应。 */
    private final Consumer<Object> on;

    /** table 的行到交给 lambda 的行：一对多原样，多对多经过中间表的导航。 */
    private final Function<Object, R> view;

    /** 追加的筛选条件块，写在目标行上；没有时为 null。 */
    private final Consumer<R> filter;

    /** {@link #list} 的子行顺序，没有时为 null。 */
    private final Function<R, ? extends OrderItem> order;

    /** {@link #list} 给每个父行最多取的子行数，没有时为 null。 */
    private final Integer limit;

    /** 由表接口的 {@code many(...)} 创建。 */
    @SuppressWarnings("unchecked")
    Many(Ref<?> owner, Class<R> target, Consumer<R> on, Consumer<R> filter) {
        this(owner, target, row -> on.accept((R) row), row -> (R) row, filter, null, null);
    }

    /** 全部字段的构造，where、orderBy、limit 与 through 由它派生新的 Many。 */
    private Many(Ref<?> owner, Class<? extends Entity> table, Consumer<Object> on, Function<Object, R> view, Consumer<R> filter,
                 Function<R, ? extends OrderItem> order, Integer limit) {
        this.owner = owner;
        this.table = table;
        this.on = on;
        this.view = view;
        this.filter = filter;
        this.order = order;
        this.limit = limit;
    }

    /** 换掉筛选、排序、条数的副本，表、连接条件和导航不变。 */
    private Many<R> with(Consumer<R> filter, Function<R, ? extends OrderItem> order, Integer limit) {
        return new Many<>(owner, table, on, view, filter, order, limit);
    }

    /** 只看满足条件的目标行，返回新的 Many；条件一行写一个。 */
    public Many<R> where(Consumer<R> condition) {
        Consumer<R> before = filter;
        return with(before == null ? condition : r -> {
            before.accept(r);
            condition.accept(r);
        }, order, limit);
    }

    /**
     * 按条件对象（同查询的 {@code where(filter)}，见 {@link org.dpdns.zerodep.query.annotation.Where}）只看部分目标行：
     * 前端的筛选条件于是能筛子集合，例如 {@code m.deliveries().where(lineFilter).list(...)}。字段的路径从目标表开始。
     */
    public Many<R> where(Object filter) {
        return where(r -> Conditions.add(FilterObject.condition(SelectSpec.from(TableProxy.refOf(r)), filter)));
    }

    /**
     * 多对多：本关系（到中间表的一对多）再沿中间表的多对一关系 hop 走到目标表，得到目标表的 Many：
     * {@code userRoles().through(UserRole::role)}。行数是中间表的行数；where、orderBy、limit 写在 through 之后，lambda 拿到的是目标行。
     */
    public <T extends Entity> Many<T> through(Function<R, T> hop) {
        if (filter != null || order != null || limit != null) {
            throw new IllegalStateException("through(...) goes right after the relation to the junction table; call where, orderBy and limit after it");
        }
        Function<Object, R> before = view;
        return new Many<>(owner, table, on, row -> hop.apply(before.apply(row)), null, null, null);
    }

    /** 至少有一行：{@code EXISTS (...)}，写进当前的条件块；groupJoin 时是 {@code COALESCE(分组列, 0) > 0}。 */
    public void any() { Conditions.add(new Test<>(this, Nodes.exists(scope().selectOne(), false), false)); }

    /** 至少有一行满足条件。 */
    public void any(Consumer<R> condition) { where(condition).any(); }

    /** 一行也没有：{@code NOT EXISTS (...)}，写进当前的条件块；groupJoin 时是 {@code COALESCE(分组列, 0) = 0}。 */
    public void none() { Conditions.add(new Test<>(this, Nodes.exists(scope().selectOne(), true), true)); }

    /** 没有一行满足条件。 */
    public void none(Consumer<R> condition) { where(condition).none(); }

    /** 行数，相关子查询 {@code (SELECT COUNT(*) ...)}；多对多时是中间表的行数。 */
    public ExprBigint<Long> count() { return ExprBigint.of(value(r -> Sql.count(), true)); }

    /** 某个值去重后的个数，相关子查询 {@code (SELECT COUNT(DISTINCT x) ...)}。 */
    public ExprBigint<Long> countDistinct(Function<R, ? extends Expr<?>> value) { return ExprBigint.of(value(r -> value.apply(r).countDistinct(), true)); }

    /**
     * 某个值的合计；没有行时为 NULL。结果的静态类型是 {@code ExprNumber<N>} 而不是值的具体类型：值来自隐式类型的 lambda，
     * 返回类型若是类型变量，{@code x.gt(m.items().sum(i -> i.amount()))} 会在 {@code gt(T)} 与 {@code gt(Expr<T>)} 之间引用不明确。
     */
    @SuppressWarnings("unchecked")
    public <N extends Number> ExprNumber<N> sum(Function<R, ? extends ExprNumber<N>> value) {
        return (ExprNumber<N>) typed(value, e -> Nodes.aggregate(e, "SUM"));
    }

    /** 某个值的最大值，例如 {@code d.version().eq(d.versions().max(x -> x.version()))}；没有行时为 NULL。静态类型为何是 {@code Expr<T>} 见 {@link #sum}。 */
    public <T> Expr<T> max(Function<R, ? extends Expr<T>> value) { return typed(value, e -> Nodes.aggregate(e, "MAX")); }

    /** 某个值的最小值；没有行时为 NULL。静态类型为何是 {@code Expr<T>} 见 {@link #sum}。 */
    public <T> Expr<T> min(Function<R, ? extends Expr<T>> value) { return typed(value, e -> Nodes.aggregate(e, "MIN")); }

    /** 某个值的平均值，结果是小数；没有行时为 NULL。 */
    public ExprDecimal<BigDecimal> avg(Function<R, ? extends ExprNumber<?>> value) { return ExprDecimal.of(value(r -> value.apply(r).avg(), false)); }

    /**
     * 把目标行的文本用 separator 拼成一个值，按 {@link #orderBy} 给的顺序：
     * {@code m.deliveries().orderBy(l -> l.delivery()).stringAgg(l -> l.delivery(), ",")}；没有行时为 NULL。
     */
    public ExprText<String> stringAgg(Function<R, ? extends ExprText<?>> value, String separator) {
        return ExprText.of(Sql.scalar(scope().select(row -> {
            R r = view.apply(row);
            return order == null ? value.apply(r).stringAgg(separator) : value.apply(r).stringAgg(separator, order.apply(r));
        })));
    }

    /**
     * 每组按 order 排序后的第一行，例如最新的一条记录：{@code m.purchases().first(p -> p.createdOn().desc()).vendor()}。
     * 连接条件里"目标列 = 所属表的列"的部分作为分组键，其余条件只筛选目标行；没有匹配行时各列为 NULL。渲染时按方言选写法（0018）：
     * 支持 LATERAL 的库（PostgreSQL、MySQL）写成 {@code LEFT JOIN LATERAL (SELECT 目标表各列 … WHERE 键 = 父行的列 ORDER BY … LIMIT 1) ON TRUE}，
     * 每个父行按索引取一行，给 (键, 排序列) 建索引；其余（HANA、SQLite、PolarDB）写成
     * {@code LEFT JOIN (SELECT 目标表各列, ROW_NUMBER() OVER (PARTITION BY 键 ORDER BY ...) ...) ON 键相等 AND 行号 = 1}。
     * 多对多时在中间表上取，再沿导航取目标行。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public R first(Function<R, ? extends OrderItem> order) {
        Split split = split("first()");
        Expr<?>[] partition = split.keys().toArray(Expr<?>[]::new);
        Object row = split.row();
        OrderItem sort = Conditions.outside(() -> order.apply(view.apply(row)));
        Query<?> ranked = Query.select(SelectSpec.from(split.inner()).where(Nodes.all(split.filters())),
            Sql.cols(((Entity) row).columns(), Sql.rowNumber().over(w -> w.partitionBy(partition).orderBy(sort)).as(ROW_NUMBER)));
        // LATERAL 的写法在查询的表的另一次出现上：键等于父行的列，排好序取一行
        Split each = split("first()");
        OrderItem eachSort = Conditions.outside(() -> order.apply(view.apply(each.row())));
        List<Cond> correlated = new ArrayList<>(each.filters());
        for (int i = 0; i < each.keys().size(); i++) correlated.add(Nodes.compare(each.keys().get(i), "=", each.outer().get(i)));
        Query<?> lateral = Query.select(SelectSpec.from(each.inner()).where(Nodes.all(correlated)).orderBy(OrderList.of(eachSort)).limit(1),
            Sql.cols(((Entity) each.row()).columns()));
        Ref<?> first = Ref.of(new FirstRow(ranked, lateral, table, table.getSimpleName().toLowerCase(java.util.Locale.ROOT) + "_first"));
        List<Cond> join = new ArrayList<>();
        for (int i = 0; i < partition.length; i++) join.add(Nodes.compare(first.column(split.keys().get(i).property().name()), "=", split.outer().get(i)));
        join.add(Nodes.compare(new RowNumber(first), "=", Nodes.literal(Long.class, 1L)));
        Cond rankedJoin = Nodes.all(join);
        owner.relate(first, Nodes.of(w -> {
            if (w.dialect().supportsLateral()) w.sql("TRUE");
            else w.render(rankedJoin);
        }));
        return view.apply(first.param());
    }

    /**
     * 子行按 order 排序，用于 {@link #list}：{@code h.items().orderBy(i -> i.position()).list(...)}。
     * 对 any、count 等集合运算没有影响。
     */
    public Many<R> orderBy(Function<R, ? extends OrderItem> order) { return with(filter, order, limit); }

    /**
     * {@link #list} 给每个父行最多取 rows 个子行，按 {@link #orderBy} 的顺序取前面的，例如每个物料最近的 3 张交货单：
     * {@code m.deliveries().orderBy(l -> l.createdOn().desc()).limit(3).list(...)}。批量查询用 ROW_NUMBER 按父键分区过滤。
     */
    public Many<R> limit(int rows) {
        if (rows < 1) throw new IllegalArgumentException("limit needs at least one row");
        if (order == null) throw new IllegalStateException("limit(...) keeps the first rows by orderBy(...); call orderBy first");
        return with(filter, order, rows);
    }

    /**
     * 每个父行的子行列表，放进 {@code row(...)} 的参数，例如
     * {@code row(MaterialTree::new, m.material(), m.deliveries().list(l -> row(Line::new, l.delivery(), l.quantity())))}。
     * 父查询只多取连接键；执行时用一条按键批量的 IN 查询取出全部子行，所以 N 个父行只多一条 SQL。
     * 连接条件里"目标列 = 所属表的列"的部分作为键，其余条件只筛选子行；多对多时键是中间表的列。
     */
    public <C> Children<C> list(Function<R, ? extends Selection<C>> projection) {
        Split split = split("list()");
        R row = view.apply(split.row());
        SelectSpec spec = SelectSpec.from(split.inner()).where(Nodes.all(split.filters()));
        if (order != null) spec = spec.orderBy(OrderList.of(Conditions.outside(() -> order.apply(row))));
        if (limit != null) {
            Expr<?>[] partition = split.keys().toArray(Expr<?>[]::new);
            OrderItem sort = Conditions.outside(() -> order.apply(row));
            spec = spec.qualify(Conditions.collect(() -> Sql.rowNumber().over(w -> w.partitionBy(partition).orderBy(sort)).le((long) limit)));
        }
        Selection<C> selection = Conditions.outside(() -> projection.apply(row));
        return new Children<>(spec, selection, split.keys(), split.outer());
    }

    // ---------- groupJoin ----------

    /** 所属的那次表出现；groupJoin 把派生表连接在它后面。 */
    public Ref<?> owner() { return owner; }

    /** 两个 Many 是否是同一个关系：同一次表出现上的同一个 many(...)，where 筛选可以不同。 */
    public boolean sameRelation(Many<?> other) { return owner == other.owner && on == other.on; }

    /**
     * groupJoin 的派生表：按连接键分组，键的列名是 k1、k2……，operations 每个一列，列名是 c1、c2……，
     * 每个运算自己的 where 写进它的聚合（CASE WHEN）。outer 是所属表里依次与 k1、k2…… 相等的列。
     */
    public Grouping grouping(List<? extends Grouped> operations) {
        Split split = with(null, null, null).split("groupJoin(...)");
        List<Expr<?>> columns = new ArrayList<>();
        for (int i = 0; i < split.keys().size(); i++) columns.add(split.keys().get(i).as("k" + (i + 1)));
        for (int i = 0; i < operations.size(); i++) columns.add(operations.get(i).aggregate(split.row()).as("c" + (i + 1)));
        SelectSpec spec = SelectSpec.from(split.inner()).where(Nodes.all(split.filters())).groupBy(split.keys());
        return new Grouping(Query.select(spec, Sql.cols(columns.toArray(Expr<?>[]::new))), split.outer());
    }

    /** {@link #grouping} 的结果：派生表的查询，以及所属表里与各键相等的列。 */
    public record Grouping(Query<?> query, List<Expr<?>> outer) { }

    /** 一对多关系上的一个集合运算；查询 groupJoin 这个关系时，渲染器把它换成分组派生表的一列。 */
    public interface Grouped extends Renderable {
        /** 运算所在的关系。 */
        Many<?> relation();

        /** 在派生表里计算它的聚合，row 是派生表那次出现（查询的表）的表接口实例；关系上的 where 写成聚合的过滤条件。 */
        Aggregate<?> aggregate(Object row);

        /** 换成派生表的列 column 之后的写法，例如行数写成 {@code COALESCE(列, 0)}。 */
        void renderGrouped(SqlWriter w, Renderable column);
    }

    /** 包装成可替换的聚合值：aggregate 在目标行上算出聚合（count、sum 等返回的表达式）。 */
    private <T> Expr<T> value(Function<R, ? extends Expr<T>> aggregate, boolean zeroIfNone) {
        Expr<T> correlated = Sql.scalar(scope().select(row -> aggregate.apply(view.apply(row))));
        return new Value<>(this, correlated, aggregate, zeroIfNone);
    }

    /** 一个值上的聚合；值有类型接口时结果在运行时也包成同一类型（{@code ExprDecimal.of(...)} 包装时不会套两层）。 */
    private <T> Expr<T> typed(Function<R, ? extends Expr<T>> value, Function<Expr<T>, Expr<T>> aggregate) {
        List<Expr<T>> seen = new ArrayList<>(1);
        Expr<T> result = value(r -> {
            Expr<T> v = value.apply(r);
            if (seen.isEmpty()) seen.add(v);
            return aggregate.apply(v);
        }, false);
        return seen.get(0) instanceof Family<T, ?> family ? family.retype(result) : result;
    }

    /** 带上这个 Many 的筛选条件的聚合；r 是目标行。 */
    private Aggregate<?> filtered(R r, Aggregate<?> aggregate) {
        return filter == null ? aggregate : aggregate.filter(Conditions.collect(() -> filter.accept(r)));
    }

    /** 一对多上的聚合值：默认渲染为相关子查询；groupJoin 时换成分组派生表的列，行数类在没有行时为 0。 */
    private static final class Value<R extends Entity, T> extends ExprNode<T> implements Grouped {
        private final Many<R> many;
        private final Expr<T> correlated;
        private final Function<R, ? extends Expr<?>> aggregate;
        private final boolean zeroIfNone;

        Value(Many<R> many, Expr<T> correlated, Function<R, ? extends Expr<?>> aggregate, boolean zeroIfNone) {
            super(correlated.type());
            this.many = many;
            this.correlated = correlated;
            this.aggregate = aggregate;
            this.zeroIfNone = zeroIfNone;
        }

        @Override public void render(SqlWriter w) { w.render(correlated); }

        @Override public String nameHint() { return correlated.nameHint(); }

        @Override public Many<?> relation() { return many; }

        @Override public Aggregate<?> aggregate(Object row) {
            R r = many.view.apply(row);
            return many.filtered(r, (Aggregate<?>) Nodes.unwrap(aggregate.apply(r)));
        }

        @Override public void renderGrouped(SqlWriter w, Renderable column) {
            if (zeroIfNone) w.sql("COALESCE(").render(column).sql(", 0)");
            else w.render(column);
        }
    }

    /** 一对多上的存在判断：默认渲染为 EXISTS / NOT EXISTS；groupJoin 时比较分组派生表里的行数。 */
    private static final class Test<R extends Entity> extends Cond implements Grouped {
        private final Many<R> many;
        private final Cond correlated;
        private final boolean none;

        Test(Many<R> many, Cond correlated, boolean none) {
            this.many = many;
            this.correlated = correlated;
            this.none = none;
        }

        @Override public void render(SqlWriter w) { w.render(correlated); }

        @Override public Many<?> relation() { return many; }

        @Override public Aggregate<?> aggregate(Object row) { return many.filtered(many.view.apply(row), (Aggregate<?>) Nodes.unwrap(Sql.count())); }

        @Override public void renderGrouped(SqlWriter w, Renderable column) {
            w.sql("COALESCE(").render(column).sql(none ? ", 0) = 0" : ", 0) > 0");
        }
    }

    /** 行号列在排名子查询里的名字。 */
    private static final String ROW_NUMBER = "rn__";

    /**
     * 连接条件拆成三部分：键（查询的表的列）、所属行里与键相等的列、其余只筛选的条件。
     *
     * @param inner 查询的表新的一次出现
     * @param row   inner 的表接口实例（多对多时是中间表的行）
     */
    private record Split(Ref<?> inner, Object row, List<Col<?>> keys, List<Expr<?>> outer, List<Cond> filters) { }

    /** 在查询的表新的一次出现上展开连接条件和筛选条件并拆分；没有"查询的表的列 = 所属表的列"时报错，use 是方法名。 */
    private Split split(String use) {
        Ref<?> inner = Ref.table(table);
        Object row = inner.param();
        Cond condition = Conditions.collect(() -> conditions(row));
        List<Col<?>> keys = new ArrayList<>();
        List<Expr<?>> outer = new ArrayList<>();
        List<Cond> filters = new ArrayList<>();
        for (Cond c : Nodes.conjuncts(condition)) {
            if (c instanceof Nodes.Comparison cmp && cmp.operator.equals("=") && link(cmp.left, cmp.right, inner, keys, outer)) continue;
            if (c instanceof Nodes.Comparison cmp && cmp.operator.equals("=") && link(cmp.right, cmp.left, inner, keys, outer)) continue;
            filters.add(c);
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(use + " needs an equality between a column of " + table.getSimpleName()
                + " and a column of the table the relation belongs to, e.g. i -> i.delivery().eq(delivery())");
        }
        return new Split(inner, row, keys, outer, filters);
    }

    /** maybeInner 是查询的表的列、other 不是时，记为一对键，返回 true。 */
    private static boolean link(Expr<?> maybeInner, Expr<?> other, Ref<?> inner, List<Col<?>> keys, List<Expr<?>> outer) {
        if (!(Nodes.unwrap(maybeInner) instanceof Col<?> col) || col.ref() != inner) return false;
        if (!(Nodes.unwrap(other) instanceof Col<?> o) || o.ref() == inner) return false;
        keys.add(col);
        outer.add(other);
        return true;
    }

    /** 在查询的表的一行上写下连接条件，在它对应的目标行上写下筛选条件。 */
    private void conditions(Object row) {
        on.accept(row);
        if (filter != null) filter.accept(view.apply(row));
    }

    /** 查询的表的一个查询作用域，条件是连接条件加筛选条件。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private From1<Object> scope() { return (From1) Sql.from((Class) table).where(r -> conditions(r)); }

    /** 排名子查询里的行号列。 */
    private static final class RowNumber extends ExprNode<Long> {
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

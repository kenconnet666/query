package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.JoinWrite;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.select.ImplicitJoins;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.Sharding;
import org.dpdns.zerodep.query.table.SourceFilters;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 联表的 UPDATE：SET 和 WHERE 可以引用连接的表，由方言门面的 {@code updateJoin} 开始：
 * <pre>{@code
 * db.execute(Postgres.updateJoin(Card.class, User.class, (c, u) -> u.id().eq(c.userId()))
 *     .set((c, u) -> c.owner().to(u.name()))
 *     .where((c, u) -> u.status().eq("ACTIVE")));
 * }</pre>
 * PostgreSQL 写成 {@code UPDATE ... FROM}，MySQL 写成 {@code UPDATE ... JOIN ... SET}，HANA 写成按主键对上行的 MERGE（被改表要有 {@code @Id}）。
 * 连接是内连接；每个被改行至多匹配一个连接行，否则 PostgreSQL、MySQL 取其中一行（值不确定），HANA 报错。
 * 没有 where 条件时报错（连接条件不算），全表要写 {@code .all()}；两张表的全局过滤器和逻辑删除条件都照常加上。
 * 不能用导航关系，要用的表直接连接。不可变，每个方法返回新的语句。
 *
 * @param <T> 被改的表接口
 * @param <S> 连接的表接口
 */
public final class JoinUpdate<T extends Entity, S extends Entity> implements Write {
    /** 被改的表、where 条件和开关。 */
    private final Target<T> target;

    /** 连接的表的这次出现。 */
    private final Ref<S> source;

    /** 连接条件。 */
    private final Cond on;

    /** SET 项，按调用顺序。 */
    private final List<Assignment> sets;

    /** 由 {@link #of} 和各修改方法调用。 */
    private JoinUpdate(Target<T> target, Ref<S> source, Cond on, List<Assignment> sets) {
        this.target = target;
        this.source = source;
        this.on = on;
        this.sets = sets;
    }

    /** 更新 table，连接 joined；由方言门面的 {@code updateJoin} 调用。 */
    public static <T extends Entity, S extends Entity> JoinUpdate<T, S> of(Class<T> table, Class<S> joined, BiFunction<T, S, Cond> on) {
        Target<T> target = Target.of(table);
        Ref<S> source = Ref.table(joined);
        return new JoinUpdate<>(target, source, joinCondition(target, source, on), List.of());
    }

    /** 追加一个 SET 项，值可以引用连接的表：{@code set((c, u) -> c.owner().to(u.name()))}。 */
    public JoinUpdate<T, S> set(BiFunction<T, S, Assignment> assignment) {
        Assignment a = assignment.apply(target.row(), source.param());
        if (a == null || a.skipped()) throw new IllegalArgumentException("set(...) returned null; write col.to(value)");
        if (a.column().ref() != target.ref()) throw new IllegalArgumentException("set(...) must assign a column of the updated table");
        if (Metadata.entity(target.ref().entity()).writes().readOnly().contains(a.column().property().name())) {
            throw new IllegalArgumentException(target.ref().entity().getSimpleName() + "." + a.column().property().name() + "() is @ReadOnly");
        }
        List<Assignment> all = new ArrayList<>(sets);
        all.add(a);
        return new JoinUpdate<>(target, source, on, List.copyOf(all));
    }

    /** 追加 WHERE 条件，可以引用两张表；多次调用之间是 AND。 */
    public JoinUpdate<T, S> where(BiFunction<T, S, Cond> condition) {
        return new JoinUpdate<>(target.withWhere(nonNull(condition.apply(target.row(), source.param()))), source, on, sets);
    }

    /** 两张表都不套用全局过滤器，也不加逻辑删除的条件。 */
    public JoinUpdate<T, S> unfiltered() { return new JoinUpdate<>(target.filtering(target.filtering().off()), source, on, sets); }

    /** 两张表都不套用这些名字的全局过滤器。 */
    public JoinUpdate<T, S> unfiltered(String filter, String... more) {
        return new JoinUpdate<>(target.filtering(target.filtering().without(filter, more)), source, on, sets);
    }

    /** 两张表都不加逻辑删除的条件。 */
    public JoinUpdate<T, S> withDeleted() { return new JoinUpdate<>(target.filtering(target.filtering().withDeleted()), source, on, sets); }

    /** 不套用数据库实例的写入填充（审计列）。 */
    public JoinUpdate<T, S> withoutFills() { return new JoinUpdate<>(target.withoutFills(), source, on, sets); }

    /** 允许没有 where 条件，即改动所有连接得上的行。 */
    public JoinUpdate<T, S> all() { return new JoinUpdate<>(target.all(), source, on, sets); }

    /** 期望影响 rows 行，不符时 {@code db.execute} 抛出 {@code UnexpectedRowCountException}。 */
    public JoinUpdate<T, S> expect(long rows) { return new JoinUpdate<>(target.expect(rows), source, on, sets); }

    /** {@code expect(n)} 写明的行数。 */
    @Override public Long expected() { return target.expected(); }

    /** 一条联表 UPDATE，由方言写出；没有 SET 项时报错。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        if (sets.isEmpty()) throw new IllegalStateException("updateJoin(...) has nothing to SET; add set(...)");
        SqlWriter w = writers.get();
        w.dialect().joinUpdate(w, parts(w, target, source, on, sets, "updateJoin(...)"));
        return List.of(w.statement());
    }

    /**
     * 交给方言的各部分：被改表的过滤条件和 where 合在一起，连接表的过滤条件并进连接条件，SET 里再加上更新时的填充（覆盖同名的项）。
     * 没有 where 条件又没有 all() 时报错；条件或值用到导航关系时报错。statement 用于报错。
     */
    static JoinWrite parts(SqlWriter w, Target<?> target, Ref<?> source, Cond on, List<Assignment> assignments, String statement) {
        Sharding sharding = Sharding.of(w);
        if (sharding.rule(target.ref().entity()) != null || sharding.rule(source.entity()) != null) {
            throw new UnsupportedOperationException(statement + " cannot use a sharded table; update or delete each shard with update(...) or deleteFrom(...)");
        }
        Cond own = Nodes.all(target.where());
        if (own.isEmpty() && !target.everyRow()) {
            throw new IllegalStateException(statement + " without where(...) would touch every joined row of " + target.ref().entity().getSimpleName()
                + "; add .all() if that is intended");
        }
        List<Assignment> sets = new ArrayList<>(assignments);
        for (Assignment fill : target.fillsOf(w).onUpdate(target.ref())) {
            sets.removeIf(a -> a.column().property().name().equals(fill.column().property().name()));
            sets.add(fill);
        }
        List<Renderable> used = new ArrayList<>(sets.stream().map(Assignment::value).toList());
        used.add(own);
        used.add(on);
        if (!ImplicitJoins.used(w, List.of(target.ref(), source), used).isEmpty()) {
            throw new IllegalArgumentException(statement + " cannot follow relations; join the table you need instead");
        }
        SourceFilters filters = target.filters(w);
        List<Cond> joined = new ArrayList<>();
        joined.add(on);
        joined.addAll(filters.conditionsFor(source));
        List<Cond> mine = new ArrayList<>(filters.conditionsFor(target.ref()));
        mine.add(own);
        Cond where = Nodes.all(mine);
        EntityMeta<?> meta = Metadata.entity(target.ref().entity());
        Ref<?> ref = target.ref();
        List<Renderable> keys = new ArrayList<>();
        List<Renderable> keyColumns = new ArrayList<>();
        for (String id : meta.writes().ids()) {
            Col<?> key = new Col<>(ref, meta.property(id));
            keys.add(key::renderName);
            keyColumns.add(key);
        }
        return new JoinWrite(ref::renderSource, x -> x.alias(ref.alias(x)), x -> x.table(meta.schema(), meta.table()), source::renderSource,
            Nodes.all(joined), where.isEmpty() ? null : where,
            sets.stream().map(a -> (Renderable) a.column()::renderName).toList(), sets.stream().map(a -> (Renderable) a.column()).toList(),
            sets.stream().map(a -> (Renderable) a.value()).toList(), keys, keyColumns);
    }

    /** 用两张表的表接口算出连接条件。 */
    static <T extends Entity, S extends Entity> Cond joinCondition(Target<T> target, Ref<S> source, BiFunction<T, S, Cond> on) {
        Cond c = nonNull(on.apply(target.row(), source.param()));
        if (c.isEmpty()) throw new IllegalArgumentException("A join needs a condition");
        return c;
    }

    /** 条件不能是 null。 */
    static Cond nonNull(Cond c) {
        if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
        return c;
    }
}

package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 联表的 DELETE：WHERE 可以引用连接的表，由方言门面的 {@code deleteJoin} 开始：
 * <pre>{@code
 * db.execute(Mysql.deleteJoin(Card.class, User.class, (c, u) -> u.id().eq(c.userId())).where((c, u) -> u.status().eq("LEFT")));
 * }</pre>
 * PostgreSQL 写成 {@code DELETE ... USING}，MySQL 写成 {@code DELETE 别名 FROM ... JOIN}，HANA 写成 {@code WHERE EXISTS} 子查询。
 * 被改表有 {@code @LogicDelete} 列时改写成联表 UPDATE，{@code .physically()} 才真正删除。其余规则同 {@link JoinUpdate}。
 *
 * @param <T> 被删的表接口
 * @param <S> 连接的表接口
 */
public final class JoinDelete<T extends Entity, S extends Entity> implements Write {
    /** 被删的表、where 条件和开关。 */
    private final Target<T> target;

    /** 连接的表的这次出现。 */
    private final Ref<S> source;

    /** 连接条件。 */
    private final Cond on;

    /** 是否真正删除；false 时有 {@code @LogicDelete} 列的表改写成 UPDATE。 */
    private final boolean physical;

    /** 由 {@link #of} 和各修改方法调用。 */
    private JoinDelete(Target<T> target, Ref<S> source, Cond on, boolean physical) {
        this.target = target;
        this.source = source;
        this.on = on;
        this.physical = physical;
    }

    /** 从 table 删除，连接 joined；由方言门面的 {@code deleteJoin} 调用。 */
    public static <T extends Entity, S extends Entity> JoinDelete<T, S> of(Class<T> table, Class<S> joined, BiConsumer<T, S> on) {
        Target<T> target = Target.of(table);
        Ref<S> source = Ref.table(joined);
        return new JoinDelete<>(target, source, JoinUpdate.joinCondition(target, source, on), false);
    }

    /** 追加 WHERE 条件，可以引用两张表；多次调用之间是 AND。 */
    public JoinDelete<T, S> where(BiConsumer<T, S> condition) {
        return new JoinDelete<>(target.withWhere(JoinUpdate.condition(target, source, condition)), source, on, physical);
    }

    /** 表上有 {@code @LogicDelete} 列时仍然真正删除。 */
    public JoinDelete<T, S> physically() { return new JoinDelete<>(target, source, on, true); }

    /** 两张表都不套用全局过滤器，也不加逻辑删除的条件。 */
    public JoinDelete<T, S> unfiltered() { return new JoinDelete<>(target.filtering(target.filtering().off()), source, on, physical); }

    /** 两张表都不套用这些名字的全局过滤器。 */
    public JoinDelete<T, S> unfiltered(String filter, String... more) {
        return new JoinDelete<>(target.filtering(target.filtering().without(filter, more)), source, on, physical);
    }

    /** 两张表都不加逻辑删除的条件。 */
    public JoinDelete<T, S> withDeleted() { return new JoinDelete<>(target.filtering(target.filtering().withDeleted()), source, on, physical); }

    /** 不套用数据库实例的写入填充；只影响逻辑删除改写成的 UPDATE。 */
    public JoinDelete<T, S> withoutFills() { return new JoinDelete<>(target.withoutFills(), source, on, physical); }

    /** 允许没有 where 条件，即删除所有连接得上的行。 */
    public JoinDelete<T, S> all() { return new JoinDelete<>(target.all(), source, on, physical); }

    /** 期望影响 rows 行，不符时 {@code db.execute} 抛出 {@code UnexpectedRowCountException}。 */
    public JoinDelete<T, S> expect(long rows) { return new JoinDelete<>(target.expect(rows), source, on, physical); }

    /** {@code expect(n)} 写明的行数。 */
    @Override public Long expected() { return target.expected(); }

    /** 一条联表 DELETE，或逻辑删除改写成的联表 UPDATE，由方言写出。 */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Rendered> render(Supplier<SqlWriter> writers) {
        SqlWriter w = writers.get();
        EntityMeta<?> meta = Metadata.entity(target.ref().entity());
        WriteMeta.LogicDeleteMeta logic = meta.writes().logicDelete();
        if (logic == null || physical) {
            w.dialect().joinDelete(w, JoinUpdate.parts(w, target, source, on, List.of(), "deleteJoin(...)"));
        } else {
            w.dialect().joinUpdate(w, JoinUpdate.parts(w, target, source, on, List.of(Assignment.deleted(target.ref(), meta, logic)), "deleteJoin(...)"));
        }
        return List.of(w.statement());
    }
}

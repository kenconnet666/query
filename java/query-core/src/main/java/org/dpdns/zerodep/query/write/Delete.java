package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.column.Col;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * DELETE 语句，由 {@code deleteFrom(Lips.class)} 开始：{@code db.execute(deleteFrom(Lips.class).where(l -> l.delivery().eq("D1")))}。
 * 没有 where 条件时报错（清空全表要写 {@code .all()}）；全局过滤器的条件也加进 WHERE。不可变，每个方法返回新的语句。
 *
 * @param <T> 被删除行的表接口
 */
public final class Delete<T extends Entity> implements Write {
    /** 目标表、条件与检查。 */
    private final Target<T> target;

    /** {@link #byId} 的行；没有时为 null。 */
    private final ById byId;

    /** 是否真正删除；false 时有 {@code @LogicDelete} 列的表改写成 UPDATE。 */
    private final boolean physical;

    /** 由 {@link #of} 和各修改方法调用。 */
    private Delete(Target<T> target, ById byId, boolean physical) {
        this.target = target;
        this.byId = byId;
        this.physical = physical;
    }

    /** 从 table 删除；通常写作 {@code Sql.deleteFrom(Lips.class)}。 */
    public static <T extends Entity> Delete<T> of(Class<T> table) { return new Delete<>(Target.of(table), null, false); }

    /** 删除的表。 */
    public Class<T> table() { return target.ref().entity(); }

    /** 表上有 {@code @LogicDelete} 列时仍然真正删除，而不是把它设为已删除。 */
    public Delete<T> physically() { return new Delete<>(target, byId, true); }

    /**
     * 按主键删除这些行：每行一条 {@code DELETE ... WHERE 主键 = ?}，表上有 {@code @Version} 时加上 {@code AND version = ?}；
     * 期望每行影响一行，否则 {@code db.execute} 抛出 {@code UnexpectedRowCountException}。行必须带主键属性（和版本属性）。
     */
    public Delete<T> byId(Object... rows) { return byId(Arrays.asList(rows)); }

    /** 同上，行在集合里。 */
    public Delete<T> byId(Collection<?> rows) {
        return new Delete<>(target, ById.of(Metadata.entity(target.ref().entity()), List.copyOf(rows), "deleteFrom(...).byId(...)"), physical);
    }

    /** 追加 WHERE 条件；多次调用之间是 AND。 */
    public Delete<T> where(Consumer<T> condition) { return new Delete<>(target.where(condition), byId, physical); }

    /** 条件对象：每个非空字段一个条件，规则同查询的 {@code where(filter)}。 */
    public Delete<T> where(Object filter) { return new Delete<>(target.where(filter), byId, physical); }

    /** 之后的 where 里，与空值比较的条件被省略；要写在 where 之前。 */
    public Delete<T> skipEmpty() { return new Delete<>(target.skipEmpty(), byId, physical); }

    /** 不套用数据库实例的全局过滤器，也不加逻辑删除的条件。 */
    public Delete<T> unfiltered() { return new Delete<>(target.filtering(target.filtering().off()), byId, physical); }

    /** 不套用这些名字的全局过滤器，其余照常。 */
    public Delete<T> unfiltered(String filter, String... more) { return new Delete<>(target.filtering(target.filtering().without(filter, more)), byId, physical); }

    /** 不加逻辑删除的条件，已删除的行也会被改到；全局过滤器照常。 */
    public Delete<T> withDeleted() { return new Delete<>(target.filtering(target.filtering().withDeleted()), byId, physical); }

    /** 不套用数据库实例的写入填充（审计列），例如迁移数据时保留原来的创建人和时间。 */
    public Delete<T> withoutFills() { return new Delete<>(target.withoutFills(), byId, physical); }

    /** 允许没有 where 条件，即删除全表。 */
    public Delete<T> all() { return new Delete<>(target.all(), byId, physical); }

    /** 期望影响 rows 行，不符时 {@code db.execute} 抛出 {@code UnexpectedRowCountException}。 */
    public Delete<T> expect(long rows) { return new Delete<>(target.expect(rows), byId, physical); }

    /** 加一个写在 DELETE（逻辑删除时是 UPDATE）后面的提示，规则同 {@code update(...).with(...)}。 */
    public Delete<T> with(Function<T, Clause> hint) { return new Delete<>(target.hint(hint), byId, physical); }

    /** {@code expect(n)} 写明的行数；没有写而用了 byId 时是行数。 */
    @Override public Long expected() { return target.expected() != null || byId == null ? target.expected() : byId.expected(); }

    /** {@code DELETE FROM 表 别名 WHERE ...}，byId 时每行一条；有逻辑删除列时改写成把它设为已删除的 UPDATE。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        SqlWriter probe = writers.get();
        if (byId == null) {
            List<String> shards = target.shards(probe);
            if (shards == null) return List.of(statement(probe, Cond.EMPTY));
            return shards.stream().map(t -> statement(target.on(writers.get(), t), Cond.EMPTY)).toList();
        }
        List<Rendered> out = new ArrayList<>();
        for (Object row : byId.rows()) {
            List<String> shards = target.shards(probe, byId, row);
            if (shards == null) out.add(statement(writers.get(), byId.locate(target.ref(), row)));
            else for (String t : shards) out.add(statement(target.on(writers.get(), t), byId.locate(target.ref(), row)));
        }
        return out;
    }

    /** 本语句写全部分表，不按条件选表（见 {@code Config.shard(...)}）。 */
    public Delete<T> allShards() { return new Delete<>(target.filtering(target.filtering().allShards()), byId, physical); }

    /** 本语句只写分表的这些物理表。 */
    public Delete<T> onShards(String table, String... more) { return new Delete<>(target.filtering(target.filtering().onShards(table, more)), byId, physical); }

    /** 写出一条 DELETE 或逻辑删除的 UPDATE；located 是 byId 定位一行的条件。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Rendered statement(SqlWriter w, Cond located) {
        EntityMeta<?> meta = Metadata.entity(target.ref().entity());
        WriteMeta.LogicDeleteMeta logic = meta.writes().logicDelete();
        if (logic == null || physical) {
            w.sql("DELETE ");
            target.renderHints(w);
            w.sql("FROM ");
            target.ref().renderSource(w);
            target.renderWhere(w, "deleteFrom(...)", located);
            return w.statement();
        }
        List<Assignment> sets = new ArrayList<>();
        sets.add(Assignment.deleted(target.ref(), meta, logic));
        String version = meta.writes().version();
        if (byId != null && version != null) sets.add(Assignment.increment(Col.of(target.ref(), meta.property(version))));
        return Update.set(w, target, sets, located, "deleteFrom(...)");
    }
}

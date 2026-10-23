package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.table.Entity;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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

    /** 由 {@link #of} 和各修改方法调用。 */
    private Delete(Target<T> target, ById byId) {
        this.target = target;
        this.byId = byId;
    }

    /** 从 table 删除；通常写作 {@code Sql.deleteFrom(Lips.class)}。 */
    public static <T extends Entity> Delete<T> of(Class<T> table) { return new Delete<>(Target.of(table), null); }

    /**
     * 按主键删除这些行：每行一条 {@code DELETE ... WHERE 主键 = ?}，表上有 {@code @Version} 时加上 {@code AND version = ?}；
     * 期望每行影响一行，否则 {@code db.execute} 抛出 {@code UnexpectedRowCountException}。行必须带主键属性（和版本属性）。
     */
    public Delete<T> byId(Object... rows) { return byId(Arrays.asList(rows)); }

    /** 同上，行在集合里。 */
    public Delete<T> byId(Collection<?> rows) {
        return new Delete<>(target, ById.of(Metadata.entity(target.ref().entity()), List.copyOf(rows), "deleteFrom(...).byId(...)"));
    }

    /** 追加 WHERE 条件；多次调用之间是 AND。 */
    public Delete<T> where(Function<T, Cond> condition) { return new Delete<>(target.where(condition), byId); }

    /** 条件对象：每个非空字段一个条件，规则同查询的 {@code where(filter)}。 */
    public Delete<T> where(Object filter) { return new Delete<>(target.where(filter), byId); }

    /** 之后的 where 里，与空值比较的条件被省略；要写在 where 之前。 */
    public Delete<T> skipEmpty() { return new Delete<>(target.skipEmpty(), byId); }

    /** 不套用数据库实例的全局过滤器。 */
    public Delete<T> unfiltered() { return new Delete<>(target.unfiltered(), byId); }

    /** 允许没有 where 条件，即删除全表。 */
    public Delete<T> all() { return new Delete<>(target.all(), byId); }

    /** 期望影响 rows 行，不符时 {@code db.execute} 抛出 {@code UnexpectedRowCountException}。 */
    public Delete<T> expect(long rows) { return new Delete<>(target.expect(rows), byId); }

    /** {@code expect(n)} 写明的行数；没有写而用了 byId 时是行数。 */
    @Override public Long expected() { return target.expected() != null || byId == null ? target.expected() : byId.expected(); }

    /** {@code DELETE FROM 表 别名 WHERE ...}，byId 时每行一条。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        if (byId == null) return List.of(statement(writers.get(), Cond.EMPTY));
        return byId.rows().stream().map(row -> statement(writers.get(), byId.locate(target.ref(), row))).toList();
    }

    /** 写出一条 DELETE；located 是 byId 定位一行的条件。 */
    private Rendered statement(SqlWriter w, Cond located) {
        w.sql("DELETE FROM ");
        target.ref().renderSource(w);
        target.renderWhere(w, "deleteFrom(...)", located);
        return w.statement();
    }
}

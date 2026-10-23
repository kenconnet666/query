package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.table.Entity;

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

    /** 由 {@link #of} 和各修改方法调用。 */
    private Delete(Target<T> target) { this.target = target; }

    /** 从 table 删除；通常写作 {@code Sql.deleteFrom(Lips.class)}。 */
    public static <T extends Entity> Delete<T> of(Class<T> table) { return new Delete<>(Target.of(table)); }

    /** 追加 WHERE 条件；多次调用之间是 AND。 */
    public Delete<T> where(Function<T, Cond> condition) { return new Delete<>(target.where(condition)); }

    /** 条件对象：每个非空字段一个条件，规则同查询的 {@code where(filter)}。 */
    public Delete<T> where(Object filter) { return new Delete<>(target.where(filter)); }

    /** 之后的 where 里，与空值比较的条件被省略；要写在 where 之前。 */
    public Delete<T> skipEmpty() { return new Delete<>(target.skipEmpty()); }

    /** 不套用数据库实例的全局过滤器。 */
    public Delete<T> unfiltered() { return new Delete<>(target.unfiltered()); }

    /** 允许没有 where 条件，即删除全表。 */
    public Delete<T> all() { return new Delete<>(target.all()); }

    /** 期望影响 rows 行，不符时 {@code db.execute} 抛出 {@code UnexpectedRowCountException}。 */
    public Delete<T> expect(long rows) { return new Delete<>(target.expect(rows)); }

    @Override public Long expected() { return target.expected(); }

    /** {@code DELETE FROM 表 别名 WHERE ...}。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        SqlWriter w = writers.get();
        w.sql("DELETE FROM ");
        target.ref().renderSource(w);
        target.renderWhere(w, "deleteFrom(...)");
        return List.of(w.statement());
    }
}

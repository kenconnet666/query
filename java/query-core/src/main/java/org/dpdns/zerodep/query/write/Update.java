package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.table.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * UPDATE 语句，由 {@code update(Lips.class)} 开始：
 * <pre>{@code
 * db.execute(update(Lips.class)
 *     .set(l -> l.unit().to("KG"))
 *     .set(l -> l.quantity().to(plus(l.quantity(), val(BigDecimal.ONE))))
 *     .where(l -> l.delivery().eq("D1")));
 * }</pre>
 * 没有 where 条件时报错（全表更新要写 {@code .all()}）；全局过滤器的条件也加进 WHERE。不可变，每个方法返回新的语句。
 *
 * @param <T> 被更新的表接口
 */
public final class Update<T extends Entity> implements Write {
    /** 目标表、条件与检查。 */
    private final Target<T> target;

    /** SET 项，按调用顺序；被 skipEmpty 省略的不在其中。 */
    private final List<Assignment> sets;

    /** 由 {@link #of} 和各修改方法调用。 */
    private Update(Target<T> target, List<Assignment> sets) {
        this.target = target;
        this.sets = sets;
    }

    /** 更新 table；通常写作 {@code Sql.update(Lips.class)}。 */
    public static <T extends Entity> Update<T> of(Class<T> table) { return new Update<>(Target.of(table), List.of()); }

    /** 追加一个 SET 项：{@code set(l -> l.unit().to("KG"))}；skipEmpty 下值为空的项被省略。 */
    public Update<T> set(Function<T, Assignment> assignment) {
        Assignment a = SkipEmpty.within(target.skipping(), () -> assignment.apply(target.row()));
        if (a == null) throw new IllegalArgumentException("set(...) returned null; write col.to(value)");
        if (a.skipped()) return this;
        if (a.column().ref() != target.ref()) throw new IllegalArgumentException("set(...) must assign a column of the updated table");
        List<Assignment> all = new ArrayList<>(sets);
        all.add(a);
        return new Update<>(target, List.copyOf(all));
    }

    /** 追加 WHERE 条件；多次调用之间是 AND。 */
    public Update<T> where(Function<T, Cond> condition) { return new Update<>(target.where(condition), sets); }

    /** 条件对象：每个非空字段一个条件，规则同查询的 {@code where(filter)}。 */
    public Update<T> where(Object filter) { return new Update<>(target.where(filter), sets); }

    /** 之后的 set 与 where 里，值为空的 SET 项和与空值比较的条件被省略；要写在它们之前。 */
    public Update<T> skipEmpty() { return new Update<>(target.skipEmpty(), sets); }

    /** 不套用数据库实例的全局过滤器。 */
    public Update<T> unfiltered() { return new Update<>(target.unfiltered(), sets); }

    /** 允许没有 where 条件，即更新全表。 */
    public Update<T> all() { return new Update<>(target.all(), sets); }

    /** 期望影响 rows 行，不符时 {@code db.execute} 抛出 {@code UnexpectedRowCountException}；用于"比较后更新"的状态流转。 */
    public Update<T> expect(long rows) { return new Update<>(target.expect(rows), sets); }

    @Override public Long expected() { return target.expected(); }

    /** {@code UPDATE 表 别名 SET 列 = 值, ... WHERE ...}；没有 SET 项时没有语句。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        if (sets.isEmpty()) return List.of();
        SqlWriter w = writers.get();
        w.sql("UPDATE ");
        target.ref().renderSource(w);
        w.sql("\nSET ");
        for (int i = 0; i < sets.size(); i++) {
            if (i > 0) w.sql(", ");
            sets.get(i).column().renderName(w);
            w.sql(" = ").render(sets.get(i).value());
        }
        target.renderWhere(w, "update(...)");
        return List.of(w.statement());
    }
}

package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.ImplicitJoins;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Filtering;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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

    /** {@link #byId} 的行；没有时为 null。 */
    private final ById byId;

    /** 由 {@link #of} 和各修改方法调用。 */
    private Update(Target<T> target, List<Assignment> sets, ById byId) {
        this.target = target;
        this.sets = sets;
        this.byId = byId;
    }

    /** 更新 table；通常写作 {@code Sql.update(Lips.class)}。 */
    public static <T extends Entity> Update<T> of(Class<T> table) { return new Update<>(Target.of(table), List.of(), null); }

    /** 更新的表。 */
    public Class<T> table() { return target.ref().entity(); }

    /**
     * 按主键更新这些行：每行一条 {@code UPDATE ... SET 其余属性 WHERE 主键 = ?}，表上有 {@code @Version} 时加上
     * {@code SET version = version + 1} 和 {@code AND version = ?}；期望每行影响一行，否则 {@code db.execute} 抛出
     * {@code UnexpectedRowCountException}（乐观锁冲突）。行是 record 或普通类，必须带主键属性（和版本属性）；
     * 开启 skipEmpty 时值为 null 的属性不更新。可以再加 set 和 where。
     */
    public Update<T> byId(Object... rows) { return byId(Arrays.asList(rows)); }

    /** 同上，行在集合里。 */
    public Update<T> byId(Collection<?> rows) {
        return new Update<>(target, sets, ById.of(Metadata.entity(target.ref().entity()), List.copyOf(rows), "update(...).byId(...)"));
    }

    /**
     * 按 {@code byId(...)} 的行逐一与修改前的行比较，只 SET 值变了的属性；一行完全没变时不发这一行的语句，也不计入期望的行数。
     * 修改前的行与 byId 的行一一对应、类相同，主键和版本必须相等。例如编辑表单提交时：
     * {@code update(Doc.class).byId(edited).diffFrom(loaded)}。
     */
    public Update<T> diffFrom(Object... originals) { return diffFrom(Arrays.asList(originals)); }

    /** 同上，修改前的行在集合里。 */
    public Update<T> diffFrom(Collection<?> originals) {
        if (byId == null) throw new IllegalStateException("diffFrom(...) compares against byId(...) rows; call byId(...) first");
        return new Update<>(target, sets, byId.diffFrom(List.copyOf(originals)));
    }

    /**
     * {@code byId(...)} 的行里会被写的那些，按原顺序：有 {@code diffFrom(...)} 时只含值变了的行，否则是全部行；
     * 没有 byId 时为空。执行成功后，表上有版本列时这些行在库里的版本都加了一。
     */
    public List<Object> changedRows() { return byId == null ? List.of() : List.copyOf(byId.changed()); }

    /** 追加一个 SET 项：{@code set(l -> l.unit().to("KG"))}；skipEmpty 下值为空的项被省略；{@code @ReadOnly} 的列不能写。 */
    public Update<T> set(Function<T, Assignment> assignment) {
        Assignment a = SkipEmpty.within(target.skipping(), () -> assignment.apply(target.row()));
        if (a == null) throw new IllegalArgumentException("set(...) returned null; write col.to(value)");
        if (a.skipped()) return this;
        if (a.column().ref() != target.ref()) throw new IllegalArgumentException("set(...) must assign a column of the updated table");
        if (Metadata.entity(target.ref().entity()).writes().readOnly().contains(a.column().property().name())) {
            throw new IllegalArgumentException(target.ref().entity().getSimpleName() + "." + a.column().property().name() + "() is @ReadOnly");
        }
        List<Assignment> all = new ArrayList<>(sets);
        all.add(a);
        return new Update<>(target, List.copyOf(all), byId);
    }

    /** 追加 WHERE 条件；多次调用之间是 AND。 */
    public Update<T> where(Function<T, Cond> condition) { return new Update<>(target.where(condition), sets, byId); }

    /** 条件对象：每个非空字段一个条件，规则同查询的 {@code where(filter)}。 */
    public Update<T> where(Object filter) { return new Update<>(target.where(filter), sets, byId); }

    /** 之后的 set 与 where 里，值为空的 SET 项和与空值比较的条件被省略；要写在它们之前。 */
    public Update<T> skipEmpty() { return new Update<>(target.skipEmpty(), sets, byId); }

    /** 不套用数据库实例的全局过滤器，也不加逻辑删除的条件。 */
    public Update<T> unfiltered() { return new Update<>(target.filtering(Filtering.OFF), sets, byId); }

    /** 不套用这些名字的全局过滤器，其余照常。 */
    public Update<T> unfiltered(String filter, String... more) { return new Update<>(target.filtering(target.filtering().without(filter, more)), sets, byId); }

    /** 不加逻辑删除的条件，已删除的行也会被改到；全局过滤器照常。 */
    public Update<T> withDeleted() { return new Update<>(target.filtering(target.filtering().withDeleted()), sets, byId); }

    /** 不套用数据库实例的写入填充（审计列），例如迁移数据时保留原来的创建人和时间。 */
    public Update<T> withoutFills() { return new Update<>(target.withoutFills(), sets, byId); }

    /** 允许没有 where 条件，即更新全表。 */
    public Update<T> all() { return new Update<>(target.all(), sets, byId); }

    /** 期望影响 rows 行，不符时 {@code db.execute} 抛出 {@code UnexpectedRowCountException}；用于"比较后更新"的状态流转。 */
    public Update<T> expect(long rows) { return new Update<>(target.expect(rows), sets, byId); }

    /** {@code expect(n)} 写明的行数；没有写而用了 byId 时是行数。 */
    @Override public Long expected() {
        if (target.expected() != null || byId == null) return target.expected();
        return sets.isEmpty() ? byId.expected() : (long) byId.rows().size();
    }

    /** {@code UPDATE 表 别名 SET 列 = 值, ... WHERE ...}，byId 时每行一条；没有 SET 项时没有语句。 */
    @Override public List<Rendered> render(Supplier<SqlWriter> writers) {
        if (byId == null) return sets.isEmpty() ? List.of() : List.of(statement(writers.get(), sets, Cond.EMPTY));
        List<Rendered> out = new ArrayList<>();
        for (int i = 0; i < byId.rows().size(); i++) {
            int index = i;
            List<Assignment> changed = SkipEmpty.within(target.skipping(), () -> byId.sets(target.ref(), index));
            if (changed.isEmpty() && sets.isEmpty()) {
                if (byId.originals() != null) continue;   // diffFrom：这一行没有变化
                throw new IllegalArgumentException("update(...).byId(...) has nothing to SET: " + byId.rows().get(i).getClass().getSimpleName()
                    + " holds only key, version or non-updatable properties");
            }
            List<Assignment> all = new ArrayList<>(sets);
            all.addAll(changed.stream().filter(a -> !a.skipped()).toList());
            out.add(statement(writers.get(), all, byId.locate(target.ref(), byId.rows().get(i))));
        }
        return out;
    }

    /** 写出一条 UPDATE；located 是 byId 定位一行的条件。 */
    private Rendered statement(SqlWriter w, List<Assignment> assignments, Cond located) {
        return set(w, target, assignments, located, "update(...)");
    }

    /**
     * 写出 {@code UPDATE 表 别名 SET ... WHERE ...}，SET 里再加上数据库实例的更新时填充（覆盖同名的项）；
     * 逻辑删除也用它。statement 用于没有 WHERE 时的报错。
     */
    static Rendered set(SqlWriter w, Target<?> target, List<Assignment> assignments, Cond located, String statement) {
        List<Assignment> all = new ArrayList<>(assignments);
        for (Assignment fill : target.fillsOf(w).onUpdate(target.ref())) {
            all.removeIf(a -> a.column().property().name().equals(fill.column().property().name()));
            all.add(fill);
        }
        if (!ImplicitJoins.used(w, List.of(target.ref()), all.stream().map(Assignment::value).toList()).isEmpty()) {
            throw new IllegalArgumentException(statement + " SET reads a related table through navigation; use a scalar subquery"
                + " or the dialect's join update");
        }
        w.sql("UPDATE ");
        target.ref().renderSource(w);
        w.sql("\nSET ");
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) w.sql(", ");
            all.get(i).column().renderName(w);
            w.sql(" = ").render(all.get(i).value());
        }
        target.renderWhere(w, statement, located);
        return w.statement();
    }
}

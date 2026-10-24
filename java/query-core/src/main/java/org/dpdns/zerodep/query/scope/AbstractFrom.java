package org.dpdns.zerodep.query.scope;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.select.FilterObject;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.SelectSpec;

import java.util.function.Supplier;

/** 所有作用域（From1 ... From16）共有的部分。不可变：每次调用都返回新的作用域。 */
public abstract class AbstractFrom<S extends AbstractFrom<S>> {
    /** 本作用域累积的 SELECT 内容（除投影外）。 */
    final SelectSpec spec;

    /** 由生成的 From1 ... From16 调用。 */
    AbstractFrom(SelectSpec spec) { this.spec = spec; }

    /** 用新的 SELECT 内容重建同类作用域，由生成的子类实现。 */
    abstract S rebuild(SelectSpec spec);

    /** SELECT DISTINCT。 */
    public final S distinct() { return rebuild(spec.distinct()); }

    /** 最多返回 rows 行。 */
    public final S limit(int rows) { return rebuild(spec.limit(rows)); }

    /** 跳过前 rows 行。 */
    public final S offset(int rows) { return rebuild(spec.offset(rows)); }

    /** 本查询不套用数据库实例的全局过滤器，也不加逻辑删除的条件。 */
    public final S unfiltered() { return rebuild(spec.unfiltered()); }

    /** 本查询不套用这些名字的全局过滤器（见 {@code Config.filter(name, ...)}），其余照常，例如跨租户但仍排除已删除的行。 */
    public final S unfiltered(String filter, String... more) { return rebuild(spec.unfiltered(filter, more)); }

    /** 本查询不加逻辑删除的条件，能看到已删除的行；全局过滤器照常，例如仍限定在当前租户。 */
    public final S withDeleted() { return rebuild(spec.withDeleted()); }

    /**
     * 之后写的 WHERE、HAVING、连接条件里，与 null、空白字符串或空集合比较的条件直接省略，
     * 适合按请求参数筛选：{@code from(Likp.class).skipEmpty().where(h -> h.customer().eq(req.customer))}。
     * 不开启时与 null 比较会报错，以免漏写条件导致查出全部数据。
     */
    public final S skipEmpty() { return rebuild(spec.skipEmpty()); }

    /**
     * 按条件对象筛选：对象的每个字段变成一个条件，默认与同名列比较相等（集合用 IN），用 {@link Where} 指定列和比较方式；
     * 值为 null、空白字符串或空集合的字段不产生条件，所以可以直接传入请求参数：{@code from(Lips.class).where(search)}。
     * 字段名找不到对应的列时报错；不作为条件的字段（分页、排序）标上 {@code @Ignore}。
     */
    public final S where(Object filter) { return rebuild(spec.where(FilterObject.condition(spec, filter))); }

    /** 追加方言子句，例如 {@code Hana.hint("NO_CS_JOIN")}。 */
    public final S with(Clause clause) { return rebuild(spec.clause(clause)); }

    /** 本作用域上的 SELECT 1，供 {@code exists(from(...).where(...))} 使用。 */
    public final Query<Integer> selectOne() { return Query.select(spec, Sql.lit(1)); }

    /** 在本作用域的 skipEmpty 模式下构造条件。 */
    final Cond condition(Supplier<Cond> body) { return SkipEmpty.within(spec.skipEmpty, body); }
}

package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Clause;

import java.util.function.Supplier;

/** 所有作用域（From1 ... From16）共有的部分。不可变：每次调用都返回新的作用域。 */
public abstract class AbstractFrom<S extends AbstractFrom<S>> {
    final SelectSpec spec;

    AbstractFrom(SelectSpec spec) { this.spec = spec; }

    /** 用新的 SELECT 内容重建同类作用域，由生成的子类实现。 */
    abstract S rebuild(SelectSpec spec);

    /** SELECT DISTINCT。 */
    public final S distinct() { return rebuild(spec.distinct()); }

    /** 最多返回 rows 行。 */
    public final S limit(int rows) { return rebuild(spec.limit(rows)); }

    /** 跳过前 rows 行。 */
    public final S offset(int rows) { return rebuild(spec.offset(rows)); }

    /** 本查询不套用数据库实例的全局过滤器。 */
    public final S unfiltered() { return rebuild(spec.unfiltered()); }

    /**
     * 之后写的 WHERE、HAVING、连接条件里，与 null、空白字符串或空集合比较的条件直接省略，
     * 适合按请求参数筛选：{@code from(Likp.class).skipEmpty().where(h -> h.customer().eq(req.customer))}。
     * 不开启时与 null 比较会报错，以免漏写条件导致查出全部数据。
     */
    public final S skipEmpty() { return rebuild(spec.skipEmpty()); }

    /** 追加方言子句，例如 {@code Hana.hint("NO_CS_JOIN")}。 */
    public final S with(Clause clause) { return rebuild(spec.clause(clause)); }

    /** 本作用域上的 SELECT 1，供 {@code exists(from(...).where(...))} 使用。 */
    final Query<Integer> selectOne() { return Query.select(spec, Sql.lit(1)); }

    /** 在本作用域的 skipEmpty 模式下构造条件。 */
    final Cond condition(Supplier<Cond> body) { return SkipEmpty.within(spec.skipEmpty, body); }
}

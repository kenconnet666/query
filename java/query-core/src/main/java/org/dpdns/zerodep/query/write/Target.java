package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.FilterObject;
import org.dpdns.zerodep.query.select.SelectSpec;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.SourceFilters;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * UPDATE 与 DELETE 共用的部分：目标表的一次出现、WHERE 条件、skipEmpty、全局过滤器的开关，以及执行前的检查。不可变。
 *
 * @param ref       目标表的这次出现
 * @param where     where 追加的条件，渲染时以 AND 连接
 * @param skipping  是否开启 skipEmpty
 * @param filtered  是否套用数据库实例的全局过滤器
 * @param everyRow  是否允许没有 where 条件（改动或删除全表）
 * @param expected  期望影响的总行数，null 表示不检查
 */
record Target<T extends Entity>(Ref<T> ref, List<Cond> where, boolean skipping, boolean filtered, boolean everyRow, Long expected) {
    /** 以 table 的一次新出现开始。 */
    static <T extends Entity> Target<T> of(Class<T> table) { return new Target<>(Ref.table(table), List.of(), false, true, false, null); }

    /** 目标表的表接口实例，传给各个 lambda。 */
    T row() { return ref.param(); }

    /** 追加 WHERE 条件；skipEmpty 下与空值比较的条件被省略。 */
    Target<T> where(Function<T, Cond> condition) {
        Cond c = SkipEmpty.within(skipping, () -> condition.apply(row()));
        if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
        return withWhere(c);
    }

    /** 追加条件对象生成的条件，规则同查询的 {@code where(filter)}。 */
    Target<T> where(Object filter) { return withWhere(FilterObject.condition(SelectSpec.from(ref), filter)); }

    /** 追加一个已经构造好的条件。 */
    Target<T> withWhere(Cond c) {
        List<Cond> all = new ArrayList<>(where);
        all.add(c);
        return new Target<>(ref, List.copyOf(all), skipping, filtered, everyRow, expected);
    }

    /** 之后的 where 与 SET 里，与空值比较的条件和值为空的 SET 项被省略。 */
    Target<T> skipEmpty() { return new Target<>(ref, where, true, filtered, everyRow, expected); }

    /** 不套用全局过滤器。 */
    Target<T> unfiltered() { return new Target<>(ref, where, skipping, false, everyRow, expected); }

    /** 允许没有 where 条件。 */
    Target<T> all() { return new Target<>(ref, where, skipping, filtered, true, expected); }

    /** 期望影响 rows 行。 */
    Target<T> expect(long rows) {
        if (rows < 0) throw new IllegalArgumentException("expect needs a non-negative row count");
        return new Target<>(ref, where, skipping, filtered, everyRow, rows);
    }

    /**
     * 写出 WHERE：全局过滤器的条件加 where 的条件。where 的条件全部为空（没写，或都被 skipEmpty 省略）又没有 all() 时报错，
     * 免得一条语句改掉或删掉整张表；全局过滤器的条件不算，因为它们只是把范围限定在当前租户等。
     * located 是 byId 按主键（和版本）定位一行的条件，没有时为空条件。
     */
    void renderWhere(SqlWriter w, String statement, Cond located) {
        List<Cond> mine = new ArrayList<>(where);
        mine.add(located);
        Cond own = Nodes.all(mine);
        if (own.isEmpty() && !everyRow) {
            throw new IllegalStateException(statement + " without where(...) would touch every row of " + ref.entity().getSimpleName()
                + "; add .all() if that is intended");
        }
        List<Cond> conds = new ArrayList<>();
        SourceFilters filters = filtered ? (SourceFilters) w.attribute(SourceFilters.class) : SourceFilters.OFF;
        conds.addAll((filters == null ? SourceFilters.NONE : filters).conditionsFor(ref));
        conds.add(own);
        Cond whereAll = Nodes.all(conds);
        if (!whereAll.isEmpty()) w.sql("\nWHERE ").render(whereAll);
    }
}

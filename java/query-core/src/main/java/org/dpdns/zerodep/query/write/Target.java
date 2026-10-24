package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.FilterObject;
import org.dpdns.zerodep.query.select.ImplicitJoins;
import org.dpdns.zerodep.query.select.SelectSpec;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Filtering;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.ShardRule;
import org.dpdns.zerodep.query.table.Sharding;
import org.dpdns.zerodep.query.table.SourceFilters;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * UPDATE 与 DELETE 共用的部分：目标表的一次出现、WHERE 条件、skipEmpty、全局过滤器的开关，以及执行前的检查。不可变。
 *
 * @param ref       目标表的这次出现
 * @param where     where 追加的条件，渲染时以 AND 连接
 * @param skipping  是否开启 skipEmpty
 * @param filtering 全局过滤器和逻辑删除的开关
 * @param fills     是否套用数据库实例的写入填充
 * @param everyRow  是否允许没有 where 条件（改动或删除全表）
 * @param expected  期望影响的总行数，null 表示不检查
 * @param hints     写在 UPDATE、DELETE 关键字后面的提示（方言的优化器提示）
 */
record Target<T extends Entity>(Ref<T> ref, List<Cond> where, boolean skipping, Filtering filtering, boolean fills, boolean everyRow, Long expected,
                               List<Clause> hints) {
    /** 以 table 的一次新出现开始。 */
    static <T extends Entity> Target<T> of(Class<T> table) { return new Target<>(Ref.table(table), List.of(), false, Filtering.ON, true, false, null, List.of()); }

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
        return new Target<>(ref, List.copyOf(all), skipping, filtering, fills, everyRow, expected, hints);
    }

    /** 之后的 where 与 SET 里，与空值比较的条件和值为空的 SET 项被省略。 */
    Target<T> skipEmpty() { return new Target<>(ref, where, true, filtering, fills, everyRow, expected, hints); }

    /** 换一个过滤开关。 */
    Target<T> filtering(Filtering f) { return new Target<>(ref, where, skipping, f, fills, everyRow, expected, hints); }

    /** 不套用写入填充。 */
    Target<T> withoutFills() { return new Target<>(ref, where, skipping, filtering, false, everyRow, expected, hints); }

    /** 允许没有 where 条件。 */
    Target<T> all() { return new Target<>(ref, where, skipping, filtering, fills, true, expected, hints); }

    /** 期望影响 rows 行。 */
    Target<T> expect(long rows) {
        if (rows < 0) throw new IllegalArgumentException("expect needs a non-negative row count");
        return new Target<>(ref, where, skipping, filtering, fills, everyRow, rows, hints);
    }

    /** 追加一个提示；只接受写在关键字后面的那种（{@link Clause.Position#HINT}），例如 MySQL 的优化器提示。 */
    Target<T> hint(Function<T, Clause> clause) {
        Clause c = clause.apply(row());
        if (c == null || c.position() != Clause.Position.HINT) {
            throw new IllegalArgumentException("UPDATE and DELETE take only optimizer hints right after the keyword, got " + c);
        }
        List<Clause> all = new ArrayList<>(hints);
        all.add(c);
        return new Target<>(ref, where, skipping, filtering, fills, everyRow, expected, List.copyOf(all));
    }

    /** 写出提示（每个后面跟一个空格，同一方言的合并由方言决定）；提示属于别的方言时报错。 */
    void renderHints(SqlWriter w) {
        for (Clause c : hints) {
            if (c.dialect() != null && !c.dialect().isInstance(w.dialect())) {
                throw new UnsupportedOperationException("A " + c.dialect().getSimpleName() + " clause cannot run on " + w.dialect().name());
            }
        }
        if (!hints.isEmpty()) w.dialect().hints(w, hints);
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
        SourceFilters filters = filters(w);
        List<Cond> conds = new ArrayList<>(filters.conditionsFor(ref));
        conds.add(navigated(w, own, filters));
        Cond whereAll = Nodes.all(conds);
        if (!whereAll.isEmpty()) w.sql("\nWHERE ").render(whereAll);
    }

    /** 这条语句套用的过滤器：数据库实例配置的（没有数据库实例时只有逻辑删除），再按 {@link #filtering} 取舍。 */
    SourceFilters filters(SqlWriter w) {
        SourceFilters configured = (SourceFilters) w.attribute(SourceFilters.class);
        return (configured == null ? SourceFilters.NONE : configured).select(filtering);
    }

    /** 按 where 条件要写的物理表；表不分表时为 null。 */
    List<String> shards(SqlWriter w) { return Sharding.of(w).route(ref, where, filtering); }

    /**
     * byId 的一行要写的物理表：行里有分表键属性时是它的值所在的表；没有时要语句显式选了分表（{@code allShards()}、{@code onShards(...)}），
     * 否则报错。表不分表时为 null。
     */
    List<String> shards(SqlWriter w, ById byId, Object row) {
        Sharding sharding = Sharding.of(w);
        ShardRule rule = sharding.rule(ref.entity());
        if (rule == null) return null;
        for (RowColumns.Column c : byId.columns()) {
            if (c.name().equals(rule.key())) return List.of(sharding.routeValue(ref.entity(), c.value(row)));
        }
        if (!filtering.choosesShards()) {
            throw new IllegalArgumentException(row.getClass().getSimpleName() + " has no shard key property '" + rule.key() + "', so byId(...) cannot tell its shard;"
                + " add it to the row or use allShards()");
        }
        return sharding.route(ref, List.of(), filtering);
    }

    /** 分表键不能改：SET 里有它时报错（换表要先删再插）。 */
    void keepsShardKey(SqlWriter w, List<Assignment> assignments, String statement) {
        ShardRule rule = Sharding.of(w).rule(ref.entity());
        if (rule == null) return;
        for (Assignment a : assignments) {
            if (a.column().property().name().equals(rule.key())) {
                throw new IllegalArgumentException(statement + " cannot change the shard key " + ref.entity().getSimpleName() + "." + rule.key()
                    + "; delete the row and insert it again");
            }
        }
    }

    /** 在 w 上登记这条语句写的物理表，之后 {@code ref.renderSource} 写出它。 */
    SqlWriter on(SqlWriter w, String table) {
        w.state(ref, List.of(table));
        return w;
    }

    /** 这条语句套用的写入填充；{@code withoutFills()} 时没有。 */
    Fills fillsOf(SqlWriter w) { return fills ? Fills.of(w) : Fills.NONE; }

    /**
     * own 经过导航关系读到别的表时，包成 {@code EXISTS (SELECT 1 FROM 单行 LEFT JOIN 关联表 ON ... WHERE own)}；没有时原样返回。
     * UPDATE、DELETE 不能像查询那样直接 LEFT JOIN，三个库的联表写法又各不相同；从单行的种子出发再 LEFT JOIN，
     * 没有关联行时关联列为 NULL，与查询里导航的语义一致。
     */
    private Cond navigated(SqlWriter w, Cond own, SourceFilters filters) {
        if (own.isEmpty()) return own;
        Set<Ref.Relation> used = ImplicitJoins.used(w, List.of(ref), List.of(own));
        if (used.isEmpty()) return own;
        Object seed = new Object();
        return new Cond() {
            @Override public void render(SqlWriter x) {
                x.sql("EXISTS (SELECT 1\nFROM (SELECT 1 AS ").identifier("ONE");
                x.dialect().fromNothing(x);
                x.sql(") ").alias(x.aliasFor(seed, "seed"));
                ImplicitJoins.emit(x, ref, used, filters);
                x.sql("\nWHERE ").render(own).sql(")");
            }
        };
    }
}

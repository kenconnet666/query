package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Step;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.postgres.Postgres.*;

@DisplayName("PostgreSQL 渲染金样例")
class PostgresSpecTest extends RenderSpec {
    @Override protected String dialect() { return "postgres"; }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Postgres.database(connections, configure);
    }

    @Override protected Map<String, SpecCase> dialectCases() {
        Map<String, SpecCase> cases = new LinkedHashMap<>(classicCases());
        cases.putAll(structureCases());
        return cases;
    }

    /** 方言包早先就有的函数与子句。 */
    private Map<String, SpecCase> classicCases() {
        return Map.of(
            "postgres-extremes", SpecCase.plain(() -> from(Lips.class)
                .select(l -> cols(
                    greatest(l.quantity(), val(new BigDecimal("4"))).as("atLeast4"),
                    least(l.quantity(), val(new BigDecimal("4"))).as("atMost4")))),
            "postgres-grouping-sets", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> groupingSets(keys(l.material(), l.unit()), keys(l.material()), keys()))
                .select(l -> cols(l.material(), l.unit(), count().as("lines")))),
            "postgres-cube", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> cube(l.material(), l.unit()))
                .select(l -> cols(l.material(), l.unit(), count().as("lines")))),
            "postgres-functions", SpecCase.plain(() -> from(Lips.class)
                .with(l -> distinctOn(l.material()))
                .where(l -> all(
                    ilike(l.unit(), "e%"),
                    l.material().isDistinctFrom(val("M9")),
                    l.unit().isNotDistinctFrom(l.unit())))
                .orderBy(l -> orders(l.material(), l.delivery().desc()))
                .select(l -> cols(l.material(), l.delivery()))),
            "postgres-aggregates", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> l.material())
                .select(l -> cols(
                    l.material(),
                    sum(l.quantity()).filter(l.unit().eq("EA")).as("each"),
                    stringAgg(l.delivery(), ";", l.delivery().desc()).as("deliveries")))));
    }

    /** 集合运算的 ALL 变体、RANGE 窗口帧、递归 CTE、FULL JOIN 以外的加锁子句。 */
    private Map<String, SpecCase> structureCases() {
        return Map.of(
            "postgres-set-all", SpecCase.plain(() -> exceptAll(intersectAll(materials("EA"), materials("KG")), materials("PC"))),
            "postgres-range-frame", SpecCase.plain(() -> from(Event.class)
                .orderBy(e -> e.id())
                .select(e -> cols(e.id(),
                    count().over(w -> w.orderBy(e.onDay()).frame(range(daysPreceding(7), Bound.currentRow()))).as("lastWeek"),
                    count().over(w -> w.orderBy(e.id()).frame(range(Bound.preceding(1), Bound.following(0)))).as("near")))),
            "postgres-recursive", SpecCase.plain(() -> {
                Cte<Step> steps = recursiveCte("steps",
                    from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> cols(l.delivery(), lit(1).as("depth"))).into(Step.class),
                    self -> from(self).where(s -> s.col(Step::depth).lt(3))
                        .select(s -> cols(s.col(Step::delivery), plus(s.col(Step::depth), lit(1)).as("depth"))).into(Step.class));
                return from(steps).orderBy(s -> s.col(Step::depth)).select(s -> s.col(Step::depth));
            }),
            "postgres-locks", SpecCase.plain(() -> from(Lips.class)
                .join(Mara.class, (l, m) -> m.material().eq(l.material()))
                .with((l, m) -> forUpdateOf(Lock.SKIP_LOCKED, l))
                .select((l, m) -> l.delivery())),
            "postgres-statistics", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> l.material())
                .select(l -> cols(l.material(),
                    percentileCont(0.5, l.quantity()).as("median"),
                    percentileDisc(0.5, l.quantity()).as("lower"),
                    mode(l.unit()).as("unit"),
                    corr(l.quantity(), l.quantity()).as("self")))),
            "postgres-full-join", SpecCase.plain(() -> from(Lips.class)
                .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material()))
                .select((l, m) -> cols(l.delivery(), m.material()))),
            "postgres-share-lock", SpecCase.plain(() -> from(Lips.class).with(forShare(Lock.NOWAIT)).select(l -> l.delivery())));
    }

    /** 某个单位的交货行的物料。 */
    private static Query<String> materials(String unit) { return from(Lips.class).where(l -> l.unit().eq(unit)).select(l -> l.material()); }
}

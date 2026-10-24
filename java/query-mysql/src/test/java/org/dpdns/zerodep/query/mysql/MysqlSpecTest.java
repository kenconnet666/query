package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.mysql.MysqlTables.Article;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Step;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.mysql.Mysql.*;

@DisplayName("MySQL 渲染金样例")
public class MysqlSpecTest extends RenderSpec {
    @Override protected String dialect() { return "mysql"; }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Mysql.database(connections, configure);
    }

    @Override protected Map<String, SpecCase> dialectCases() {
        Map<String, SpecCase> cases = new LinkedHashMap<>(classicCases());
        cases.putAll(structureCases());
        cases.putAll(functionCases());
        return cases;
    }

    /** 方言包早先就有的函数与子句。 */
    private Map<String, SpecCase> classicCases() {
        return Map.of(
            "mysql-extremes", SpecCase.plain(() -> from(Lips.class)
                .select(l -> cols(
                    greatest(l.quantity(), val(new BigDecimal("4"))).as("atLeast4"),
                    least(l.quantity(), val(new BigDecimal("4"))).as("atMost4")))),
            "mysql-functions", SpecCase.plain(() -> from(Lips.class)
                .with(maxExecutionTime(2000))
                .groupBy(l -> l.material())
                .select(l -> cols(
                    l.material(),
                    daysBetween(lit(LocalDate.of(2026, 1, 1)), lit(LocalDate.of(2026, 2, 1))).as("days"),
                    dateFormat(lit(LocalDate.of(2026, 1, 31)), "%Y%m%d").as("formatted"),
                    stringAgg(l.delivery(), "|", l.delivery().asc()).as("deliveries")))));
    }

    /** 集合运算的 ALL 变体、RANGE 窗口帧、递归 CTE、FULL JOIN 以外的加锁子句。 */
    private Map<String, SpecCase> structureCases() {
        return Map.of(
            "mysql-set-all", SpecCase.plain(() -> exceptAll(intersectAll(materials("EA"), materials("KG")), materials("PC"))),
            "mysql-range-frame", SpecCase.plain(() -> from(Event.class)
                .orderBy(e -> e.id())
                .select(e -> cols(e.id(),
                    count().over(w -> w.orderBy(e.onDay()).frame(range(daysPreceding(7), Bound.currentRow()))).as("lastWeek"),
                    count().over(w -> w.orderBy(e.id()).frame(range(Bound.preceding(1), Bound.following(0)))).as("near")))),
            "mysql-recursive", SpecCase.plain(() -> {
                Cte<Step> steps = recursiveCte("steps",
                    from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> cols(l.delivery(), lit(1).as("depth"))).into(Step.class),
                    self -> from(self).where(s -> s.col(Step::depth).lt(3))
                        .select(s -> cols(s.col(Step::delivery), plus(s.col(Step::depth), lit(1)).as("depth"))).into(Step.class));
                return from(steps).orderBy(s -> s.col(Step::depth)).select(s -> s.col(Step::depth));
            }),
            "mysql-locks", SpecCase.plain(() -> from(Lips.class)
                .join(Mara.class, (l, m) -> m.material().eq(l.material()))
                .with((l, m) -> forUpdateOf(Lock.SKIP_LOCKED, l))
                .select((l, m) -> l.delivery())),
            "mysql-share-lock", SpecCase.plain(() -> from(Lips.class).with(forShare(Lock.NOWAIT)).select(l -> l.delivery())));
    }

    /** 某个单位的交货行的物料。 */
    private static Query<String> materials(String unit) { return from(Lips.class).where(l -> l.unit().eq(unit)).select(l -> l.material()); }

    /** 合并的优化器提示、JSON 与全文检索。 */
    private Map<String, SpecCase> functionCases() {
        return Map.of(
            "mysql-hints", SpecCase.plain(() -> from(Lips.class)
                .join(Mara.class, (l, m) -> m.material().eq(l.material()))
                .with((l, m) -> maxExecutionTime(2000))
                .with((l, m) -> index(l, "PRIMARY"))
                .with((l, m) -> noIndex(m))
                .select((l, m) -> l.delivery())),
            "mysql-json", SpecCase.plain(() -> from(Article.class)
                .where(a -> jsonContains(a.doc(), "{\"status\":\"open\"}"))
                .select(a -> cols(a.id(), jsonText(a.doc(), "$.owner.name").as("owner")))),
            "mysql-fulltext", SpecCase.plain(() -> from(Article.class)
                .where(a -> any(match("pump", a.title(), a.body()), matchBoolean("+valve -leak", a.title(), a.body())))
                .orderBy(a -> matchScore("pump", a.title(), a.body()).desc())
                .select(a -> a.id())));
    }
}

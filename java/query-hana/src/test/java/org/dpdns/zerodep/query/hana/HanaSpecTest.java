package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.hana.HanaTables.LipsByUnit;
import org.dpdns.zerodep.query.hana.HanaTables.Org;
import org.dpdns.zerodep.query.hana.HanaTables.OrgNode;
import org.dpdns.zerodep.query.hana.HanaTables.SalesView;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.hana.Hana.*;

@DisplayName("HANA 渲染金样例")
class HanaSpecTest extends RenderSpec {
    @Override protected String dialect() { return "hana"; }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Hana.database(connections, configure);
    }

    @Override protected Map<String, SpecCase> dialectCases() {
        Map<String, SpecCase> cases = new LinkedHashMap<>(classicCases());
        cases.putAll(structureCases());
        cases.putAll(functionCases());
        return cases;
    }

    /** 方言包早先就有的函数与子句。 */
    private Map<String, SpecCase> classicCases() {
        LocalDate first = LocalDate.of(2026, 1, 1);
        LocalDate day = LocalDate.of(2026, 1, 31);
        return Map.of(
            "hana-extremes", SpecCase.plain(() -> from(Lips.class)
                .select(l -> cols(
                    greatest(l.quantity(), val(new BigDecimal("4"))).as("atLeast4"),
                    least(l.quantity(), val(new BigDecimal("4"))).as("atMost4"),
                    hana(l.delivery().substring(2)).toInteger().as("number")))),
            "hana-grouping-sets", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> groupingSets(keys(l.material(), l.unit()), keys(l.material()), keys()))
                .select(l -> cols(l.material(), l.unit(), count().as("lines")))),
            "hana-cube", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> cube(l.material(), l.unit()))
                .select(l -> cols(l.material(), l.unit(), count().as("lines")))),
            "hana-functions", SpecCase.plain(() -> from(Lips.class)
                .with(hint("NO_CS_JOIN", "IGNORE_PLAN_CACHE"))
                .select(l -> cols(
                    lit(first).daysBetween(lit(first).addDays(3)).as("days"),
                    lit(day).addMonths(1).as("months"),
                    lit(day).addYears(2).as("years"),
                    hana(lit(day)).toVarchar("YYYYMMDD").as("text"),
                    hana(l.delivery()).toDate("YYYYMMDD").as("day"),
                    hana(l.material()).length().as("size")))),
            "hana-regexpr", SpecCase.plain(() -> from(Lips.class)
                .where(l -> hana(l.delivery()).likeRegexpr("^D[0-9]+$"))
                .select(l -> cols(
                    hana(l.quantity()).toNvarchar().as("text"),
                    hana(lit(day)).toNvarchar("YYYYMMDD").as("day")))),
            "hana-string-agg", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> l.material())
                .select(l -> cols(
                    l.material(),
                    l.delivery().stringAgg(",", l.delivery().asc(), l.unit().desc())
                        .as("deliveries")))));
    }

    /** HANA 的中位数、窗口式的百分位数、相关系数与 FULL JOIN。 */
    private Map<String, SpecCase> structureCases() {
        return Map.of(
            "hana-statistics", SpecCase.plain(() -> from(Lips.class)
                .distinct()
                .select(l -> cols(l.material(),
                    percentileCont(0.5, l.quantity()).over(w -> w.partitionBy(l.material())).as("median"),
                    percentileDisc(0.5, l.quantity()).over(w -> w.partitionBy(l.material())).as("lower")))),
            "hana-median", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> l.material())
                .select(l -> cols(l.material(), hana(l.quantity()).median().as("median"), corr(l.quantity(), l.quantity()).as("self")))),
            "hana-full-join", SpecCase.plain(() -> from(Lips.class)
                .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material()))
                .select((l, m) -> cols(l.delivery(), m.material()))));
    }

    /** 带参数的视图与表函数、层级、全文检索与 JSON。 */
    private Map<String, SpecCase> functionCases() {
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("P_YEAR", "2026");
        placeholders.put("P_REGION", null);
        return Map.of(
            "hana-calculation-view", SpecCase.plain(() -> fromView(SalesView.class, placeholders)
                .where(v -> v.amount().gt(BigDecimal.TEN))
                .select(v -> cols(v.material(), v.amount()))),
            "hana-table-function", SpecCase.plain(() -> fromFunction(LipsByUnit.class, Map.of("P_UNIT", "EA"))
                .orderBy(f -> f.delivery())
                .select(f -> f.delivery())),
            "hana-hierarchy", SpecCase.plain(() -> from(hierarchy(OrgNode.class, orgSource()))
                .orderBy(n -> n.col(OrgNode::hierarchyRank))
                .select(n -> cols(n.col(OrgNode::nodeId), n.col(OrgNode::hierarchyLevel), n.col(OrgNode::hierarchyParentRank), n.col(OrgNode::name)))),
            "hana-descendants", SpecCase.plain(() -> from(descendants(OrgNode.class, orgSource(), List.of("B")))
                .orderBy(n -> n.col(OrgNode::nodeId))
                .select(n -> cols(n.col(OrgNode::nodeId), n.col(OrgNode::hierarchyDistance)))),
            "hana-search", SpecCase.plain(() -> from(Lips.class)
                .where(l -> {
                    contains("M1", l.material());
                    containsFuzzy(0.8, "D1", l.delivery(), l.material());
                })
                .orderBy(l -> score().desc())
                .select(l -> cols(l.delivery(), score().as("score"), hana(l.unit()).jsonValue("$.unit").as("json")))));
    }

    /** 组织表作为层级的来源：编号与上级编号改名为 nodeId、parentId。 */
    static org.dpdns.zerodep.query.select.Query<Map<String, Object>> orgSource() {
        return from(Org.class).select(o -> cols(o.id().as("nodeId"), o.parent().as("parentId"), o.name()));
    }
}

package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
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
        LocalDate first = LocalDate.of(2026, 1, 1);
        LocalDate day = LocalDate.of(2026, 1, 31);
        return Map.of(
            "hana-extremes", SpecCase.plain(() -> from(Lips.class)
                .select((r, l) -> Arrays.asList(
                    r.get(greatest(l.col(Lips::getQuantity), val(new BigDecimal("4"))).as("atLeast4")),
                    r.get(least(l.col(Lips::getQuantity), val(new BigDecimal("4"))).as("atMost4")),
                    r.get(toInteger(substring(l.col(Lips::getDelivery), 2)).as("number"))))),
            "hana-grouping-sets", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> groupingSets(keys(l.col(Lips::getMaterial), l.col(Lips::getUnit)), keys(l.col(Lips::getMaterial)), keys()))
                .select((r, l) -> Arrays.asList(r.get(l, Lips::getMaterial), r.get(l, Lips::getUnit), r.get(count().as("lines"))))),
            "hana-cube", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> cube(l.col(Lips::getMaterial), l.col(Lips::getUnit)))
                .select((r, l) -> Arrays.asList(r.get(l, Lips::getMaterial), r.get(l, Lips::getUnit), r.get(count().as("lines"))))),
            "hana-functions", SpecCase.plain(() -> from(Lips.class)
                .with(hint("NO_CS_JOIN", "IGNORE_PLAN_CACHE"))
                .select((r, l) -> Arrays.asList(
                    r.get(daysBetween(lit(first), addDays(lit(first), 3)).as("days")),
                    r.get(addMonths(lit(day), 1).as("months")),
                    r.get(addYears(lit(day), 2).as("years")),
                    r.get(toVarchar(lit(day), "YYYYMMDD").as("text")),
                    r.get(toDate(l.col(Lips::getDelivery), "YYYYMMDD").as("day")),
                    r.get(length(l.col(Lips::getMaterial)).as("size"))))),
            "hana-regexpr", SpecCase.plain(() -> from(Lips.class)
                .where(l -> likeRegexpr(l.col(Lips::getDelivery), "^D[0-9]+$"))
                .select((r, l) -> Arrays.asList(
                    r.get(toNvarchar(l.col(Lips::getQuantity)).as("text")),
                    r.get(toNvarchar(lit(day), "YYYYMMDD").as("day"))))),
            "hana-string-agg", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> l.col(Lips::getMaterial))
                .select((r, l) -> Arrays.asList(
                    r.get(l, Lips::getMaterial),
                    r.get(stringAgg(l.col(Lips::getDelivery), ",", l.col(Lips::getDelivery).asc(), l.col(Lips::getUnit).desc())
                        .as("deliveries"))))));
    }
}

package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;
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
                .select(l -> cols(
                    greatest(l.quantity(), val(new BigDecimal("4"))).as("atLeast4"),
                    least(l.quantity(), val(new BigDecimal("4"))).as("atMost4"),
                    toInteger(substring(l.delivery(), 2)).as("number")))),
            "hana-grouping-sets", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> groupingSets(keys(l.material(), l.unit()), keys(l.material()), keys()))
                .select(l -> cols(l.material(), l.unit(), count().as("lines")))),
            "hana-cube", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> cube(l.material(), l.unit()))
                .select(l -> cols(l.material(), l.unit(), count().as("lines")))),
            "hana-functions", SpecCase.plain(() -> from(Lips.class)
                .with(hint("NO_CS_JOIN", "IGNORE_PLAN_CACHE"))
                .select(l -> cols(
                    daysBetween(lit(first), addDays(lit(first), 3)).as("days"),
                    addMonths(lit(day), 1).as("months"),
                    addYears(lit(day), 2).as("years"),
                    toVarchar(lit(day), "YYYYMMDD").as("text"),
                    toDate(l.delivery(), "YYYYMMDD").as("day"),
                    length(l.material()).as("size")))),
            "hana-regexpr", SpecCase.plain(() -> from(Lips.class)
                .where(l -> likeRegexpr(l.delivery(), "^D[0-9]+$"))
                .select(l -> cols(
                    toNvarchar(l.quantity()).as("text"),
                    toNvarchar(lit(day), "YYYYMMDD").as("day")))),
            "hana-string-agg", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> l.material())
                .select(l -> cols(
                    l.material(),
                    stringAgg(l.delivery(), ",", l.delivery().asc(), l.unit().desc())
                        .as("deliveries")))));
    }
}

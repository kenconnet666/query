package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.util.Arrays;
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
        return Map.of(
            "postgres-extremes", SpecCase.plain(() -> from(Lips.class)
                .select((r, l) -> Arrays.asList(
                    r.get(greatest(l.col(Lips::getQuantity), val(new BigDecimal("4"))).as("atLeast4")),
                    r.get(least(l.col(Lips::getQuantity), val(new BigDecimal("4"))).as("atMost4"))))),
            "postgres-grouping-sets", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> keys(groupingSets(keys(l.col(Lips::getMaterial), l.col(Lips::getUnit)), keys(l.col(Lips::getMaterial)), keys())))
                .select((r, l) -> Arrays.asList(r.get(l, Lips::getMaterial), r.get(l, Lips::getUnit), r.get(count().as("lines"))))),
            "postgres-cube", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> keys(cube(l.col(Lips::getMaterial), l.col(Lips::getUnit))))
                .select((r, l) -> Arrays.asList(r.get(l, Lips::getMaterial), r.get(l, Lips::getUnit), r.get(count().as("lines"))))),
            "postgres-functions", SpecCase.plain(() -> from(Lips.class)
                .with(l -> distinctOn(l.col(Lips::getMaterial)))
                .where(l -> all(
                    ilike(l.col(Lips::getUnit), "e%"),
                    pg(l.col(Lips::getMaterial)).isDistinctFrom("M9"),
                    pg(l.col(Lips::getUnit)).isNotDistinctFrom(l.col(Lips::getUnit))))
                .orderBy(l -> orders(l.col(Lips::getMaterial).asc(), l.col(Lips::getDelivery).desc()))
                .select((r, l) -> Arrays.asList(r.get(l, Lips::getMaterial), r.get(l, Lips::getDelivery)))),
            "postgres-aggregates", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> keys(l.col(Lips::getMaterial)))
                .select((r, l) -> Arrays.asList(
                    r.get(l, Lips::getMaterial),
                    r.get(filter(sum(l.col(Lips::getQuantity)), l.col(Lips::getUnit).eq("EA")).as("each")),
                    r.get(stringAgg(l.col(Lips::getDelivery), ";", l.col(Lips::getDelivery).desc()).as("deliveries"))))));
    }
}

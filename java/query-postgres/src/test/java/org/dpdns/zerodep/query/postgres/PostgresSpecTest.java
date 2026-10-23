package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
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
                    pg(l.material()).isDistinctFrom("M9"),
                    pg(l.unit()).isNotDistinctFrom(l.unit())))
                .orderBy(l -> orders(l.material(), l.delivery().desc()))
                .select(l -> cols(l.material(), l.delivery()))),
            "postgres-aggregates", SpecCase.plain(() -> from(Lips.class)
                .groupBy(l -> l.material())
                .select(l -> cols(
                    l.material(),
                    sum(l.quantity()).filter(l.unit().eq("EA")).as("each"),
                    stringAgg(l.delivery(), ";", l.delivery().desc()).as("deliveries")))));
    }
}

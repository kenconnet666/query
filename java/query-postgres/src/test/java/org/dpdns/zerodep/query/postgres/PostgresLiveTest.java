package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.postgres.Postgres.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Needs QUERY_PG_URL, QUERY_PG_USER and QUERY_PG_PASSWORD; skipped otherwise. */
@DisplayName("PostgreSQL 实库场景")
class PostgresLiveTest extends LiveScenarios {

    @Override protected LiveDatabase connect() { return LiveDatabase.fromEnvironment("PG"); }

    @Override protected Database database(LiveDatabase live, Consumer<Database.Config> configure) {
        return Postgres.database(live.connections(), configure);
    }

    @Override protected String text(int length) { return "VARCHAR(" + length + ")"; }

    @Test
    void DISTINCT_ON取每个物料最新的交货() {
        List<String> rows = db.list(from(Lips.class)
            .with(l -> distinctOn(l.col(Lips::getMaterial)))
            .orderBy(l -> orders(l.col(Lips::getMaterial).asc(), l.col(Lips::getCreatedOn).desc()))
            .selectValue(l -> l.col(Lips::getDelivery)));
        assertEquals(List.of("D5", "D3"), rows);
    }

    @Test
    void 聚合FILTER与ILIKE以及IS_DISTINCT_FROM() {
        BigDecimal m1 = db.one(from(Lips.class).selectValue(l -> filter(sum(l.col(Lips::getQuantity)), l.col(Lips::getMaterial).eq("M1"))));
        assertEquals(0, new BigDecimal("6").compareTo(m1));
        long ea = db.one(from(Lips.class).where(l -> ilike(l.col(Lips::getUnit), "ea")).selectValue(l -> count()));
        assertEquals(5, ea);
        long notM1 = db.one(from(Lips.class).where(l -> pg(l.col(Lips::getMaterial)).isDistinctFrom("M1")).selectValue(l -> count()));
        assertEquals(2, notM1);
    }

    @Test
    void 带排序的STRING_AGG() {
        List<String> rows = db.list(from(Lips.class)
            .groupBy(l -> keys(l.col(Lips::getMaterial)))
            .orderBy(l -> orders(l.col(Lips::getMaterial).asc()))
            .select((r, l) -> r.get(l, Lips::getMaterial) + "=" + r.get(stringAgg(l.col(Lips::getDelivery), ",", l.col(Lips::getDelivery).asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }
}

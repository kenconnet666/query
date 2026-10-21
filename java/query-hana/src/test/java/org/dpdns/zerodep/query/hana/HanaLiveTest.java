package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.hana.Hana.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Needs QUERY_HANA_URL, QUERY_HANA_USER and QUERY_HANA_PASSWORD; skipped otherwise. */
@DisplayName("HANA 实库场景")
class HanaLiveTest extends LiveScenarios {

    @Override protected LiveDatabase connect() { return LiveDatabase.fromEnvironment("HANA"); }

    @Override protected Database database(LiveDatabase live, Consumer<Database.Config> configure) {
        return Hana.database(live.connections(), configure);
    }

    @Override protected String text(int length) { return "NVARCHAR(" + length + ")"; }

    @Test
    void HANA日期函数在实库上可用() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        int days = db.one(from(Lips.class).limit(1).selectValue(l -> daysBetween(lit(day), addMonths(lit(day), 1))));
        assertEquals(31, days);
        assertEquals("20260105", db.one(from(Lips.class).limit(1).selectValue(l -> toVarchar(addDays(lit(day), 4), "YYYYMMDD"))));
    }

    @Test
    void 带排序的STRING_AGG与提示子句() {
        List<String> rows = db.list(from(Lips.class)
            .with(hint("NO_CS_JOIN"))
            .groupBy(l -> keys(l.col(Lips::getMaterial)))
            .orderBy(l -> orders(l.col(Lips::getMaterial).asc()))
            .select((r, l) -> r.get(l, Lips::getMaterial) + "=" + r.get(stringAgg(l.col(Lips::getDelivery), ",", l.col(Lips::getDelivery).asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }

    @Test
    void 分组集合与CUBE() {
        List<String> sets = db.list(from(Lips.class)
            .groupBy(l -> keys(groupingSets(keys(l.col(Lips::getMaterial)), keys())))
            .orderBy(l -> orders(grouping(l.col(Lips::getMaterial)).asc(), l.col(Lips::getMaterial).asc()))
            .select((r, l) -> r.get(l, Lips::getMaterial) + "=" + r.get(sum(l.col(Lips::getQuantity))).stripTrailingZeros().toPlainString()));
        assertEquals(List.of("M1=6", "M2=12", "null=18"), sets);
        List<Long> cells = db.list(from(Lips.class)
            .groupBy(l -> keys(cube(l.col(Lips::getMaterial), l.col(Lips::getUnit))))
            .selectValue(l -> count()));
        assertEquals(6, cells.size(), "material x unit, material, unit and the grand total");
    }

    @Test
    void GREATEST与LEAST遇到NULL时返回NULL() {
        List<String> rows = db.list(from(Lips.class)
            .orderBy(l -> orders(l.col(Lips::getDelivery).asc()))
            .select((r, l) -> r.get(l, Lips::getDelivery) + ":" + plain(r.get(greatest(l.col(Lips::getQuantity), val(new BigDecimal("4")))))
                + ":" + plain(r.get(least(l.col(Lips::getQuantity), val(new BigDecimal("4")))))));
        assertEquals(List.of("D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"), rows);
        assertNull(db.one(from(Lips.class).where(l -> l.col(Lips::getDelivery).eq("D1"))
            .selectValue(l -> greatest(l.col(Lips::getQuantity), nullOf(BigDecimal.class)))));
    }

    @Test
    void TO_INTEGER把文本转成整数() {
        assertEquals(List.of(1, 2, 3, 4, 5), db.list(from(Lips.class)
            .orderBy(l -> orders(l.col(Lips::getDelivery).asc()))
            .selectValue(l -> toInteger(substring(l.col(Lips::getDelivery), 2)))));
    }
}

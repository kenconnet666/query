package org.dpdns.zerodep.query.it.dialect;

import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.it.support.Db.SQLITE;
import static org.dpdns.zerodep.query.sqlite.Sqlite.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SQLite 专属的写法（{@code Sqlite} 门面）和它的类型行为：区分大小写的 GLOB、中位数与百分位数，小数按数值比较。数据见 {@link SampleData}。
 */
@LiveTest(only = SQLITE)
@DisplayName("SQLite 专属写法")
class SqliteTest {
    @Parameter Live live;

    @Test
    void GLOB区分大小写而LIKE不区分() {
        long like = live.db().one(from(Lips.class).unfiltered().where(l -> like(l.unit(), "ea")).select(l -> count()));
        long lower = live.db().one(from(Lips.class).unfiltered().where(l -> glob(l.unit(), "ea")).select(l -> count()));
        long pattern = live.db().one(from(Lips.class).unfiltered().where(l -> glob(l.unit(), "E?")).select(l -> count()));
        assertEquals(List.of(6L, 0L, 6L), List.of(like, lower, pattern), "LIKE ignores ASCII case on SQLite, GLOB does not");
    }

    @Test
    void 中位数与百分位数() {
        // M1 的数量是 3、2、1。
        Map<String, Object> r = live.db().one(from(Lips.class).where(l -> l.material().eq("M1"))
            .select(l -> cols(median(l.quantity()).as("middle"), percentileCont(0.5, l.quantity()).as("p50"), percentileDisc(1, l.quantity()).as("top"))));
        assertEquals(2.0, ((Number) r.get("middle")).doubleValue());
        assertEquals(2.0, ((Number) r.get("p50")).doubleValue());
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) r.get("top")));
    }

    @Test
    void 小数按数值比较且有效数字在十五位以内时原样读回() {
        assertEquals(List.of("D6"), live.db().list(from(Lips.class).unfiltered().where(l -> l.quantity().gt(new BigDecimal("9.5"))).select(l -> l.delivery())),
            "NUMERIC affinity compares as numbers, not text");
        assertEquals(0, new BigDecimal("100.000").compareTo(live.db().one(from(Lips.class).unfiltered().where(l -> l.delivery().eq("D6")).select(l -> l.quantity()))));
    }
}

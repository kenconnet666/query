package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("动态列与排序")
class DynamicTest {
    private static final TestDialect DIALECT = new TestDialect();

    /** 按物料、单位、交货单前缀汇总数量。 */
    private static Query<Map<String, Object>> totals() {
        return from(Lips.class)
            .groupBy(l -> keys(l.material(), l.unit(), substring(l.delivery(), 1, 1)))
            .orderBy(l -> orders(l.material().asc(), l.unit().asc()))
            .select(l -> cols(l.material(), l.unit(), substring(l.delivery(), 1, 1).as("prefix"), sum(l.quantity()).as("total")));
    }

    @Test
    void 排除的列写成NULL且只被它用到的关系不再连接() {
        Query<Map<String, Object>> q = from(Lips.class)
            .select(l -> cols(l.delivery(), l.mara().partNumber(), l.text().text()))
            .exclude("partNumber");
        assertEquals("""
            SELECT LIPS.VBELN AS "delivery", NULL AS "partNumber", MAKT.MAKTX AS "text"
            FROM QUERY_IT.LIPS LIPS
            LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?""", q.toSql(DIALECT).sql());
        assertEquals(List.of("delivery", "partNumber", "text"), q.labels());
    }

    @Test
    void 排除分组的维度时分组键和按它的排序一起去掉() {
        String sql = totals().exclude("unit", "prefix").toSql(DIALECT).sql();
        assertEquals("""
            SELECT LIPS.MATNR AS "material", NULL AS "unit", NULL AS "prefix", SUM(LIPS.LFIMG) AS "total"
            FROM QUERY_IT.LIPS LIPS
            GROUP BY LIPS.MATNR
            ORDER BY LIPS.MATNR ASC""", sql);
    }

    @Test
    void only按用户选的列取值且空列表保持不变() {
        Query<Map<String, Object>> q = totals();
        String sql = q.only(List.of("PREFIX", "total")).toSql(DIALECT).sql();
        assertTrue(sql.endsWith("\nGROUP BY SUBSTRING(LIPS.VBELN, 1, 1)"), sql);
        assertSame(q, q.only(List.of()));
        assertSame(q, q.only(null));
        var e = assertThrows(IllegalArgumentException.class, () -> q.only(List.of("material", "plant")));
        assertEquals("only(...): unknown column 'plant'; the query has [material, unit, prefix, total]", e.getMessage());
    }

    @Test
    void excludeIf只在条件成立时排除() {
        Query<Map<String, Object>> q = totals();
        assertSame(q, q.excludeIf(false, "unit"));
        assertTrue(q.excludeIf(true, "unit").toSql(DIALECT).sql().startsWith("SELECT LIPS.MATNR AS \"material\", NULL AS \"unit\""));
    }

    @Test
    void 构造器投影里被排除的参数为null() {
        Query<Delivery> q = from(Lips.class)
            .select(l -> row(Delivery::new, l.material(), l.unit(), l.quantity()))
            .exclude("unit");
        assertTrue(q.toSql(DIALECT).sql().startsWith("SELECT LIPS.MATNR AS \"material\", NULL AS \"unit\", LIPS.LFIMG AS \"quantity\""));
        assertEquals(new Delivery("M1", null, BigDecimal.ONE), q.mapRow(new Object[] {"M1", null, BigDecimal.ONE}));
    }

    @Test
    void sortBy按结果列替换原来的排序() {
        String sql = totals().sortBy("total desc, Material").toSql(DIALECT).sql();
        assertTrue(sql.endsWith("ORDER BY SUM(LIPS.LFIMG) DESC, LIPS.MATNR ASC"), sql);
        Query<Map<String, Object>> q = totals();
        assertSame(q, q.sortBy(" "));
        assertSame(q, q.sortBy(null));
    }

    @Test
    void sortBy只认结果列和asc与desc() {
        Query<Map<String, Object>> q = totals();
        for (String bad : Arrays.asList("plant", "total; DROP TABLE x", "total sideways", "total desc nulls")) {
            assertThrows(IllegalArgumentException.class, () -> q.sortBy(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> q.exclude("total").sortBy("total"));
        Query<String> units = from(Lips.class).select(l -> l.unit());
        var e = assertThrows(IllegalStateException.class, () -> units.unionAll(units).sortBy("unit"));
        assertTrue(e.getMessage().contains("single SELECT"), e.getMessage());
    }
}

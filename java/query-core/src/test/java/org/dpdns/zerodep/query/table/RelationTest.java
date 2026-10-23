package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("关系与导航")
class RelationTest {
    private static final TestDialect DIALECT = new TestDialect();

    @Test
    void 多对一关系按需LEFT_JOIN且同一关系只连接一次() {
        Rendered r = from(Lips.class)
            .where(l -> l.mara().manufacturer().eq("MK1"))
            .orderBy(l -> l.mara().partNumber())
            .select(l -> cols(l.delivery(), l.mara().partNumber(), l.text().text()))
            .toSql(DIALECT);
        assertEquals("""
            SELECT LIPS.VBELN AS "delivery", MARA.MFRPN AS "partNumber", MAKT.MAKTX AS "text"
            FROM QUERY_IT.LIPS LIPS
            LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
            LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
            WHERE MARA.MFRNR = ?
            ORDER BY MARA.MFRPN ASC""", r.sql());
        assertEquals(List.of("1", "MK1"), r.parameters());
    }

    @Test
    void 没有用到的关系不会出现在SQL里() {
        String none = null;
        String sql = from(Lips.class).skipEmpty()
            .where(l -> l.mara().manufacturer().eq(none))
            .select(l -> l.delivery())
            .toSql(DIALECT).sql();
        assertEquals("SELECT LIPS.VBELN AS \"delivery\"\nFROM QUERY_IT.LIPS LIPS", sql);
    }

    @Test
    void 关系可以继续导航且全局过滤器进入ON() {
        var db = new TestDatabase(c -> c.filter(org.dpdns.zerodep.query.testing.Erp.Client.class, t -> t.client().eq("100")));
        String sql = db.render(from(Lips.class).select(l -> l.mara().text().text())).sql();
        assertEquals("""
            SELECT MAKT.MAKTX AS "text"
            FROM QUERY_IT.LIPS LIPS
            LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT AND MARA.MANDT = ?
            LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = MARA.MATNR AND MAKT.MANDT = MARA.MANDT AND MAKT.SPRAS = ? AND MAKT.MANDT = ?
            WHERE LIPS.MANDT = ?""", sql);
    }

    @Test
    void 一对多用EXISTS和相关子查询() {
        String sql = from(Mara.class)
            .where(m -> all(m.deliveries().any(l -> l.quantity().gt(BigDecimal.ONE)), m.deliveries().where(l -> l.unit().eq("KG")).none()))
            .select(m -> cols(m.material(), m.deliveries().count().as("lines"), m.deliveries().sum(l -> l.quantity()).as("total")))
            .toSql(DIALECT).sql();
        assertEquals("""
            SELECT MARA.MATNR AS "material", (SELECT COUNT(*)
            FROM QUERY_IT.LIPS LIPS
            WHERE LIPS.MATNR = MARA.MATNR AND LIPS.MANDT = MARA.MANDT) AS "lines", (SELECT SUM(LIPS2.LFIMG)
            FROM QUERY_IT.LIPS LIPS2
            WHERE LIPS2.MATNR = MARA.MATNR AND LIPS2.MANDT = MARA.MANDT) AS "total"
            FROM QUERY_IT.MARA MARA
            WHERE EXISTS (SELECT 1
            FROM QUERY_IT.LIPS LIPS3
            WHERE LIPS3.MATNR = MARA.MATNR AND LIPS3.MANDT = MARA.MANDT AND LIPS3.LFIMG > ?) AND NOT EXISTS (SELECT 1
            FROM QUERY_IT.LIPS LIPS4
            WHERE LIPS4.MATNR = MARA.MATNR AND LIPS4.MANDT = MARA.MANDT AND LIPS4.VRKME = ?)""", sql);
    }

    @Test
    void first取每组第一行并可继续取列() {
        String sql = from(Mara.class)
            .select(m -> cols(m.material(), m.deliveries().first(l -> l.createdOn().desc()).delivery().as("latest")))
            .toSql(DIALECT).sql();
        assertEquals("""
            SELECT MARA.MATNR AS "material", LIPS_FIRST."delivery" AS "latest"
            FROM QUERY_IT.MARA MARA
            LEFT JOIN (SELECT LIPS.MANDT AS "client", LIPS.ERDAT AS "createdOn", LIPS.VBELN AS "delivery", LIPS.MATNR AS "material", \
            LIPS.LFIMG AS "quantity", LIPS.VGBEL AS "salesOrder", LIPS.VRKME AS "unit", \
            ROW_NUMBER() OVER (PARTITION BY LIPS.MATNR, LIPS.MANDT ORDER BY LIPS.ERDAT DESC) AS "rn__"
            FROM QUERY_IT.LIPS LIPS) LIPS_FIRST ON LIPS_FIRST."material" = MARA.MATNR AND LIPS_FIRST."client" = MARA.MANDT \
            AND LIPS_FIRST."rn__" = 1""", sql);
    }

    @Test
    void first需要连接两张表的等值条件() {
        var e = assertThrows(IllegalArgumentException.class, () -> from(Mara.class)
            .select(m -> m.many(Lips.class, l -> l.unit().eq("EA")).first(l -> l.createdOn()).delivery()));
        assertTrue(e.getMessage().contains("first() needs an equality"), e.getMessage());
    }

    @Test
    void 注解声明的关系按列名配对() {
        String sql = from(Lips.class)
            .where(l -> l.salesData().any())
            .select(l -> l.anyText().text())
            .toSql(DIALECT).sql();
        assertTrue(sql.contains("LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MANDT = LIPS.MANDT AND MAKT.MATNR = LIPS.MATNR"), sql);
        assertTrue(sql.contains("WHERE EXISTS (SELECT 1\nFROM QUERY_IT.VBKD VBKD\nWHERE VBKD.VBELN = LIPS.VGBEL)"), sql);
    }

    @Test
    void 关系不进入selectAs的同名列匹配() {
        record Line(String delivery, String material) { }
        String sql = from(Lips.class).where(l -> l.mara().materialType().eq("ZMAT")).selectAs(Line.class).toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT LIPS.VBELN AS \"delivery\", LIPS.MATNR AS \"material\""), sql);
        assertFalse(sql.contains("MARA.MATNR AS"), sql);
    }
}

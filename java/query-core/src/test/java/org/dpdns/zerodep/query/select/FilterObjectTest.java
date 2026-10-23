package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("条件对象")
class FilterObjectTest {
    private static final TestDialect DIALECT = new TestDialect();

    /** 交货查询的请求参数。 */
    public record DeliverySearch(
        List<String> material,
        @Where(value = "createdOn", op = Where.Op.GE) LocalDate from,
        @Where(value = "createdOn", op = Where.Op.LT) LocalDate to,
        @Where(value = {"mara.partNumber", "text.text"}, op = Where.Op.CONTAINS) String keyword,
        @Where(op = Where.Op.NE) String unit,
        @Ignore String sort) { }

    private static Rendered render(DeliverySearch search) {
        return from(Lips.class).where(search).select(l -> l.delivery()).toSql(DIALECT);
    }

    @Test
    void 每个字段按注解变成条件且关系按需连接() {
        Rendered r = render(new DeliverySearch(List.of("M1", "M2"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), "50%", "KG", "x"));
        assertEquals("""
            SELECT LIPS.VBELN AS "delivery"
            FROM QUERY_IT.LIPS LIPS
            LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
            LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
            WHERE LIPS.MATNR IN (?, ?) AND LIPS.ERDAT >= ? AND LIPS.ERDAT < ? AND ((MARA.MFRPN LIKE ? ESCAPE '!') OR (MAKT.MAKTX LIKE ? ESCAPE '!')) \
            AND LIPS.VRKME <> ?""", r.sql());
        assertEquals(List.of("1", "M1", "M2", "20260101", "20260701", "%50!%%", "%50!%%", "KG"), r.parameters());
    }

    @Test
    void 值为空的字段不产生条件也不连接关系() {
        assertEquals("SELECT LIPS.VBELN AS \"delivery\"\nFROM QUERY_IT.LIPS LIPS",
            render(new DeliverySearch(List.of(), null, null, " ", null, null)).sql());
    }

    @Test
    void 条件对象与普通条件可以一起用() {
        String sql = from(Lips.class)
            .where(new DeliverySearch(null, null, null, null, "KG", null))
            .where(l -> l.quantity().gt(java.math.BigDecimal.ONE))
            .select(l -> l.delivery())
            .toSql(DIALECT).sql();
        assertEquals("SELECT LIPS.VBELN AS \"delivery\"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME <> ? AND LIPS.LFIMG > ?", sql);
    }

    @Test
    void 字段名找不到列时无论有没有值都报错() {
        record Bad(String plant) { }
        var e = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).where(new Bad(null)));
        assertEquals("Bad.plant: the query's tables have no plant", e.getMessage());
        record ToRelation(@Where("mara") String mara) { }
        e = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).where(new ToRelation("x")));
        assertEquals("ToRelation.mara: mara is a relation, not a column", e.getMessage());
        record RangeOfList(@Where(op = Where.Op.GE) List<String> unit) { }
        assertThrows(IllegalArgumentException.class, () -> from(Lips.class).where(new RangeOfList(List.of("EA"))));
    }

    @Test
    void contains等转义通配符且空值按skipEmpty处理() {
        Rendered r = from(Lips.class).skipEmpty()
            .where(l -> all(startsWith(l.unit(), "a_b"), endsWith(l.unit(), "c!"), contains(l.unit(), null)))
            .select(l -> l.delivery())
            .toSql(DIALECT);
        assertEquals("""
            SELECT LIPS.VBELN AS "delivery"
            FROM QUERY_IT.LIPS LIPS
            WHERE LIPS.VRKME LIKE ? ESCAPE '!' AND LIPS.VRKME LIKE ? ESCAPE '!'""", r.sql());
        assertEquals(List.of("a!_b%", "%c!!"), r.parameters());
        assertThrows(IllegalArgumentException.class, () -> from(Lips.class).where(l -> contains(l.unit(), null)));
    }
}

package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("原生 SQL 片段")
class NativeSqlTest {
    private static final TestDialect DIALECT = new TestDialect();

    /** 原生 SQL 数据源的一行：物料和数量合计。 */
    public record MaterialTotal(String material, BigDecimal total) { }

    @Test
    void 参数可以混用表达式和值且SELECT列表里的值写成字面量() {
        Rendered r = from(Lips.class)
            .where(l -> sql(String.class, "LPAD({0}, {1}, '0')", l.material(), 18).eq("000000000000000001"))
            .select(l -> sql(String.class, "LPAD({0}, {1}, '0')", l.material(), 18).as("padded"))
            .toSql(DIALECT);
        assertEquals("""
            SELECT LPAD(LIPS.MATNR, 18, '0') AS "padded"
            FROM QUERY_IT.LIPS LIPS
            WHERE LPAD(LIPS.MATNR, ?, '0') = ?""", r.sql());
        assertEquals(List.of(18, "000000000000000001"), r.parameters());
    }

    @Test
    void 集合参数展开成多个占位符() {
        Rendered r = from(Lips.class).where(l -> sqlCond("{0} IN ({1})", l.unit(), List.of("EA", "KG"))).select(l -> l.delivery()).toSql(DIALECT);
        assertEquals("SELECT LIPS.VBELN AS \"delivery\"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VRKME IN (?, ?)", r.sql());
        assertEquals(List.of("EA", "KG"), r.parameters());
    }

    @Test
    void 按方言选最接近的写法() {
        abstract class Other extends Dialect { }
        SqlExpr<String> e = sql(String.class, "DEFAULT({0})", lit("x"))
            .on(Other.class, "OTHER({0})")
            .on(Dialect.class, "ANY({0})")
            .on(TestDialect.class, "TEST({0})");
        assertEquals("SELECT TEST('x') AS \"value\"", from(Lips.class).select(l -> e).limit(1).toSql(DIALECT).sql().lines().findFirst().orElseThrow());
        SqlCond c = sqlCond("1 = 1").on(Dialect.class, "2 = 2");
        assertEquals("SELECT LIPS.VBELN AS \"delivery\"\nFROM QUERY_IT.LIPS LIPS\nWHERE 2 = 2",
            from(Lips.class).where(l -> c).select(l -> l.delivery()).toSql(DIALECT).sql());
    }

    @Test
    void sqlTable作为数据源并可与表连接() {
        var totals = sqlTable(MaterialTotal.class,
            "SELECT {0} AS {1}, SUM(LFIMG) AS {2} FROM QUERY_IT.LIPS WHERE VRKME = {3} GROUP BY {0}",
            identifier("MATNR"), identifier("material"), identifier("total"), "EA");
        Rendered r = from(totals)
            .join(Mara.class, (t, m) -> m.material().eq(t.col(MaterialTotal::material)))
            .where((t, m) -> t.col(MaterialTotal::total).gt(BigDecimal.ONE))
            .select((t, m) -> cols(m.partNumber(), t.col(MaterialTotal::total)))
            .toSql(DIALECT);
        assertEquals("""
            SELECT MARA.MFRPN AS "partNumber", T."total" AS "total"
            FROM (SELECT MATNR AS "material", SUM(LFIMG) AS "total" FROM QUERY_IT.LIPS WHERE VRKME = ? GROUP BY MATNR) T
            JOIN QUERY_IT.MARA MARA ON MARA.MATNR = T."material"
            WHERE T."total" > ?""", r.sql());
        assertEquals(List.of("EA", BigDecimal.ONE), r.parameters());
    }

    @Test
    void 空参数报错() {
        assertThrows(IllegalArgumentException.class, () -> sqlCond("{0} = {1}", lit(1), null));
        assertThrows(IllegalArgumentException.class, () -> sqlCond("{0} IN ({1})", lit(1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> identifier(" "));
    }
}

package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Scenarios;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.dpdns.zerodep.query.type.CastType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.dpdns.zerodep.query.hana.Hana.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HANA 渲染")
class HanaRenderTest {

    @Test
    void 别名折叠为大写不加引号() {
        String sql = Scenarios.lines().toSql(HanaDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT LIPS.VBELN AS \"delivery\""), sql);
        assertTrue(sql.contains("FROM QUERY_IT.LIPS LIPS\nLEFT JOIN QUERY_IT.MAKT MAKT"), sql);
    }

    @Test
    void 非ASCII字面量使用N前缀且类型转换用NVARCHAR() {
        String sql = from(Lips.class)
            .select(l -> caseWhen(l.unit().eq("EA"), lit("件")).otherwise(cast(l.unit(), CastType.text(10))))
            .toSql(HanaDialect.INSTANCE).sql();
        assertTrue(sql.contains("THEN N'件' ELSE CAST(LIPS.VRKME AS NVARCHAR(10)) END"), sql);
    }

    @Test
    void HANA函数与提示子句() {
        Query<Integer> q = from(Lips.class)
            .with(hint("NO_CS_JOIN"))
            .select(l -> daysBetween(lit(LocalDate.of(2026, 1, 1)), addDays(lit(LocalDate.of(2026, 1, 1)), 30)));
        assertEquals("SELECT DAYS_BETWEEN(DATE '2026-01-01', ADD_DAYS(DATE '2026-01-01', 30)) AS \"value\"\nFROM QUERY_IT.LIPS LIPS\n"
            + "WITH HINT(NO_CS_JOIN)", q.toSql(HanaDialect.INSTANCE).sql());
    }

    @Test
    void HANA专属函数和子句不能在别的方言上渲染() {
        Query<Integer> fn = from(Lips.class).select(l -> daysBetween(lit(LocalDate.of(2026, 1, 1)), lit(LocalDate.of(2026, 1, 2))));
        assertThrows(UnsupportedOperationException.class, () -> fn.toSql(new TestDialect()));
        Query<String> clause = from(Lips.class).with(hint("NO_CS_JOIN")).select(l -> l.delivery());
        assertThrows(UnsupportedOperationException.class, () -> clause.toSql(new TestDialect()));
    }
}

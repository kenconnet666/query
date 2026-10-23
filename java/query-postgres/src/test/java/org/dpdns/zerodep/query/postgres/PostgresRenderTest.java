package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.dpdns.zerodep.query.type.CastType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.dpdns.zerodep.query.postgres.Postgres.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PostgreSQL 渲染")
class PostgresRenderTest {

    @Test
    void 别名折叠为小写且大写表名加引号() {
        String sql = from(Lips.class).select(l -> l.delivery()).toSql(PostgresDialect.INSTANCE).sql();
        assertEquals("SELECT lips.\"VBELN\" AS delivery\nFROM \"QUERY_IT\".\"LIPS\" lips", sql);
    }

    @Test
    void DISTINCT_ON替代DISTINCT并放在SELECT之后() {
        String sql = from(Lips.class)
            .with(l -> distinctOn(l.material()))
            .distinct()
            .orderBy(l -> orders(l.material(), l.createdOn().desc()))
            .select(l -> l.delivery())
            .toSql(PostgresDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT DISTINCT ON (lips.\"MATNR\") lips.\"VBELN\" AS delivery"), sql);
    }

    @Test
    void 聚合FILTER与类型转换用NUMERIC() {
        String sql = from(Lips.class)
            .select(l -> filter(sum(cast(l.quantity(), CastType.decimal(15, 3))), l.material().eq("M1")))
            .toSql(PostgresDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT SUM(CAST(lips.\"LFIMG\" AS NUMERIC(15,3))) FILTER (WHERE lips.\"MATNR\" = 'M1')"), sql);
    }

    @Test
    void 含反斜杠的字面量写成E字符串以免受standard_conforming_strings影响() {
        String sql = from(Lips.class).select(l -> lit("a\\'; --")).toSql(PostgresDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT E'a\\\\''; --' AS value"), sql);
        String plain = from(Lips.class).select(l -> lit("O'Brien")).toSql(PostgresDialect.INSTANCE).sql();
        assertTrue(plain.startsWith("SELECT 'O''Brien' AS value"), plain);
    }

    @Test
    void PostgreSQL专属操作不能在别的方言上渲染() {
        Query<String> q = from(Lips.class).where(l -> ilike(l.unit(), "ea")).select(l -> l.delivery());
        assertThrows(UnsupportedOperationException.class, () -> q.toSql(new TestDialect()));
        Query<String> d = from(Lips.class).with(l -> distinctOn(l.material())).select(l -> l.delivery());
        assertThrows(UnsupportedOperationException.class, () -> d.toSql(new TestDialect()));
    }
}

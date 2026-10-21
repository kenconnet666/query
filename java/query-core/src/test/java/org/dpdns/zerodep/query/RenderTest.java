package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.testing.Erp.ClientEntity;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Scenarios;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("渲染")
class RenderTest {
    private static final Dialect DIALECT = new TestDialect();
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Test
    void CTE被提升到最外层且EXISTS子查询不带别名() {
        Rendered r = Scenarios.deliveries(START, END, null).toSql(DIALECT);
        assertEquals("""
            WITH "material_scope" AS (
            SELECT DISTINCT MARA.MANDT AS "client", MARA.MATNR AS "material", \
            CASE WHEN MARA.MFRPN <> TRIM(MARA.MFRPN) THEN TRIM(MARA.MFRPN) ELSE MARA.MFRPN END AS "partNumber"
            FROM QUERY_IT.MARA MARA
            WHERE MARA.MTART = ?
            )
            SELECT MATERIAL_SCOPE."partNumber" AS "partNumber", MAX(COALESCE(MAKT.MAKTX, MATERIAL_SCOPE."partNumber")) AS "name", \
            SUM(LIPS.LFIMG) AS "quantity"
            FROM "material_scope" MATERIAL_SCOPE
            JOIN QUERY_IT.LIPS LIPS ON LIPS.MANDT = MATERIAL_SCOPE."client" AND LIPS.MATNR = MATERIAL_SCOPE."material"
            LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = MATERIAL_SCOPE."material" AND MAKT.SPRAS = ?
            WHERE LIPS.ERDAT BETWEEN ? AND ? AND EXISTS (SELECT 1
            FROM QUERY_IT.VBKD VBKD
            WHERE VBKD.VBELN = LIPS.VGBEL)
            GROUP BY MATERIAL_SCOPE."partNumber"
            ORDER BY MATERIAL_SCOPE."partNumber" ASC""", r.sql());
        assertEquals(List.of("ZMAT", "1", "20260101", "20260630"), r.parameters());
    }

    @Test
    void 全局过滤器进入FROM表的WHERE和连接表的ON() {
        var db = new TestDatabase(c -> c.filter(ClientEntity.class, ref -> ref.col(ClientEntity::getClient).eq("100")));
        Rendered r = db.render(Scenarios.lines());
        assertEquals("""
            SELECT LIPS.VBELN AS "delivery", LIPS.MATNR AS "materialNo", LIPS.ERDAT AS "createdOn", MAKT.MAKTX AS "text"
            FROM QUERY_IT.LIPS LIPS
            LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.SPRAS = ? AND MAKT.MANDT = ?
            WHERE LIPS.MANDT = ?
            ORDER BY LIPS.VBELN ASC""", r.sql());
        assertEquals(List.of("1", "100", "100"), r.parameters());
    }

    @Test
    void 全局过滤器也作用于CTE和EXISTS子查询里的表() {
        var db = new TestDatabase(c -> c.filter(ClientEntity.class, ref -> ref.col(ClientEntity::getClient).eq("100")));
        String sql = db.render(Scenarios.deliveries(START, END, "MK1")).sql();
        assertTrue(sql.contains("WHERE MARA.MANDT = ? AND MARA.MTART = ? AND MARA.MFRNR = ?"), sql);
        assertTrue(sql.contains("WHERE VBKD.MANDT = ? AND VBKD.VBELN = LIPS.VGBEL"), sql);
    }

    @Test
    void 关闭过滤器后不再追加条件() {
        var db = new TestDatabase(c -> c.filter(ClientEntity.class, ref -> ref.col(ClientEntity::getClient).eq("100")));
        String sql = db.render(from(Lips.class).unfiltered().selectValue(l -> count())).sql();
        assertEquals("SELECT COUNT(*) AS \"value\"\nFROM QUERY_IT.LIPS LIPS", sql);
    }

    @Test
    void 泛型模板生成的UNION只在第一段写别名() {
        Rendered r = Scenarios.stock("2026").toSql(DIALECT);
        assertEquals("""
            SELECT MARDH.MATNR AS "material", MARDH.LFGJA AS "year", MARDH.LFMON AS "period", COALESCE(MARDH.LABST, 0) AS "quantity"
            FROM QUERY_IT.MARDH MARDH
            WHERE MARDH.LFGJA = ?
            UNION ALL
            SELECT MCHBH.MATNR, MCHBH.LFGJA, MCHBH.LFMON, COALESCE(MCHBH.CLABS, 0)
            FROM QUERY_IT.MCHBH MCHBH
            WHERE MCHBH.LFGJA = ?""", r.sql());
    }

    @Test
    void 自连接和关联子查询的别名在整条语句内唯一() {
        String sql = from(Mara.class)
            .join(Mara.class, (a, b) -> b.col(Mara::getPartNumber).eq(a, Mara::getPartNumber))
            .where((a, b) -> exists(from(Mara.class).where(c -> c.col(Mara::getMaterial).eq(b, Mara::getMaterial)).selectOne()))
            .selectValue((a, b) -> a.col(Mara::getMaterial))
            .toSql(DIALECT).sql();
        assertEquals("""
            SELECT MARA.MATNR AS "material"
            FROM QUERY_IT.MARA MARA
            JOIN QUERY_IT.MARA MARA2 ON MARA2.MFRPN = MARA.MFRPN
            WHERE EXISTS (SELECT 1
            FROM QUERY_IT.MARA MARA3
            WHERE MARA3.MATNR = MARA2.MATNR)""", sql);
    }

    @Test
    void 窗口函数和取排名第一的行() {
        String sql = Scenarios.latestDeliveries().toSql(DIALECT).sql();
        assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY LIPS.MATNR ORDER BY LIPS.ERDAT DESC, LIPS.VBELN DESC) AS \"rank\""), sql);
        assertTrue(sql.endsWith("WHERE RANKED_DELIVERY.\"rank\" = ?\nORDER BY RANKED_DELIVERY.\"material\" ASC"), sql);
    }

    @Test
    void IN列表超过方言上限时拆成多个IN() {
        String sql = from(Mara.class)
            .where(m -> m.col(Mara::getMaterial).in(List.of("1", "2", "3", "4", "5", "6", "7")))
            .selectValue(m -> m.col(Mara::getMaterial))
            .toSql(new TestDialect(3)).sql();
        assertTrue(sql.endsWith("WHERE (MARA.MATNR IN (?, ?, ?) OR MARA.MATNR IN (?, ?, ?) OR MARA.MATNR IN (?))"), sql);
        String negated = from(Mara.class)
            .where(m -> m.col(Mara::getMaterial).notIn(List.of("1", "2", "3", "4")))
            .selectValue(m -> m.col(Mara::getMaterial))
            .toSql(new TestDialect(3)).sql();
        assertTrue(negated.endsWith("WHERE (MARA.MATNR NOT IN (?, ?, ?) AND MARA.MATNR NOT IN (?))"), negated);
    }

    @Test
    void 空IN恒假而空NOT_IN恒真() {
        assertTrue(where(m -> m.col(Mara::getMaterial).in(List.of())).endsWith("WHERE 1 = 0"));
        assertTrue(where(m -> m.col(Mara::getMaterial).notIn(List.of())).endsWith("WHERE 1 = 1"));
    }

    @Test
    void 可选条件为空时整体省略() {
        String sql = where(m -> all(
            m.col(Mara::getMaterial).eqIfPresent(null),
            m.col(Mara::getMaterial).inIfPresent(List.of()),
            onlyIf(false, () -> m.col(Mara::getMaterial).eq("x")),
            ifPresent(null, v -> m.col(Mara::getMaterial).eq("x"))));
        assertEquals("SELECT MARA.MATNR AS \"material\"\nFROM QUERY_IT.MARA MARA", sql);
    }

    @Test
    void OR的每个分支都加括号() {
        String sql = where(m -> any(
            m.col(Mara::getMaterial).eq("A"),
            m.col(Mara::getPartNumber).eq("B").and(m.col(Mara::getManufacturer).eq("C"))));
        assertTrue(sql.endsWith("WHERE ((MARA.MATNR = ?) OR (MARA.MFRPN = ? AND MARA.MFRNR = ?))"), sql);
    }

    @Test
    void 分页的LIMIT和OFFSET() {
        String sql = from(Mara.class).orderBy(m -> orders(m.col(Mara::getMaterial).asc().nullsLast()))
            .selectValue(m -> m.col(Mara::getMaterial)).limit(10).offset(20).toSql(DIALECT).sql();
        assertTrue(sql.endsWith("ORDER BY MARA.MATNR ASC NULLS LAST\nLIMIT 10 OFFSET 20"), sql);
    }

    @Test
    void 取单行时只收紧不放宽已有的LIMIT() {
        Query<String> q = from(Mara.class).limit(1).selectValue(m -> m.col(Mara::getMaterial));
        assertSame(q, q.limitAtMost(2));
        assertTrue(q.limitAtMost(2).toSql(DIALECT).sql().endsWith("LIMIT 1"));
        assertTrue(from(Mara.class).selectValue(m -> m.col(Mara::getMaterial)).limitAtMost(2).toSql(DIALECT).sql().endsWith("LIMIT 2"));
    }

    @Test
    void 计数语句去掉不影响结果的排序() {
        Query<String> q = from(Mara.class).orderBy(m -> orders(m.col(Mara::getMaterial).asc())).selectValue(m -> m.col(Mara::getMaterial));
        assertEquals("SELECT COUNT(*) FROM (SELECT MARA.MATNR AS \"material\"\nFROM QUERY_IT.MARA MARA) COUNTED",
            QueryRenderer.count(new SqlWriter(DIALECT), q).sql());
    }

    @Test
    void 方言专属子句在别的方言上拒绝渲染() {
        Clause other = new Clause() {
            @Override public Position position() { return Position.END; }

            @Override public Class<? extends Dialect> dialect() { return OtherDialect.class; }

            @Override public void render(SqlWriter w) { w.sql("OTHER"); }
        };
        Query<String> q = from(Mara.class).with(other).selectValue(m -> m.col(Mara::getMaterial));
        var e = assertThrows(UnsupportedOperationException.class, () -> q.toSql(DIALECT));
        assertTrue(e.getMessage().contains("OtherDialect"), e.getMessage());
    }

    @Test
    void 方言专属函数在别的方言上拒绝渲染() {
        FunctionDef onlyOther = FunctionDef.only(OtherDialect.class, "OTHER_FN");
        Query<String> q = from(Mara.class).selectValue(m -> call(onlyOther, String.class, m.col(Mara::getMaterial)));
        var e = assertThrows(UnsupportedOperationException.class, () -> q.toSql(DIALECT));
        assertTrue(e.getMessage().contains("OTHER_FN is a OtherDialect function"), e.getMessage());
    }

    @Test
    void 数据库实例可以覆盖函数渲染() {
        FunctionDef onlyOther = FunctionDef.only(OtherDialect.class, "OTHER_FN");
        var db = new TestDatabase(c -> c.function(onlyOther, (w, args) -> w.sql("MINE(").join(args, ", ").sql(")")));
        String sql = db.render(from(Mara.class).selectValue(m -> call(onlyOther, String.class, m.col(Mara::getMaterial)))).sql();
        assertTrue(sql.startsWith("SELECT MINE(MARA.MATNR)"), sql);
    }

    @Test
    void 同一个查询在同一个数据库实例上只渲染一次() {
        var db = new TestDatabase(null);
        Query<String> q = from(Mara.class).selectValue(m -> m.col(Mara::getMaterial));
        assertSame(db.render(q), db.render(q));
        assertNotSame(db.render(q), new TestDatabase(null).render(q));
    }

    @Test
    void ROLLUP必须一次列出全部分组键() {
        assertThrows(IllegalStateException.class, () -> from(Lips.class)
            .groupBy(l -> keys(l.col(Lips::getMaterial))).groupByRollup(l -> keys(l.col(Lips::getUnit))));
        assertThrows(IllegalStateException.class, () -> from(Lips.class)
            .groupByRollup(l -> keys(l.col(Lips::getMaterial))).groupBy(l -> keys(l.col(Lips::getUnit))));
    }

    @Test
    void SELECT列表里的标量子查询仍然绑定WHERE参数() {
        Rendered r = from(Mara.class).select((row, m) -> java.util.Arrays.asList(
            row.get(scalar(from(Lips.class).where(l -> l.col(Lips::getMaterial).eq("M1")).selectValue(l -> count())).as("n")),
            row.get(m.col(Mara::getMaterial).coalesce("none").as("material")))).toSql(DIALECT);
        assertEquals("""
            SELECT (SELECT COUNT(*)
            FROM QUERY_IT.LIPS LIPS
            WHERE LIPS.MATNR = ?) AS "n", COALESCE(MARA.MATNR, 'none') AS "material"
            FROM QUERY_IT.MARA MARA""", r.sql());
        assertEquals(List.of("M1"), r.parameters());
    }

    @Test
    void 常量行每行一个字面量SELECT并用UNION_ALL连接() {
        record Code(String code, Integer sort) { }
        String sql = values(Code.class, List.of(new Code("A", 1), new Code("B", 2))).toSql(DIALECT).sql();
        assertEquals("""
            SELECT 'A' AS "code", 1 AS "sort"
            UNION ALL
            SELECT 'B', 2""", sql);
        var empty = assertThrows(IllegalArgumentException.class, () -> values(Code.class, List.of()));
        assertTrue(empty.getMessage().contains("at least one row"), empty.getMessage());
        var missing = assertThrows(IllegalArgumentException.class, () -> values(Code.class, List.of(new Code("A", null))));
        assertTrue(missing.getMessage().contains("Code.sort is null"), missing.getMessage());
    }

    @Test
    void CASE与类型化原生片段且SELECT列表里的常量写成字面量() {
        String sql = from(Mara.class).selectValue(m -> caseWhen(m.col(Mara::getMaterialType).eq("ZMAT"), "material")
            .when(m.col(Mara::getMaterialType).eq("ZOTH"), "other").otherwise(sql(String.class, "LOWER({0})", m.col(Mara::getMaterialType))))
            .toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT CASE WHEN MARA.MTART = 'ZMAT' THEN 'material' WHEN MARA.MTART = 'ZOTH' THEN 'other' ELSE LOWER(MARA.MTART) END"), sql);
    }

    private static String where(Fn.F1<Ref<Mara>, Cond> cond) {
        return from(Mara.class).where(cond).selectValue(m -> m.col(Mara::getMaterial)).toSql(DIALECT).sql();
    }

    static final class OtherDialect extends Dialect {
        @Override public String name() { return "other"; }

        @Override protected boolean isBare(String identifier) { return false; }

        @Override public String foldCase(String identifier) { return identifier; }
    }
}

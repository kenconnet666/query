package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.testing.Erp.Client;
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
    void 单个分组键和排序键不需要包装且裸表达式按升序排序() {
        Rendered wrapped = from(Mara.class)
            .groupBy(m -> keys(m.material()))
            .orderBy(m -> orders(m.material().asc()))
            .select(m -> m.material())
            .toSql(DIALECT);
        Rendered bare = from(Mara.class)
            .groupBy(m -> m.material())
            .orderBy(m -> m.material())
            .select(m -> m.material())
            .toSql(DIALECT);
        assertEquals(wrapped.sql(), bare.sql());
        String mixed = from(Lips.class).orderBy(l -> orders(l.material(), l.delivery().desc()))
            .select(l -> rowNumber().over(w -> w.orderBy(l.delivery()))).toSql(DIALECT).sql();
        assertTrue(mixed.contains("ROW_NUMBER() OVER (ORDER BY LIPS.VBELN ASC)") && mixed.endsWith("ORDER BY LIPS.MATNR ASC, LIPS.VBELN DESC"), mixed);
    }

    @Test
    void EXISTS直接接收作用域且列可以直接与另一张表的列比较() {
        String sql = from(Lips.class)
            .join(Mara.class, (l, m) -> m.material().eq(l.material()).and(m.material().ge(l.material())))
            .where((l, m) -> all(notExists(from(Mara.class).where(x -> x.material().lt(m.material()))),
                l.material().gt(m.material()).or(l.material().le(m.material()))))
            .select((l, m) -> l.delivery()).toSql(DIALECT).sql();
        assertTrue(sql.contains("MARA.MATNR >= LIPS.MATNR"), sql);
        assertTrue(sql.contains("NOT EXISTS (SELECT 1\nFROM QUERY_IT.MARA MARA2\nWHERE MARA2.MATNR < MARA.MATNR)"), sql);
        assertTrue(sql.contains("((LIPS.MATNR > MARA.MATNR) OR (LIPS.MATNR <= MARA.MATNR))"), sql);
    }

    @Test
    void 非有限的浮点数没有字面量形式() {
        Rendered r = from(Lips.class).select(l -> val(Double.NaN)).toSql(DIALECT);
        assertTrue(r.sql().startsWith("SELECT ? AS \"value\""), "SELECT-list constants stay parameters when no literal exists: " + r.sql());
        assertEquals(1, r.parameters().size());
        var e = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).select(l -> lit(Double.POSITIVE_INFINITY)).toSql(DIALECT));
        assertTrue(e.getMessage().contains("No literal form for Infinity"), e.getMessage());
    }

    @Test
    void 全局过滤器进入FROM表的WHERE和连接表的ON() {
        var db = new TestDatabase(c -> c.filter(Client.class, t -> t.client().eq("100")));
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
        var db = new TestDatabase(c -> c.filter(Client.class, t -> t.client().eq("100")));
        String sql = db.render(Scenarios.deliveries(START, END, "MK1")).sql();
        assertTrue(sql.contains("WHERE MARA.MANDT = ? AND MARA.MTART = ? AND MARA.MFRNR = ?"), sql);
        assertTrue(sql.contains("WHERE VBKD.MANDT = ? AND VBKD.VBELN = LIPS.VGBEL"), sql);
    }

    @Test
    void 关闭过滤器后不再追加条件() {
        var db = new TestDatabase(c -> c.filter(Client.class, t -> t.client().eq("100")));
        String sql = db.render(from(Lips.class).unfiltered().select(l -> count())).sql();
        assertEquals("SELECT COUNT(*) AS \"value\"\nFROM QUERY_IT.LIPS LIPS", sql);
    }

    @Test
    void 泛型模板生成的UNION只在第一段写别名且子接口可以改列名() {
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
            .join(Mara.class, (a, b) -> b.partNumber().eq(a.partNumber()))
            .where((a, b) -> exists(from(Mara.class).where(c -> c.material().eq(b.material()))))
            .select((a, b) -> a.material())
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
            .where(m -> m.material().in(List.of("1", "2", "3", "4", "5", "6", "7")))
            .select(m -> m.material())
            .toSql(new TestDialect(3)).sql();
        assertTrue(sql.endsWith("WHERE (MARA.MATNR IN (?, ?, ?) OR MARA.MATNR IN (?, ?, ?) OR MARA.MATNR IN (?))"), sql);
        String negated = from(Mara.class)
            .where(m -> m.material().notIn(List.of("1", "2", "3", "4")))
            .select(m -> m.material())
            .toSql(new TestDialect(3)).sql();
        assertTrue(negated.endsWith("WHERE (MARA.MATNR NOT IN (?, ?, ?) AND MARA.MATNR NOT IN (?))"), negated);
    }

    @Test
    void 空IN恒假而空NOT_IN恒真() {
        assertTrue(where(m -> m.material().in(List.of())).endsWith("WHERE 1 = 0"));
        assertTrue(where(m -> m.material().notIn(List.of())).endsWith("WHERE 1 = 1"));
    }

    @Test
    void skipEmpty省略与空值比较的条件() {
        String none = null;
        Rendered r = from(Mara.class).skipEmpty()
            .where(m -> all(
                m.material().eq(none),
                m.material().ne("  "),
                m.material().in(List.of()),
                m.material().notIn(List.of()),
                like(m.partNumber(), none),
                m.material().between("A", none),
                m.manufacturer().gt("M"),
                onlyIf(false, () -> m.material().eq("x")),
                ifPresent(none, v -> m.material().eq(v))))
            .select(m -> m.material())
            .toSql(DIALECT);
        assertTrue(r.sql().endsWith("WHERE MARA.MATNR >= ? AND MARA.MFRNR > ?"), r.sql());
        assertEquals(List.of("A", "M"), r.parameters());
    }

    @Test
    void skipEmpty也作用于连接条件和子查询且不开启时与null比较报错() {
        String none = null;
        String sql = from(Lips.class).skipEmpty()
            .join(Mara.class, (l, m) -> all(m.material().eq(l.material()), m.manufacturer().eq(none)))
            .where((l, m) -> exists(from(Mara.class).where(x -> all(x.material().eq(l.material()), x.materialType().eq(none)))))
            .select((l, m) -> l.delivery()).toSql(DIALECT).sql();
        assertTrue(sql.contains("JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR\n"), sql);
        assertTrue(sql.endsWith("WHERE MARA2.MATNR = LIPS.MATNR)"), sql);
        var e = assertThrows(IllegalArgumentException.class, () -> from(Mara.class).where(m -> m.material().eq(none)));
        assertTrue(e.getMessage().contains("skipEmpty()"), e.getMessage());
    }

    @Test
    void OR的每个分支都加括号() {
        String sql = where(m -> any(
            m.material().eq("A"),
            m.partNumber().eq("B").and(m.manufacturer().eq("C"))));
        assertTrue(sql.endsWith("WHERE ((MARA.MATNR = ?) OR (MARA.MFRPN = ? AND MARA.MFRNR = ?))"), sql);
    }

    @Test
    void 分页的LIMIT和OFFSET() {
        String sql = from(Mara.class).orderBy(m -> m.material().asc().nullsLast())
            .select(m -> m.material()).limit(10).offset(20).toSql(DIALECT).sql();
        assertTrue(sql.endsWith("ORDER BY MARA.MATNR ASC NULLS LAST\nLIMIT 10 OFFSET 20"), sql);
    }

    @Test
    void 取单行时只收紧不放宽已有的LIMIT() {
        Query<String> q = from(Mara.class).limit(1).select(m -> m.material());
        assertSame(q, q.limitAtMost(2));
        assertTrue(q.limitAtMost(2).toSql(DIALECT).sql().endsWith("LIMIT 1"));
        assertTrue(from(Mara.class).select(m -> m.material()).limitAtMost(2).toSql(DIALECT).sql().endsWith("LIMIT 2"));
    }

    @Test
    void 计数语句去掉不影响结果的排序() {
        Query<String> q = from(Mara.class).orderBy(m -> m.material()).select(m -> m.material());
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
        Query<String> q = from(Mara.class).with(other).select(m -> m.material());
        var e = assertThrows(UnsupportedOperationException.class, () -> q.toSql(DIALECT));
        assertTrue(e.getMessage().contains("OtherDialect"), e.getMessage());
    }

    @Test
    void 方言专属函数在别的方言上拒绝渲染() {
        FunctionDef onlyOther = FunctionDef.only(OtherDialect.class, "OTHER_FN");
        Query<String> q = from(Mara.class).select(m -> call(onlyOther, String.class, m.material()));
        var e = assertThrows(UnsupportedOperationException.class, () -> q.toSql(DIALECT));
        assertTrue(e.getMessage().contains("OTHER_FN is a OtherDialect function"), e.getMessage());
    }

    @Test
    void 数据库实例可以覆盖函数渲染() {
        FunctionDef onlyOther = FunctionDef.only(OtherDialect.class, "OTHER_FN");
        var db = new TestDatabase(c -> c.function(onlyOther, (w, args) -> w.sql("MINE(").join(args, ", ").sql(")")));
        String sql = db.render(from(Mara.class).select(m -> call(onlyOther, String.class, m.material()))).sql();
        assertTrue(sql.startsWith("SELECT MINE(MARA.MATNR)"), sql);
    }

    @Test
    void 同一个查询在同一个数据库实例上只渲染一次() {
        var db = new TestDatabase(null);
        Query<String> q = from(Mara.class).select(m -> m.material());
        assertSame(db.render(q), db.render(q));
        assertNotSame(db.render(q), new TestDatabase(null).render(q));
    }

    @Test
    void 只有混用UNION与UNION_ALL的右侧组合才加括号() {
        java.util.function.Supplier<Query<String>> arm = () -> from(Mara.class).select(m -> m.material());
        java.util.function.Function<Query<String>, String> skeleton = q -> q.toSql(DIALECT).sql().replaceAll("SELECT[^\n]*\nFROM[^\n)]*", "S");
        assertEquals("S\nUNION\n(S\nUNION ALL\n(S\nUNION\nS))", skeleton.apply(arm.get().union(arm.get().unionAll(arm.get().union(arm.get())))));
        assertEquals("S\nUNION ALL\nS\nUNION ALL\nS", skeleton.apply(arm.get().unionAll(arm.get().unionAll(arm.get()))));
        assertEquals("S\nUNION\nS\nUNION ALL\nS", skeleton.apply(arm.get().union(arm.get()).unionAll(arm.get())));
    }

    @Test
    void ROLLUP必须一次列出全部分组键() {
        assertThrows(IllegalStateException.class, () -> from(Lips.class)
            .groupBy(l -> l.material()).groupByRollup(l -> l.unit()));
        assertThrows(IllegalStateException.class, () -> from(Lips.class)
            .groupByRollup(l -> l.material()).groupBy(l -> l.unit()));
    }

    @Test
    void SELECT列表里的标量子查询仍然绑定WHERE参数() {
        Rendered r = from(Mara.class).select(m -> cols(
            scalar(from(Lips.class).where(l -> l.material().eq("M1")).select(l -> count())).as("n"),
            m.material().coalesce("none"))).toSql(DIALECT);
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
        String sql = from(Mara.class).select(m -> caseWhen(m.materialType().eq("ZMAT"), "material")
            .when(m.materialType().eq("ZOTH"), "other").otherwise(sql(String.class, "LOWER({0})", m.materialType())))
            .toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT CASE WHEN MARA.MTART = 'ZMAT' THEN 'material' WHEN MARA.MTART = 'ZOTH' THEN 'other' ELSE LOWER(MARA.MTART) END"), sql);
    }

    /** 并发测试用的表。 */
    @Table("PROBE")
    interface ConcurrencyProbe extends Entity {
        /** 编号。 */
        @Column("ID") Col<String> id();

        /** 名称。 */
        @Column("NAME") Col<String> name();
    }

    /** PROBE 的一行。 */
    record ProbeRow(String id, String name) { }

    @Test
    void 多线程同时首次渲染得到相同的结果() throws Exception {
        TestDatabase db = new TestDatabase(null);
        Query<ProbeRow> q = from(ConcurrencyProbe.class).where(p -> p.id().eq("1")).selectAs(ProbeRow.class);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Rendered>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) {
                boolean viaDatabase = i % 2 == 0;
                results.add(pool.submit(() -> {
                    start.await();
                    return viaDatabase ? db.render(q) : q.toSql(DIALECT);
                }));
            }
            start.countDown();
            java.util.Set<String> sqls = new java.util.HashSet<>();
            for (var f : results) sqls.add(f.get().sql() + f.get().parameters());
            assertEquals(1, sqls.size(), sqls::toString);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 原生条件片段与当前日期时间() {
        String sql = from(Mara.class)
            .where(m -> sqlCond("{0} LIKE {1}", m.material(), val("M%")))
            .select(m -> cols(currentDate().as("today"), currentTimestamp().as("now")))
            .toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT CURRENT_DATE AS \"today\", CURRENT_TIMESTAMP AS \"now\""), sql);
        assertTrue(sql.endsWith("WHERE MARA.MATNR LIKE ?"), sql);
    }

    @Test
    void 作用域可以连接十六张表() {
        String sql = from(Mara.class)
            .join(Mara.class, (a, b) -> b.material().eq(a.material())).join(Mara.class, (a, b, c) -> c.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d) -> d.material().eq(a.material())).join(Mara.class, (a, b, c, d, e) -> e.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f) -> f.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g) -> g.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h) -> h.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i) -> i.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i, j) -> j.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i, j, k) -> k.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i, j, k, l) -> l.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i, j, k, l, m) -> m.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i, j, k, l, m, n) -> n.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o) -> o.material().eq(a.material()))
            .join(Mara.class, (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p) -> p.material().eq(a.material()))
            .select((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p) -> p.material())
            .toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT MARA16.MATNR AS \"material\""), sql);
        assertTrue(sql.contains("JOIN QUERY_IT.MARA MARA16 ON MARA16.MATNR = MARA.MATNR"), sql);
    }

    private static String where(Fn.F1<Mara, Cond> cond) {
        return from(Mara.class).where(cond).select(m -> m.material()).toSql(DIALECT).sql();
    }

    /** 只用来验证方言专属能力在别的方言上被拒绝。 */
    static final class OtherDialect extends Dialect {
        @Override public String name() { return "other"; }

        @Override protected boolean isBare(String identifier) { return false; }

        @Override public String foldCase(String identifier) { return identifier; }
    }
}

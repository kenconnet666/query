package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.mysql.MysqlTables.Article;
import org.dpdns.zerodep.query.mysql.MysqlTables.PartLog;
import org.dpdns.zerodep.query.mysql.MysqlTables.PartLogRow;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Step;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.mysql.Mysql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Needs QUERY_MYSQL_URL, QUERY_MYSQL_USER and QUERY_MYSQL_PASSWORD; skipped otherwise. */
@DisplayName("MySQL 实库场景")
public class MysqlLiveTest extends LiveScenarios {

    @Override protected LiveDatabase connect() { return LiveDatabase.fromEnvironment("MYSQL"); }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Mysql.database(connections, configure);
    }

    @Override protected String text(int length) { return "VARCHAR(" + length + ")"; }

    /** SLEEP() interrupted by max_execution_time returns 1 instead of failing, so this counts a billion rows instead. */
    @Override protected String slowStatement() {
        String thousand = "(SELECT 1 FROM information_schema.columns LIMIT 1000)";
        return "SELECT COUNT(*) FROM " + thousand + " a, " + thousand + " b, " + thousand + " c";
    }

    @Override protected String q(String identifier) { return "`" + identifier + "`"; }

    /** TIMESTAMP converts through the session time zone and ends in 2038; DATETIME is a plain local value. */
    @Override protected String timestamp() { return "DATETIME(6)"; }

    /** MySQL 没有 IDENTITY，用 AUTO_INCREMENT，它要求是一个键。 */
    @Override protected String identity() { return "INT AUTO_INCREMENT PRIMARY KEY"; }

    @Test
    void MySQL日期函数与GROUP_CONCAT() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        int days = db.one(from(Lips.class).limit(1).select(l -> daysBetween(lit(day), lit(LocalDate.of(2026, 2, 1)))));
        assertEquals(31, days);
        List<String> rows = db.list(from(Lips.class)
            .with(maxExecutionTime(5000))
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> row((m, d) -> m + "=" + d, l.material(), stringAgg(l.delivery(), ",", l.delivery().asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }

    @Test
    void 原生SQL不在反引号和反斜杠转义的字符串里找参数() {
        List<String> rows = db.sql("SELECT CONCAT('it\\'s :no', :x) AS `v:w`").bind("x", "!").list(String.class);
        assertEquals(List.of("it's :no!"), rows);
    }

    @Test
    void GREATEST与LEAST遇到NULL时返回NULL() {
        List<String> rows = db.list(from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> row((d, high, low) -> d + ":" + plain(high) + ":" + plain(low),
                l.delivery(), greatest(l.quantity(), val(new BigDecimal("4"))), least(l.quantity(), val(new BigDecimal("4"))))));
        assertEquals(List.of("D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"), rows);
        assertNull(db.one(from(Lips.class).where(l -> l.delivery().eq("D1"))
            .select(l -> greatest(l.quantity(), nullOf(BigDecimal.class)))));
    }

    @Test
    void INTERSECT_ALL与EXCEPT_ALL按次数保留重复的行() {
        Query<String> each = from(Lips.class).where(l -> l.unit().eq("EA")).select(l -> l.material());
        Query<String> master = from(Mara.class).select(m -> m.material());
        assertEquals(List.of("M1", "M2"), db.list(intersectAll(each, master)).stream().sorted().toList());
        assertEquals(List.of("M1", "M1", "M2"), db.list(exceptAll(each, master)).stream().sorted().toList());
    }

    @Test
    void RANGE窗口帧按排序值的距离取行() {
        assertEquals(List.of("1:1:1", "2:1:2"), db.list(from(Event.class)
                .orderBy(e -> e.id())
                .select(e -> cols(e.id(),
                    count().over(w -> w.orderBy(e.onDay()).frame(range(daysPreceding(7), Bound.currentRow()))).as("lastWeek"),
                    count().over(w -> w.orderBy(e.id()).frame(range(Bound.preceding(1), Bound.following(0)))).as("near"))))
            .stream().map(r -> r.get("id") + ":" + r.get("lastWeek") + ":" + r.get("near")).toList());
    }

    @Test
    void 递归CTE逐层展开到终止条件() {
        Cte<Step> steps = recursiveCte("steps",
            from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> cols(l.delivery(), lit(1).as("depth"))).into(Step.class),
            self -> from(self).where(s -> s.col(Step::depth).lt(3))
                .select(s -> cols(s.col(Step::delivery), plus(s.col(Step::depth), lit(1)).as("depth"))).into(Step.class));
        assertEquals(List.of(1, 2, 3), db.list(from(steps).orderBy(s -> s.col(Step::depth)).select(s -> s.col(Step::depth))));
    }

    @Test
    void 共享锁与只锁部分表的加锁读() {
        db.transaction(() -> {
            assertEquals(5, db.list(from(Lips.class).join(Mara.class, (l, m) -> m.material().eq(l.material()))
                .with((l, m) -> forUpdateOf(Lock.SKIP_LOCKED, l)).select((l, m) -> l.delivery())).size());
            assertEquals(5, db.list(from(Lips.class).with(forShare(Lock.NOWAIT)).select(l -> l.delivery())).size());
        });
    }

    @Test
    void MySQL没有FULL_JOIN() {
        assertThrows(UnsupportedOperationException.class, () -> db.list(from(Lips.class).unfiltered()
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material())).select((l, m) -> l.delivery())));
    }

    /** 建 JSON 和全文检索用的表。 */
    private void createArticles() throws java.sql.SQLException {
        live.execute(List.of("-DROP TABLE `QUERY_IT`.`MY_ARTICLE`",
            "CREATE TABLE `QUERY_IT`.`MY_ARTICLE` (`ID` INTEGER, `DOC` JSON, `TITLE` VARCHAR(100), `BODY` TEXT, FULLTEXT ft_article (`TITLE`, `BODY`)) ENGINE=InnoDB",
            "INSERT INTO `QUERY_IT`.`MY_ARTICLE` VALUES (1, '{\"status\":\"open\",\"owner\":{\"name\":\"ann\"}}', 'pump manual', 'the pump is running')",
            "INSERT INTO `QUERY_IT`.`MY_ARTICLE` VALUES (2, '{\"status\":\"closed\"}', 'valve notes', 'valve without leak')",
            "INSERT INTO `QUERY_IT`.`MY_ARTICLE` VALUES (3, '{\"status\":\"open\"}', 'valve report', 'valve leak found')"));
    }

    @Test
    void 优化器提示合并进一个注释() {
        List<String> rows = db.list(from(Lips.class).join(Mara.class, (l, m) -> m.material().eq(l.material()))
            .with((l, m) -> maxExecutionTime(5000)).with((l, m) -> noIndex(m)).orderBy((l, m) -> l.delivery()).select((l, m) -> l.delivery()));
        assertEquals(List.of("D1", "D2", "D3", "D4", "D5"), rows);
    }

    @Test
    void UPDATE与DELETE带优化器提示() {
        assertThrows(IllegalStateException.class, () -> db.transaction(() -> {
            assertEquals(1L, db.execute(update(Lips.class).with(l -> noIndex(l)).set(l -> l.unit().to("KG")).where(l -> l.delivery().eq("D1"))));
            assertEquals("KG", db.one(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> l.unit())));
            assertEquals(0L, db.execute(deleteFrom(Lips.class).with(l -> noIndex(l)).where(l -> l.delivery().eq("D9"))));
            throw new IllegalStateException("roll back the change");
        }));
        assertEquals("EA", db.one(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> l.unit())));
    }

    @Test
    void JSON的包含与路径文本() throws java.sql.SQLException {
        createArticles();
        List<Map<String, Object>> rows = db.list(from(Article.class).where(a -> jsonContains(a.doc(), "{\"status\":\"open\"}")).orderBy(a -> a.id())
            .select(a -> cols(a.id(), jsonText(a.doc(), "$.owner.name").as("owner"))));
        assertEquals(List.of("1:ann", "3:null"), rows.stream().map(r -> r.get("id") + ":" + r.get("owner")).toList());
    }

    @Test
    void 全文检索的自然语言与布尔模式() throws java.sql.SQLException {
        createArticles();
        assertEquals(List.of(1), db.list(from(Article.class).where(a -> match("pump", a.title(), a.body())).select(a -> a.id())));
        assertEquals(List.of(2, 3), db.list(from(Article.class).where(a -> matchBoolean("+valve", a.title(), a.body())).orderBy(a -> a.id()).select(a -> a.id())));
        assertEquals(List.of(2), db.list(from(Article.class).where(a -> matchBoolean("+valve -found", a.title(), a.body())).select(a -> a.id())));
        assertEquals(1, db.list(from(Article.class).orderBy(a -> orders(matchScore("pump", a.title(), a.body()).desc(), a.id())).select(a -> a.id())).get(0));
    }

    @Test
    void 按TableOption建原生分区表且查询不变() throws SQLException {
        live.execute(List.of("-DROP TABLE QUERY_IT.PART_LOG"));
        db.schema().plan(PartLog.class).apply();
        db.execute(insertInto(PartLog.class).values(new PartLogRow(1L, LocalDate.of(2025, 6, 1), BigDecimal.ONE), new PartLogRow(2L, LocalDate.of(2026, 6, 1), BigDecimal.TEN)));
        assertEquals(List.of(2L), db.list(from(PartLog.class).where(p -> p.day().ge(LocalDate.of(2026, 1, 1))).select(p -> p.id())));
        try (Connection c = live.open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = 'QUERY_IT' AND TABLE_NAME = 'PART_LOG'")) {
            rs.next();
            assertEquals(3, rs.getInt(1), "partitions of PART_LOG");
        }
    }
}

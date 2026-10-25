package org.dpdns.zerodep.query.it.dialect;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.TableOption;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.mysql.MysqlDialect;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.it.support.Db.MYSQL;
import static org.dpdns.zerodep.query.it.support.Db.POLARDB;
import static org.dpdns.zerodep.query.mysql.Mysql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MySQL 专属的写法（{@code Mysql} 门面），PolarDB 兼容 MySQL，所以两个库都跑：DATEDIFF、GROUP_CONCAT、反引号与反斜杠转义的原生 SQL、
 * NULL 时为 NULL 的 GREATEST、INTERSECT ALL、RANGE 窗口帧、递归 CTE、只锁部分表、优化器提示、JSON、全文检索和分区表。
 * 数据见 {@link SampleData}。
 */
@LiveTest(only = {MYSQL, POLARDB})
@DisplayName("MySQL 专属写法")
class MysqlTest {
    @Parameter Live live;

    /** 带 JSON 文档、标题和正文的文章；表是下面手写的，带全文索引。 */
    @Table(schema = "QUERY_IT", value = "MY_ARTICLE")
    interface Article extends Entity {
        /** 编号。 */
        @Column("ID") Col<Integer> id();

        /** JSON 文档，按文本读写。 */
        @Column("DOC") Col<String> doc();

        /** 标题。 */
        @Column("TITLE") Col<String> title();

        /** 正文。 */
        @Column("BODY") Col<String> body();
    }

    /** 按日期范围分区的日志：分区键 DAY 必须在主键里。 */
    @Table(schema = "QUERY_IT", value = "PART_LOG")
    @TableOption(dialect = MysqlDialect.class, value = "PARTITION BY RANGE COLUMNS(`DAY`) (PARTITION p2025 VALUES LESS THAN ('2026-01-01'), "
        + "PARTITION p2026 VALUES LESS THAN ('2027-01-01'), PARTITION pmax VALUES LESS THAN (MAXVALUE))")
    interface PartLog extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 日期，分区键。 */
        @Id @Column("DAY") Col<LocalDate> day();

        /** 金额。 */
        @Column(value = "AMOUNT", precision = 10, scale = 2) Col<BigDecimal> amount();
    }

    record PartLogRow(Long id, LocalDate day, BigDecimal amount) { }

    /** JSON 和全文检索用的 MY_ARTICLE：三篇文章。 */
    @BeforeParameterizedClassInvocation
    static void tables(Live live) {
        live.execute("-DROP TABLE `QUERY_IT`.`MY_ARTICLE`",
            "CREATE TABLE `QUERY_IT`.`MY_ARTICLE` (`ID` INTEGER, `DOC` JSON, `TITLE` VARCHAR(100), `BODY` TEXT, FULLTEXT ft_article (`TITLE`, `BODY`)) ENGINE=InnoDB",
            "INSERT INTO `QUERY_IT`.`MY_ARTICLE` VALUES (1, '{\"status\":\"open\",\"owner\":{\"name\":\"ann\"}}', 'pump manual', 'the pump is running')",
            "INSERT INTO `QUERY_IT`.`MY_ARTICLE` VALUES (2, '{\"status\":\"closed\"}', 'valve notes', 'valve without leak')",
            "INSERT INTO `QUERY_IT`.`MY_ARTICLE` VALUES (3, '{\"status\":\"open\"}', 'valve report', 'valve leak found')");
    }

    @Test
    void DATEDIFF与GROUP_CONCAT() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        assertEquals(31, (int) live.db().one(from(Lips.class).limit(1).select(l -> daysBetween(lit(day), lit(LocalDate.of(2026, 2, 1))))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), live.db().list(from(Lips.class)
            .with(maxExecutionTime(5000))
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> row((m, d) -> m + "=" + d, l.material(), stringAgg(l.delivery(), ",", l.delivery().asc())))));
    }

    @Test
    void 原生SQL不在反引号和反斜杠转义的字符串里找参数() {
        assertEquals(List.of("it's :no!"), live.db().sql("SELECT CONCAT('it\\'s :no', :x) AS `v:w`").bind("x", "!").list(String.class));
    }

    @Test
    void GREATEST与LEAST遇到NULL时返回NULL() {
        assertEquals(List.of("D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"), live.db().list(from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> row((d, high, low) -> d + ":" + plain(high) + ":" + plain(low),
                l.delivery(), greatest(l.quantity(), val(new BigDecimal("4"))), least(l.quantity(), val(new BigDecimal("4")))))));
        assertNull(live.db().one(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> greatest(l.quantity(), nullOf(BigDecimal.class)))),
            "PostgreSQL 跳过 NULL，HANA 和 MySQL 返回 NULL");
    }

    @Test
    void INTERSECT_ALL与EXCEPT_ALL按次数保留重复的行() {
        Query<String> each = from(Lips.class).where(l -> l.unit().eq("EA")).select(l -> l.material());   // M1 三次、M2 两次
        Query<String> master = from(Mara.class).select(m -> m.material());                               // M1 到 M4 各一次
        assertEquals(List.of("M1", "M2"), live.db().list(intersectAll(each, master)).stream().sorted().toList());
        assertEquals(List.of("M1", "M1", "M2"), live.db().list(exceptAll(each, master)).stream().sorted().toList());
    }

    @Test
    void RANGE窗口帧按排序值的距离取行() {
        assertEquals(List.of("1:1:1", "2:1:2"), live.db().list(from(Event.class)
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
        assertEquals(List.of(1, 2, 3), live.db().list(from(steps).orderBy(s -> s.col(Step::depth)).select(s -> s.col(Step::depth))));
    }

    @Test
    void 共享锁与只锁部分表的加锁读() {
        live.db().transaction(() -> {
            assertEquals(5, live.db().list(from(Lips.class).join(Mara.class, (l, m) -> m.material().eq(l.material()))
                .with((l, m) -> forUpdateOf(Lock.SKIP_LOCKED, l)).select((l, m) -> l.delivery())).size());
            assertEquals(5, live.db().list(from(Lips.class).with(forShare(Lock.NOWAIT)).select(l -> l.delivery())).size());
        });
    }

    @Test
    void MySQL没有FULL_JOIN() {
        assertThrows(UnsupportedOperationException.class, () -> live.db().list(from(Lips.class).unfiltered()
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material())).select((l, m) -> l.delivery())));
    }

    @Test
    void 优化器提示合并进一个注释() {
        assertEquals(List.of("D1", "D2", "D3", "D4", "D5"), live.db().list(from(Lips.class).join(Mara.class, (l, m) -> m.material().eq(l.material()))
            .with((l, m) -> maxExecutionTime(5000)).with((l, m) -> noIndex(m)).orderBy((l, m) -> l.delivery()).select((l, m) -> l.delivery())));
    }

    @Test
    void UPDATE与DELETE带优化器提示() {
        // 在事务里改共享的样例数据，再回滚。
        assertThrows(IllegalStateException.class, () -> live.db().transaction(() -> {
            assertEquals(1L, live.db().execute(update(Lips.class).with(l -> noIndex(l)).set(l -> l.unit().to("KG")).where(l -> l.delivery().eq("D1"))));
            assertEquals("KG", live.db().one(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> l.unit())));
            assertEquals(0L, live.db().execute(deleteFrom(Lips.class).with(l -> noIndex(l)).where(l -> l.delivery().eq("D9"))));
            throw new IllegalStateException("roll back the change");
        }));
        assertEquals("EA", live.db().one(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> l.unit())));
    }

    @Test
    void JSON的包含与路径文本() {
        List<Map<String, Object>> rows = live.db().list(from(Article.class).where(a -> jsonContains(a.doc(), "{\"status\":\"open\"}")).orderBy(a -> a.id())
            .select(a -> cols(a.id(), jsonText(a.doc(), "$.owner.name").as("owner"))));
        assertEquals(List.of("1:ann", "3:null"), rows.stream().map(r -> r.get("id") + ":" + r.get("owner")).toList());
    }

    @Test
    void 全文检索的自然语言与布尔模式() {
        assertEquals(List.of(1), live.db().list(from(Article.class).where(a -> match("pump", a.title(), a.body())).select(a -> a.id())));
        assertEquals(List.of(2, 3), live.db().list(from(Article.class).where(a -> matchBoolean("+valve", a.title(), a.body())).orderBy(a -> a.id()).select(a -> a.id())));
        assertEquals(List.of(2), live.db().list(from(Article.class).where(a -> matchBoolean("+valve -found", a.title(), a.body())).select(a -> a.id())));
        assertEquals(1, live.db().list(from(Article.class).orderBy(a -> orders(matchScore("pump", a.title(), a.body()).desc(), a.id())).select(a -> a.id())).get(0));
    }

    @Test
    void 按TableOption建原生分区表且查询不变() {
        live.recreate(PartLog.class);
        live.db().execute(insertInto(PartLog.class).values(new PartLogRow(1L, LocalDate.of(2025, 6, 1), BigDecimal.ONE), new PartLogRow(2L, LocalDate.of(2026, 6, 1), BigDecimal.TEN)));
        assertEquals(List.of(2L), live.db().list(from(PartLog.class).where(p -> p.day().ge(LocalDate.of(2026, 1, 1))).select(p -> p.id())));
        assertEquals(3L, live.db().sql("SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA = 'QUERY_IT' AND TABLE_NAME = 'PART_LOG'").one(Long.class),
            "partitions of PART_LOG");
    }

    /** 小数去掉末尾的零。 */
    private static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }
}

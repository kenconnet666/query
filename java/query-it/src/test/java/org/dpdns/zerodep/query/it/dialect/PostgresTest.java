package org.dpdns.zerodep.query.it.dialect;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.TableOption;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.postgres.PostgresDatabase;
import org.dpdns.zerodep.query.postgres.PostgresDialect;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Step;
import org.dpdns.zerodep.query.testing.Writes.Note;
import org.dpdns.zerodep.query.testing.Writes.NoteRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.dpdns.zerodep.query.it.support.Db.POSTGRES;
import static org.dpdns.zerodep.query.postgres.Postgres.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PostgreSQL 专属的写法（{@code Postgres} 门面）和它的驱动行为：DISTINCT ON、ILIKE、分组集合、跳过 NULL 的 GREATEST、
 * INTERSECT ALL、RANGE 窗口帧、递归 CTE、只锁部分表、FULL JOIN、百分位数与众数、RETURNING、数组、JSONB、全文检索、分区表，
 * 以及游标式的流式读取和美元引号里的原生 SQL。数据见 {@link SampleData}。
 */
@LiveTest(only = POSTGRES)
@DisplayName("PostgreSQL 专属写法")
class PostgresTest {
    @Parameter Live live;

    /** 带标签数组、JSONB 文档和正文的行；表是下面手写的。 */
    @Table(schema = "QUERY_IT", value = "PG_TAGGED")
    interface Tagged extends Entity {
        /** 编号。 */
        @Column("ID") Col<Integer> id();

        /** 标签，text[]。 */
        @Column("TAGS") Col<String[]> tags();

        /** JSONB 文档，按文本读写。 */
        @Column("DOC") Col<String> doc();

        /** 正文。 */
        @Column("BODY") Col<String> body();
    }

    /** 按日期范围分区的日志：分区键 DAY 必须在主键里；分区本身要另外建。 */
    @Table(schema = "QUERY_IT", value = "PART_LOG")
    @TableOption(dialect = PostgresDialect.class, value = "PARTITION BY RANGE (\"DAY\")")
    interface PartLog extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 日期，分区键。 */
        @Id @Column("DAY") Col<LocalDate> day();

        /** 金额。 */
        @Column(value = "AMOUNT", precision = 10, scale = 2) Col<BigDecimal> amount();
    }

    record PartLogRow(Long id, LocalDate day, BigDecimal amount) { }

    /** 数组、JSONB 和全文检索用的 PG_TAGGED（三行），RETURNING 用的便签表。 */
    @BeforeParameterizedClassInvocation
    static void tables(Live live) {
        live.execute("-DROP TABLE \"QUERY_IT\".\"PG_TAGGED\"",
            "CREATE TABLE \"QUERY_IT\".\"PG_TAGGED\" (\"ID\" INTEGER, \"TAGS\" TEXT[], \"DOC\" JSONB, \"BODY\" TEXT)",
            "INSERT INTO \"QUERY_IT\".\"PG_TAGGED\" VALUES (1, ARRAY['red', 'blue'], '{\"status\":\"open\",\"owner\":{\"name\":\"ann\"}}', 'the pump is running')",
            "INSERT INTO \"QUERY_IT\".\"PG_TAGGED\" VALUES (2, ARRAY['red'], '{\"status\":\"closed\"}', 'a valve without a leak')",
            "INSERT INTO \"QUERY_IT\".\"PG_TAGGED\" VALUES (3, ARRAY[]::TEXT[], '{\"status\":\"open\"}', 'valve leak found')");
        live.recreate(Note.class);
    }

    @Test
    void 关闭standard_conforming_strings的会话里含反斜杠的字面量仍原样往返() {
        // 旧式会话里反斜杠在字符串字面量里是转义符：方言要按会话的设置写字面量。
        Database legacy = live.database(new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException {
                Connection c = live.open();
                try (Statement s = c.createStatement()) {
                    s.execute("SET standard_conforming_strings = off");
                }
                return c;
            }

            @Override public void release(Connection connection) throws SQLException { connection.close(); }
        }, null);
        for (String text : List.of("back\\slash", "\\'; SELECT 1; --", "ends with \\")) {
            assertEquals(text, legacy.one(from(Lips.class).limit(1).select(l -> lit(text))));
        }
    }

    @Test
    void DISTINCT_ON取每个物料最新的交货() {
        assertEquals(List.of("D5", "D3"), live.db().list(from(Lips.class)
            .with(l -> distinctOn(l.material()))
            .orderBy(l -> orders(l.material(), l.createdOn().desc()))
            .select(l -> l.delivery())));
    }

    @Test
    void 聚合FILTER与ILIKE以及IS_DISTINCT_FROM() {
        assertEquals("6", plain(live.db().one(from(Lips.class).select(l -> sum(l.quantity()).filter(l.material().eq("M1"))))));
        assertEquals(5L, (long) live.db().one(from(Lips.class).where(l -> ilike(l.unit(), "ea")).select(l -> count())));
        assertEquals(2L, (long) live.db().one(from(Lips.class).where(l -> l.material().isDistinctFrom(val("M1"))).select(l -> count())));
    }

    @Test
    void 带排序的STRING_AGG() {
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), live.db().list(from(Lips.class)
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> row((m, d) -> m + "=" + d, l.material(), stringAgg(l.delivery(), ",", l.delivery().asc())))));
    }

    @Test
    void 分组集合与CUBE() {
        assertEquals(List.of("M1=6", "M2=12", "null=18"), live.db().list(from(Lips.class)
            .groupBy(l -> groupingSets(keys(l.material()), keys()))
            .orderBy(l -> orders(grouping(l.material()), l.material()))
            .select(l -> row((m, total) -> m + "=" + plain(total), l.material(), sum(l.quantity())))));
        assertEquals(6, live.db().list(from(Lips.class).groupBy(l -> cube(l.material(), l.unit())).select(l -> count())).size(),
            "material x unit, material, unit and the grand total");
    }

    @Test
    void 自动提交的连接上流式读取也按游标逐批取行并在关闭后恢复连接() throws SQLException {
        List<Boolean> autoCommitAtRelease = new ArrayList<>();
        Connection[] opened = new Connection[1];
        ConnectionProvider tracking = new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException { return opened[0] = live.open(); }

            @Override public void release(Connection connection) throws SQLException {
                autoCommitAtRelease.add(connection.getAutoCommit());
                connection.close();
            }
        };
        // LIPS 按插入的顺序扫描：D1、D2、D3 的数量是 3、2、5，第三行除以零，一次读完的话还没返回就会出错。
        // 不写 ORDER BY，因为排序会先算出每一行。
        Query<BigDecimal> q = from(Lips.class)
            .where(l -> l.client().eq("100"))
            .select(l -> divide(lit(BigDecimal.ONE), minus(l.quantity(), lit(new BigDecimal("5")))));
        try (Stream<BigDecimal> rows = live.database(tracking, c -> c.fetchSize(1)).stream(q)) {
            Iterator<BigDecimal> it = rows.iterator();
            assertEquals(0, new BigDecimal("-0.5").compareTo(it.next()));
            assertFalse(opened[0].getAutoCommit(), "the cursor needs a transaction");
            it.next();
            assertThrows(QueryExecutionException.class, it::next);
        }
        assertEquals(List.of(true), autoCommitAtRelease, "auto-commit is back on when the connection is released");
    }

    @Test
    void 外层事务中的流式读取不提交也不回滚外层事务() throws SQLException {
        try (Connection outer = live.open()) {
            outer.setAutoCommit(false);
            long transaction = transactionId(outer);
            ConnectionProvider shared = new ConnectionProvider() {
                @Override public Connection acquire() { return outer; }

                @Override public void release(Connection connection) { }
            };
            try (Stream<String> rows = live.database(shared, null).stream(from(Lips.class).select(l -> l.delivery()))) {
                assertEquals(6, rows.count());
            }
            assertFalse(outer.getAutoCommit());
            assertEquals(transaction, transactionId(outer), "the outer transaction goes on");
            outer.rollback();
        }
    }

    /** 连接当前所在事务的编号。 */
    private static long transactionId(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); var r = s.executeQuery("SELECT txid_current()")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void 原生SQL不在美元引号里找参数() {
        assertEquals(List.of(":not_a_parameter it's :also !"),
            live.db().sql("SELECT $$:not_a_parameter$$ || $tag$ it's :also $tag$ || :x AS v").bind("x", "!").list(String.class));
    }

    @Test
    void GREATEST与LEAST跳过NULL() {
        assertEquals(List.of("D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"), live.db().list(from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> row((d, high, low) -> d + ":" + plain(high) + ":" + plain(low),
                l.delivery(), greatest(l.quantity(), val(new BigDecimal("4"))), least(l.quantity(), val(new BigDecimal("4")))))));
        assertEquals("3", plain(live.db().one(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> greatest(l.quantity(), nullOf(BigDecimal.class))))),
            "HANA 和 MySQL 在这里返回 NULL");
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
        // EVENTS 的两行相隔十几年：近 7 天的窗口各只有自己；按编号距离 1 以内时 2 号也数上 1 号。
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
    void FULL_JOIN保留两侧没有匹配的行且有全局过滤条件时报错() {
        List<Map<String, Object>> rows = live.db().list(from(Lips.class).unfiltered()
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material()))
            .select((l, m) -> cols(l.delivery(), m.material())));
        assertEquals(12, rows.size());
        assertEquals(2, rows.stream().filter(r -> r.get("delivery") == null).count());
        assertThrows(IllegalStateException.class, () -> live.db().list(from(Lips.class)
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material())).select((l, m) -> l.delivery())), "a global filter has no side to go to");
    }

    @Test
    void 百分位数众数与相关系数() {
        assertEquals(List.of("M1:2.0:2:EA:1", "M2:6.0:5:EA:1"), live.db().list(from(Lips.class)
                .groupBy(l -> l.material())
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(), percentileCont(0.5, l.quantity()).as("median"), percentileDisc(0.5, l.quantity()).as("lower"),
                    mode(l.unit()).as("unit"), corr(l.quantity(), l.quantity()).as("self"))))
            .stream().map(r -> r.get("material") + ":" + r.get("median") + ":" + plain(r.get("lower")) + ":" + r.get("unit") + ":" + Math.round((Double) r.get("self"))).toList());
    }

    @Test
    void RETURNING读回插入和删除的行() {
        PostgresDatabase pg = (PostgresDatabase) live.db();
        NoteRow row = new NoteRow("RET1", "T9", "returned", 3, "open");
        assertEquals(List.of(row), pg.returning(insertInto(Note.class).values(row), NoteRow.class));
        assertEquals(List.of(row), pg.returning(deleteFrom(Note.class).where(n -> n.id().eq("RET1")), NoteRow.class));
        assertEquals(List.of(), pg.returning(deleteFrom(Note.class).where(n -> n.id().eq("RET1")), NoteRow.class));
    }

    @Test
    void 数组参数与数组列() {
        List<Map<String, Object>> rows = live.db().list(from(Tagged.class).where(t -> eqAny(t.id(), List.of(1, 2, 3))).orderBy(t -> t.id())
            .select(t -> cols(t.id(), t.tags(), cardinality(t.tags()).as("count"))));
        assertEquals(List.of("1:[red, blue]:2", "2:[red]:1", "3:[]:0"),
            rows.stream().map(r -> r.get("id") + ":" + Arrays.toString((String[]) r.get("tags")) + ":" + r.get("count")).toList());
        assertEquals(List.of(1, 2), live.db().list(from(Tagged.class).where(t -> arrayContains(t.tags(), new String[] {"red"})).orderBy(t -> t.id()).select(t -> t.id())));
        assertEquals(List.of(1), live.db().list(from(Tagged.class).where(t -> arrayOverlaps(t.tags(), new String[] {"blue", "green"})).select(t -> t.id())));
        assertEquals(List.of(), live.db().list(from(Tagged.class).where(t -> eqAny(t.id(), List.of())).select(t -> t.id())), "an empty array matches nothing");
        String[] deliveries = live.db().one(from(Lips.class).where(l -> l.material().eq("M2")).select(l -> arrayAgg(l.delivery(), String[].class, l.delivery().desc())));
        assertEquals(List.of("D4", "D3"), List.of(deliveries));
    }

    @Test
    void JSONB的包含键与路径文本() {
        assertEquals(List.of("1:ann"), live.db().list(from(Tagged.class).where(t -> all(jsonContains(t.doc(), "{\"status\":\"open\"}"), jsonHasKey(t.doc(), "owner")))
            .select(t -> cols(t.id(), jsonText(t.doc(), "owner", "name").as("owner")))).stream().map(r -> r.get("id") + ":" + r.get("owner")).toList());
    }

    @Test
    void 全文检索与相关度() {
        assertEquals(List.of(1), live.db().list(from(Tagged.class).where(t -> textMatches("simple", t.body(), "pump")).select(t -> t.id())));
        assertEquals(List.of(2), live.db().list(from(Tagged.class).where(t -> webMatches("simple", t.body(), "valve -found")).select(t -> t.id())));
        assertEquals(List.of(1, 2, 3), live.db().list(from(Tagged.class).orderBy(t -> orders(textRank("simple", t.body(), "pump").desc(), t.id())).select(t -> t.id())));
    }

    @Test
    void 按TableOption建原生分区表且查询不变() {
        live.recreate(PartLog.class);
        // PostgreSQL 的分区要另外建，通常写在迁移里。
        live.execute("CREATE TABLE \"QUERY_IT\".\"PART_LOG_2025\" PARTITION OF \"QUERY_IT\".\"PART_LOG\" FOR VALUES FROM ('2025-01-01') TO ('2026-01-01')",
            "CREATE TABLE \"QUERY_IT\".\"PART_LOG_REST\" PARTITION OF \"QUERY_IT\".\"PART_LOG\" DEFAULT");
        live.db().execute(insertInto(PartLog.class).values(new PartLogRow(1L, LocalDate.of(2025, 6, 1), BigDecimal.ONE), new PartLogRow(2L, LocalDate.of(2026, 6, 1), BigDecimal.TEN)));
        assertEquals(List.of(2L), live.db().list(from(PartLog.class).where(p -> p.day().ge(LocalDate.of(2026, 1, 1))).select(p -> p.id())));
        assertEquals(2L, live.db().sql("SELECT COUNT(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE n.nspname = 'QUERY_IT' AND c.relname = 'PART_LOG'").one(Long.class), "partitions of PART_LOG");
    }

    /** 小数去掉末尾的零。 */
    private static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }
}

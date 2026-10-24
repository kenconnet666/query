package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.postgres.PgTables.PartLog;
import org.dpdns.zerodep.query.postgres.PgTables.PartLogRow;
import org.dpdns.zerodep.query.postgres.PgTables.Tagged;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Step;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.dpdns.zerodep.query.testing.Writes.Note;
import org.dpdns.zerodep.query.testing.Writes.NoteRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.dpdns.zerodep.query.postgres.Postgres.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Needs QUERY_PG_URL, QUERY_PG_USER and QUERY_PG_PASSWORD; skipped otherwise. */
@DisplayName("PostgreSQL 实库场景")
class PostgresLiveTest extends LiveScenarios {

    @Override protected LiveDatabase connect() { return LiveDatabase.fromEnvironment("PG"); }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Postgres.database(connections, configure);
    }

    @Override protected String text(int length) { return "VARCHAR(" + length + ")"; }

    @Override protected String slowStatement() { return "SELECT pg_sleep(3)"; }

    @Test
    void 关闭standard_conforming_strings的会话里含反斜杠的字面量仍原样往返() {
        Database legacy = database(new ConnectionProvider() {
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
        List<String> rows = db.list(from(Lips.class)
            .with(l -> distinctOn(l.material()))
            .orderBy(l -> orders(l.material(), l.createdOn().desc()))
            .select(l -> l.delivery()));
        assertEquals(List.of("D5", "D3"), rows);
    }

    @Test
    void 聚合FILTER与ILIKE以及IS_DISTINCT_FROM() {
        BigDecimal m1 = db.one(from(Lips.class).select(l -> sum(l.quantity()).filter(l.material().eq("M1"))));
        assertEquals(0, new BigDecimal("6").compareTo(m1));
        long ea = db.one(from(Lips.class).where(l -> ilike(l.unit(), "ea")).select(l -> count()));
        assertEquals(5, ea);
        long notM1 = db.one(from(Lips.class).where(l -> l.material().isDistinctFrom(val("M1"))).select(l -> count()));
        assertEquals(2, notM1);
    }

    @Test
    void 带排序的STRING_AGG() {
        List<String> rows = db.list(from(Lips.class)
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> row((m, d) -> m + "=" + d, l.material(), stringAgg(l.delivery(), ",", l.delivery().asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }

    @Test
    void 分组集合与CUBE() {
        List<String> sets = db.list(from(Lips.class)
            .groupBy(l -> groupingSets(keys(l.material()), keys()))
            .orderBy(l -> orders(grouping(l.material()), l.material()))
            .select(l -> row((m, total) -> m + "=" + total.stripTrailingZeros().toPlainString(), l.material(), sum(l.quantity()))));
        assertEquals(List.of("M1=6", "M2=12", "null=18"), sets);
        List<Long> cells = db.list(from(Lips.class)
            .groupBy(l -> cube(l.material(), l.unit()))
            .select(l -> count()));
        assertEquals(6, cells.size(), "material x unit, material, unit and the grand total");
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
        // The fresh table scans in insertion order, D1, D2, D3 holding 3, 2 and 5: the third row divides by zero, which a
        // buffered read would hit before returning any row. No ORDER BY, since a sort evaluates every row up front.
        Query<BigDecimal> q = from(Lips.class)
            .where(l -> l.client().eq("100"))
            .select(l -> divide(lit(BigDecimal.ONE), minus(l.quantity(), lit(new BigDecimal("5")))));
        try (Stream<BigDecimal> rows = Postgres.database(tracking, c -> c.fetchSize(1)).stream(q)) {
            Iterator<BigDecimal> it = rows.iterator();
            assertEquals(0, new BigDecimal("-0.5").compareTo(it.next()));
            assertFalse(opened[0].getAutoCommit(), "the cursor needs a transaction");
            it.next();
            assertThrows(QueryExecutionException.class, it::next);
        }
        assertEquals(List.of(true), autoCommitAtRelease);
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
            try (Stream<String> rows = Postgres.database(shared, null).stream(from(Lips.class).select(l -> l.delivery()))) {
                assertEquals(6, rows.count());
            }
            assertFalse(outer.getAutoCommit());
            assertEquals(transaction, transactionId(outer), "the outer transaction goes on");
            outer.rollback();
        }
    }

    @Test
    void 原生SQL不在美元引号里找参数() {
        List<String> rows = db.sql("SELECT $$:not_a_parameter$$ || $tag$ it's :also $tag$ || :x AS v").bind("x", "!").list(String.class);
        assertEquals(List.of(":not_a_parameter it's :also !"), rows);
    }

    private static long transactionId(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT txid_current()")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void GREATEST与LEAST跳过NULL() {
        List<String> rows = db.list(from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> row((d, high, low) -> d + ":" + plain(high) + ":" + plain(low),
                l.delivery(), greatest(l.quantity(), val(new BigDecimal("4"))), least(l.quantity(), val(new BigDecimal("4"))))));
        assertEquals(List.of("D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"), rows);
        assertEquals("3", plain(db.one(from(Lips.class).where(l -> l.delivery().eq("D1"))
            .select(l -> greatest(l.quantity(), nullOf(BigDecimal.class))))));
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
    void FULL_JOIN保留两侧没有匹配的行且有全局过滤条件时报错() {
        List<Map<String, Object>> rows = db.list(from(Lips.class).unfiltered()
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material()))
            .select((l, m) -> cols(l.delivery(), m.material())));
        assertEquals(12, rows.size());
        assertEquals(2, rows.stream().filter(r -> r.get("delivery") == null).count());
        assertThrows(IllegalStateException.class, () -> db.list(from(Lips.class)
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material())).select((l, m) -> l.delivery())));
    }

    @Test
    void 百分位数众数与相关系数() {
        assertEquals(List.of("M1:2.0:2:EA:1.0", "M2:6.0:5:EA:1.0"), db.list(from(Lips.class)
                .groupBy(l -> l.material())
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(), percentileCont(0.5, l.quantity()).as("median"), percentileDisc(0.5, l.quantity()).as("lower"),
                    mode(l.unit()).as("unit"), corr(l.quantity(), l.quantity()).as("self"))))
            .stream().map(r -> r.get("material") + ":" + r.get("median") + ":" + plainText(r.get("lower")) + ":" + r.get("unit") + ":" + Math.round((Double) r.get("self"))  + ".0").toList());
    }

    @Test
    void RETURNING读回插入和删除的行() {
        PostgresDatabase pg = (PostgresDatabase) db;
        NoteRow row = new NoteRow("RET1", "T9", "returned", 3, "open");
        assertEquals(List.of(row), pg.returning(insertInto(Note.class).values(row), NoteRow.class));
        assertEquals(List.of(row), pg.returning(deleteFrom(Note.class).where(n -> n.id().eq("RET1")), NoteRow.class));
        assertEquals(List.of(), pg.returning(deleteFrom(Note.class).where(n -> n.id().eq("RET1")), NoteRow.class));
    }

    /** 小数去掉末尾的零。 */
    private static String plainText(Object value) { return ((BigDecimal) value).stripTrailingZeros().toPlainString(); }

    /** 建数组、JSONB 和全文检索用的表。 */
    private void createTagged() throws java.sql.SQLException {
        live.execute(List.of("-DROP TABLE \"QUERY_IT\".\"PG_TAGGED\"",
            "CREATE TABLE \"QUERY_IT\".\"PG_TAGGED\" (\"ID\" INTEGER, \"TAGS\" TEXT[], \"DOC\" JSONB, \"BODY\" TEXT)",
            "INSERT INTO \"QUERY_IT\".\"PG_TAGGED\" VALUES (1, ARRAY['red', 'blue'], '{\"status\":\"open\",\"owner\":{\"name\":\"ann\"}}', 'the pump is running')",
            "INSERT INTO \"QUERY_IT\".\"PG_TAGGED\" VALUES (2, ARRAY['red'], '{\"status\":\"closed\"}', 'a valve without a leak')",
            "INSERT INTO \"QUERY_IT\".\"PG_TAGGED\" VALUES (3, ARRAY[]::TEXT[], '{\"status\":\"open\"}', 'valve leak found')"));
    }

    @Test
    void 数组参数与数组列() throws java.sql.SQLException {
        createTagged();
        List<Map<String, Object>> rows = db.list(from(Tagged.class).where(t -> eqAny(t.id(), List.of(1, 2, 3))).orderBy(t -> t.id())
            .select(t -> cols(t.id(), t.tags(), cardinality(t.tags()).as("count"))));
        assertEquals(List.of("1:[red, blue]:2", "2:[red]:1", "3:[]:0"),
            rows.stream().map(r -> r.get("id") + ":" + java.util.Arrays.toString((String[]) r.get("tags")) + ":" + r.get("count")).toList());
        assertEquals(List.of(1, 2), db.list(from(Tagged.class).where(t -> arrayContains(t.tags(), new String[] {"red"})).orderBy(t -> t.id()).select(t -> t.id())));
        assertEquals(List.of(1), db.list(from(Tagged.class).where(t -> arrayOverlaps(t.tags(), new String[] {"blue", "green"})).select(t -> t.id())));
        assertEquals(List.of(), db.list(from(Tagged.class).where(t -> eqAny(t.id(), List.of())).select(t -> t.id())));
        String[] deliveries = db.one(from(Lips.class).where(l -> l.material().eq("M2")).select(l -> arrayAgg(l.delivery(), String[].class, l.delivery().desc())));
        assertEquals(List.of("D4", "D3"), List.of(deliveries));
    }

    @Test
    void JSONB的包含键与路径文本() throws java.sql.SQLException {
        createTagged();
        assertEquals(List.of("1:ann"), db.list(from(Tagged.class).where(t -> all(jsonContains(t.doc(), "{\"status\":\"open\"}"), jsonHasKey(t.doc(), "owner")))
            .select(t -> cols(t.id(), jsonText(t.doc(), "owner", "name").as("owner")))).stream().map(r -> r.get("id") + ":" + r.get("owner")).toList());
    }

    @Test
    void 全文检索与相关度() throws java.sql.SQLException {
        createTagged();
        assertEquals(List.of(1), db.list(from(Tagged.class).where(t -> textMatches("simple", t.body(), "pump")).select(t -> t.id())));
        assertEquals(List.of(2), db.list(from(Tagged.class).where(t -> webMatches("simple", t.body(), "valve -found")).select(t -> t.id())));
        assertEquals(List.of(1, 2, 3), db.list(from(Tagged.class).orderBy(t -> orders(textRank("simple", t.body(), "pump").desc(), t.id())).select(t -> t.id())));
    }

    @Test
    void 按TableOption建原生分区表且查询不变() throws SQLException {
        live.execute(List.of("-DROP TABLE \"QUERY_IT\".\"PART_LOG\""));
        db.schema().plan(PartLog.class).apply();
        // PostgreSQL 的分区要另外建，通常写在迁移里。
        live.execute(List.of("CREATE TABLE \"QUERY_IT\".\"PART_LOG_2025\" PARTITION OF \"QUERY_IT\".\"PART_LOG\" FOR VALUES FROM ('2025-01-01') TO ('2026-01-01')",
            "CREATE TABLE \"QUERY_IT\".\"PART_LOG_REST\" PARTITION OF \"QUERY_IT\".\"PART_LOG\" DEFAULT"));
        db.execute(insertInto(PartLog.class).values(new PartLogRow(1L, LocalDate.of(2025, 6, 1), BigDecimal.ONE), new PartLogRow(2L, LocalDate.of(2026, 6, 1), BigDecimal.TEN)));
        assertEquals(List.of(2L), db.list(from(PartLog.class).where(p -> p.day().ge(LocalDate.of(2026, 1, 1))).select(p -> p.id())));
        try (Connection c = live.open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhparent JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'QUERY_IT' AND c.relname = 'PART_LOG'")) {
            rs.next();
            assertEquals(2, rs.getInt(1), "partitions of PART_LOG");
        }
    }
}

package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.Query;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
        BigDecimal m1 = db.one(from(Lips.class).select(l -> filter(sum(l.quantity()), l.material().eq("M1"))));
        assertEquals(0, new BigDecimal("6").compareTo(m1));
        long ea = db.one(from(Lips.class).where(l -> ilike(l.unit(), "ea")).select(l -> count()));
        assertEquals(5, ea);
        long notM1 = db.one(from(Lips.class).where(l -> pg(l.material()).isDistinctFrom("M1")).select(l -> count()));
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
}

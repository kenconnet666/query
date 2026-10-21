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

    @Override protected Database database(LiveDatabase live, Consumer<Database.Config> configure) {
        return Postgres.database(live.connections(), configure);
    }

    @Override protected String text(int length) { return "VARCHAR(" + length + ")"; }

    @Test
    void DISTINCT_ON取每个物料最新的交货() {
        List<String> rows = db.list(from(Lips.class)
            .with(l -> distinctOn(l.col(Lips::getMaterial)))
            .orderBy(l -> orders(l.col(Lips::getMaterial).asc(), l.col(Lips::getCreatedOn).desc()))
            .selectValue(l -> l.col(Lips::getDelivery)));
        assertEquals(List.of("D5", "D3"), rows);
    }

    @Test
    void 聚合FILTER与ILIKE以及IS_DISTINCT_FROM() {
        BigDecimal m1 = db.one(from(Lips.class).selectValue(l -> filter(sum(l.col(Lips::getQuantity)), l.col(Lips::getMaterial).eq("M1"))));
        assertEquals(0, new BigDecimal("6").compareTo(m1));
        long ea = db.one(from(Lips.class).where(l -> ilike(l.col(Lips::getUnit), "ea")).selectValue(l -> count()));
        assertEquals(5, ea);
        long notM1 = db.one(from(Lips.class).where(l -> pg(l.col(Lips::getMaterial)).isDistinctFrom("M1")).selectValue(l -> count()));
        assertEquals(2, notM1);
    }

    @Test
    void 带排序的STRING_AGG() {
        List<String> rows = db.list(from(Lips.class)
            .groupBy(l -> keys(l.col(Lips::getMaterial)))
            .orderBy(l -> orders(l.col(Lips::getMaterial).asc()))
            .select((r, l) -> r.get(l, Lips::getMaterial) + "=" + r.get(stringAgg(l.col(Lips::getDelivery), ",", l.col(Lips::getDelivery).asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }

    @Test
    void 分组集合与CUBE() {
        List<String> sets = db.list(from(Lips.class)
            .groupBy(l -> keys(groupingSets(keys(l.col(Lips::getMaterial)), keys())))
            .orderBy(l -> orders(grouping(l.col(Lips::getMaterial)).asc(), l.col(Lips::getMaterial).asc()))
            .select((r, l) -> r.get(l, Lips::getMaterial) + "=" + r.get(sum(l.col(Lips::getQuantity))).stripTrailingZeros().toPlainString()));
        assertEquals(List.of("M1=6", "M2=12", "null=18"), sets);
        List<Long> cells = db.list(from(Lips.class)
            .groupBy(l -> keys(cube(l.col(Lips::getMaterial), l.col(Lips::getUnit))))
            .selectValue(l -> count()));
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
            .where(l -> l.col(Lips::getClient).eq("100"))
            .selectValue(l -> divide(lit(BigDecimal.ONE), minus(l.col(Lips::getQuantity), lit(new BigDecimal("5")))));
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
            try (Stream<String> rows = Postgres.database(shared, null).stream(from(Lips.class).selectValue(l -> l.col(Lips::getDelivery)))) {
                assertEquals(6, rows.count());
            }
            assertFalse(outer.getAutoCommit());
            assertEquals(transaction, transactionId(outer), "the outer transaction goes on");
            outer.rollback();
        }
    }

    private static long transactionId(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT txid_current()")) {
            r.next();
            return r.getLong(1);
        }
    }
}

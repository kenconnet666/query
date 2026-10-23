package org.dpdns.zerodep.query.spring;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;

import static org.dpdns.zerodep.query.Sql.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring's own transaction manager and JdbcTemplate over an in-memory H2 database. */
@DisplayName("Spring 事务")
class SpringConnectionsTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Counting dataSource;
    private Database db;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    /** Renders with the neutral test dialect; H2 understands its SQL. */
    static final class H2Database extends Database {
        H2Database(ConnectionProvider connections, Consumer<Database.Config> configure) { super(new TestDialect(), connections, configure); }
    }

    /** Counts the connections borrowed from the pool and those still open, and records statement timeouts. */
    static final class Counting extends DelegatingDataSource {
        final AtomicInteger borrowed = new AtomicInteger();
        final AtomicInteger open = new AtomicInteger();
        final List<Integer> timeouts = new CopyOnWriteArrayList<>();

        Counting(DataSource target) { super(target); }

        @Override public Connection getConnection() throws SQLException {
            Connection target = super.getConnection();
            borrowed.incrementAndGet();
            open.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("close") && !target.isClosed()) open.decrementAndGet();
                Object result = invoke(target, method, args);
                return result instanceof PreparedStatement ps && method.getName().equals("prepareStatement") ? recording(ps) : result;
            });
        }

        private PreparedStatement recording(PreparedStatement target) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setQueryTimeout")) timeouts.add((Integer) args[0]);
                    return invoke(target, method, args);
                });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @BeforeEach
    void createDatabase() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:spring" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource = new Counting(h2);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA QUERY_IT");
        jdbc.execute("CREATE TABLE QUERY_IT.LIPS (MANDT VARCHAR(3), VBELN VARCHAR(10), MATNR VARCHAR(40), ERDAT VARCHAR(8), "
            + "LFIMG DECIMAL(13,3), VRKME VARCHAR(3), VGBEL VARCHAR(10))");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        db = new H2Database(SpringConnections.of(dataSource), c -> c.queryTimeout(Duration.ofMinutes(1)));
        dataSource.borrowed.set(0);
    }

    private Query<String> deliveries() { return from(Lips.class).select(l -> l.delivery()); }

    @Test
    void 事务中的查询看得到同一事务尚未提交的写入并随事务回滚() {
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO QUERY_IT.LIPS (MANDT, VBELN) VALUES ('100', 'T1')");
            assertEquals(List.of("T1"), db.list(deliveries()));
            status.setRollbackOnly();
        });
        assertEquals(List.of(), db.list(deliveries()));
    }

    @Test
    void 事务内的语句共用一个连接并在事务结束时归还() {
        tx.executeWithoutResult(status -> {
            db.list(deliveries());
            jdbc.update("INSERT INTO QUERY_IT.LIPS (MANDT, VBELN) VALUES ('100', 'T2')");
            db.count(deliveries());
            db.forEach(deliveries(), d -> assertEquals(1, dataSource.open.get(), "a stream inside the transaction keeps its connection"));
            assertEquals(1, dataSource.borrowed.get());
        });
        assertEquals(0, dataSource.open.get());
        db.list(deliveries());
        db.list(deliveries());
        assertEquals(3, dataSource.borrowed.get(), "outside a transaction every statement borrows and returns its own connection");
        assertEquals(0, dataSource.open.get());
    }

    @Test
    void 查询失败时异常穿出事务并回滚其中的写入() {
        assertThrows(QueryExecutionException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO QUERY_IT.LIPS (MANDT, VBELN) VALUES ('100', 'T3')");
            db.sql("SELECT MISSING_COLUMN FROM QUERY_IT.LIPS").list(String.class);
        }));
        assertEquals(List.of(), db.list(deliveries()));
    }

    @Test
    void 事务超时按剩余时间限制每条语句并优先于数据库实例的默认超时() {
        TransactionTemplate timed = new TransactionTemplate(tx.getTransactionManager());
        timed.setTimeout(5);
        timed.executeWithoutResult(status -> db.list(deliveries()));
        db.list(deliveries());
        assertEquals(3, dataSource.timeouts.size(), "inside the transaction the default is set first, then the transaction's: " + dataSource.timeouts);
        assertEquals(60, dataSource.timeouts.get(0));
        int inside = dataSource.timeouts.get(1);
        assertTrue(inside >= 1 && inside <= 5, "the statement gets what the transaction has left: " + dataSource.timeouts);
        assertEquals(60, dataSource.timeouts.get(2), "outside a transaction the database instance's default applies");
    }

    @Test
    void 事务超时已过时查询直接失败() {
        TransactionTemplate timed = new TransactionTemplate(tx.getTransactionManager());
        timed.setTimeout(1);
        assertThrows(TransactionTimedOutException.class, () -> timed.executeWithoutResult(status -> {
            try {
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            db.list(deliveries());
        }));
    }
}

package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.dpdns.zerodep.query.Sql.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("执行设置")
class ExecutionSettingsTest {

    /** Hands out connections whose statements return one empty row and record the query timeouts set on them. */
    static final class Recording implements ConnectionProvider {
        final List<Integer> timeouts = new ArrayList<>();
        final List<String> order = new ArrayList<>();

        @Override public Connection acquire() {
            return proxy(Connection.class, (method, args) -> method.equals("prepareStatement") ? statement() : null);
        }

        @Override public void release(Connection connection) { }

        @Override public void prepared(PreparedStatement statement) { order.add("prepared"); }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setQueryTimeout" -> {
                    timeouts.add((Integer) args[0]);
                    order.add("timeout");
                    yield null;
                }
                case "executeQuery" -> rows();
                default -> null;
            });
        }

        private static ResultSet rows() {
            int[] read = {0};
            return proxy(ResultSet.class, (m, a) -> switch (m) {
                case "next" -> read[0]++ == 0;
                case "getMetaData" -> proxy(ResultSetMetaData.class, (mm, aa) -> mm.equals("getColumnCount") ? 1 : "value");
                default -> null;
            });
        }
    }

    interface Handler {
        Object handle(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            Object result = handler.handle(m.getName(), a);
            Class<?> r = m.getReturnType();
            if (result != null || !r.isPrimitive() || r == void.class) return result;
            if (r == boolean.class) return false;
            if (r == long.class) return 0L;
            if (r == double.class) return 0.0;
            if (r == float.class) return 0f;
            return 0;
        });
    }

    static final class Fake extends Database {
        Fake(ConnectionProvider connections, Consumer<Config> configure) { super(new TestDialect(), connections, configure); }
    }

    private static Query<String> deliveries() { return from(Lips.class).select(l -> l.delivery()); }

    @Test
    void 没有配置超时时不设置语句超时() {
        Recording connections = new Recording();
        new Fake(connections, null).list(deliveries());
        assertEquals(List.of(), connections.timeouts);
    }

    @Test
    void 数据库实例的默认超时按整秒向上取整设置到每条语句() {
        Recording connections = new Recording();
        Database db = new Fake(connections, c -> c.queryTimeout(Duration.ofMillis(1500)));
        db.list(deliveries());
        db.page(deliveries(), 1, 10);
        db.sql("SELECT 1").list(String.class);
        assertEquals(List.of(2, 2, 2, 2), connections.timeouts);
    }

    @Test
    void 查询和原生SQL自己的超时优先于默认值() {
        Recording connections = new Recording();
        Database db = new Fake(connections, c -> c.queryTimeout(Duration.ofMinutes(1)));
        db.list(deliveries().timeout(Duration.ofMillis(200)));
        db.count(deliveries().timeout(Duration.ofSeconds(3)));
        db.stream(deliveries().cached(Duration.ofMinutes(1)).timeout(Duration.ofSeconds(4))).close();
        db.sql("SELECT 1").timeout(Duration.ofSeconds(5)).list(String.class);
        assertEquals(List.of(1, 3, 4, 5), connections.timeouts);
    }

    @Test
    void 连接提供者在超时设置之后收到语句() {
        Recording connections = new Recording();
        new Fake(connections, c -> c.queryTimeout(Duration.ofSeconds(1))).list(deliveries());
        assertEquals(List.of("timeout", "prepared"), connections.order, "a Spring transaction timeout set in prepared() must win");
    }

    @Test
    void 分页结果里可以有NULL值() {
        var page = new Fake(new Recording(), null).page(deliveries(), 1, 10);
        assertEquals(java.util.Arrays.asList((String) null), page.rows());
    }

    @Test
    void 超时必须为正() {
        assertThrows(IllegalArgumentException.class, () -> deliveries().timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Fake(new Recording(), c -> c.queryTimeout(Duration.ofSeconds(-1))));
    }

    @Test
    void 参数个数超过方言上限时在连接数据库之前报错() {
        List<String> many = IntStream.range(0, 70_000).mapToObj(i -> "D" + i).toList();
        Query<String> q = from(Lips.class).where(l -> l.delivery().in(many)).select(l -> l.delivery());
        var e = assertThrows(IllegalStateException.class, () -> new TestDatabase(null).list(q));
        assertTrue(e.getMessage().contains("binds 70000 parameters") && e.getMessage().contains("at most 65535"), e.getMessage());
    }
}

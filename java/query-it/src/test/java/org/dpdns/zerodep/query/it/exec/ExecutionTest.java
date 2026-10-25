package org.dpdns.zerodep.query.it.exec;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.Page;
import org.dpdns.zerodep.query.exec.QueryCache;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.exec.QueryListener;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.dpdns.zerodep.query.Sql.count;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行：分页、流式读取、结果缓存、出错时归还连接，以及语句超时由数据库取消。
 * 数据见 {@link SampleData}：客户端 100 有 D1 到 D5 五张交货单，加上客户端 200 共六张。
 */
@LiveTest
@DisplayName("分页、流式、缓存与超时")
class ExecutionTest {
    @Parameter Live live;

    /** 按交货单排序的全部交货单号。 */
    private static final Query<String> DELIVERIES = from(Lips.class).orderBy(l -> l.delivery()).select(l -> l.delivery());

    @Test
    void 分页返回当前页和总数() {
        Page<String> page = live.db().page(DELIVERIES, 2, 2);
        assertEquals(List.of("D3", "D4"), page.rows());
        assertEquals(5, page.total());
        assertEquals(3, page.totalPages());
    }

    @Test
    void 已知总数的分页不再执行COUNT() {
        var events = live.db().capture(() -> assertEquals(List.of("D3", "D4"), live.db().page(DELIVERIES, 2, 2, 5).rows()));
        assertEquals(1, events.size(), "only the page itself runs");
    }

    @Test
    void 流式读取逐行处理() {
        try (Stream<String> rows = live.db().stream(DELIVERIES)) {
            assertEquals(List.of("D1", "D2", "D3", "D4", "D5"), rows.toList());
        }
    }

    @Test
    void 结果缓存命中时不再查询数据库() {
        // 缓存按查询开启（cached），清空后重新查询；监听事件的 cached 记录每次是否来自缓存。
        List<QueryListener.Event> events = new ArrayList<>();
        Database cached = live.database(c -> c.cache(QueryCache.inMemory()).listener(events::add));
        Query<Long> q = from(Lips.class).select(l -> count()).cached(Duration.ofMinutes(1));
        assertEquals(List.of(6L, 6L), List.of(cached.one(q), cached.one(q)), "this instance has no client filter");
        assertEquals(List.of(false, true), events.stream().map(QueryListener.Event::cached).toList());
        cached.clearCache();
        assertEquals(6L, cached.one(q));
        assertEquals(List.of(false, true, false), events.stream().map(QueryListener.Event::cached).toList());
    }

    @Test
    void 查询出错映射出错或提前关闭流时都归还连接() {
        // 数着借出和归还的连接：每种出错的方式之后，借出的连接都要还回来。
        int[] open = {0};
        ConnectionProvider counting = new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException {
                Connection c = live.open();
                open[0]++;
                return c;
            }

            @Override public void release(Connection connection) throws SQLException {
                open[0]--;
                connection.close();
            }
        };
        Database db = live.database(counting, null);
        Query<String> failsOnD3 = from(Lips.class).orderBy(l -> l.delivery()).select(l -> row(d -> {
            if ("D3".equals(d)) throw new IllegalStateException("mapping failed");
            return d;
        }, l.delivery()));
        assertThrows(QueryExecutionException.class, () -> db.sql("SELECT * FROM NO_SUCH_TABLE").list(String.class), "the database fails");
        assertThrows(IllegalStateException.class, () -> db.list(failsOnD3), "the mapping fails");
        try (Stream<String> rows = db.stream(DELIVERIES)) {
            assertEquals("D1", rows.findFirst().orElseThrow());
            assertEquals(1, open[0], "the stream holds its connection until closed");
        }
        assertThrows(IllegalStateException.class, () -> {
            try (Stream<String> rows = db.stream(failsOnD3)) {
                rows.forEach(d -> { });
            }
        }, "the mapping fails while streaming");
        assertThrows(IllegalStateException.class, () -> db.forEach(DELIVERIES, d -> {
            throw new IllegalStateException("consumer failed");
        }), "the consumer fails");
        assertEquals(6, db.page(DELIVERIES, 1, 2).total());
        assertEquals(0, open[0]);
    }

    @Test
    void 超过超时的语句在数据库端被取消且连接仍可用() {
        // 单条语句的 timeout 和实例的默认 queryTimeout 都交给数据库取消；数据库报的超时错误要认得出来。
        long start = System.nanoTime();
        var own = assertThrows(QueryExecutionException.class, () -> live.db().sql(live.slowStatement()).timeout(Duration.ofSeconds(1)).list(String.class));
        Database limited = live.database(c -> c.queryTimeout(Duration.ofSeconds(1)));
        var byDefault = assertThrows(QueryExecutionException.class, () -> limited.sql(live.slowStatement()).list(String.class));
        assertTrue(own.isTimeout() && byDefault.isTimeout(), "the dialect recognises its database's timeout error: " + own.getCause());
        assertFalse(assertThrows(QueryExecutionException.class, () -> live.db().sql("SELECT * FROM " + live.table("NO_SUCH_TABLE")).list(String.class)).isTimeout());
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(elapsed < 5000, "both statements were cancelled after about a second instead of running to the end: " + elapsed + " ms");
        assertEquals(5, limited.count(from(Lips.class).where(l -> l.client().eq("100")).select(l -> l.delivery())));
        try (Stream<String> streamed = limited.stream(from(Lips.class).where(l -> l.client().eq("100")).select(l -> l.delivery()).timeout(Duration.ofSeconds(5)))) {
            assertEquals(5, streamed.count(), "a stream applies its timeout and still reads every row");
        }
    }
}

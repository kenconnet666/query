package org.dpdns.zerodep.query.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("结果缓存")
class QueryCacheTest {
    private static QueryCache.Key key(String sql) { return new QueryCache.Key("db", sql, List.of()); }

    @Test
    void 过期前命中缓存不再加载() {
        QueryCache cache = QueryCache.inMemory();
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            cache.get(key("a"), Duration.ofMinutes(1), () -> {
                loads.incrementAndGet();
                return List.<Object[]>of(new Object[] {1});
            });
        }
        assertEquals(1, loads.get());
    }

    @Test
    void 过期后重新加载() throws InterruptedException {
        QueryCache cache = QueryCache.inMemory();
        AtomicInteger loads = new AtomicInteger();
        cache.get(key("a"), Duration.ofMillis(1), () -> List.<Object[]>of(new Object[] {loads.incrementAndGet()}));
        Thread.sleep(5);
        cache.get(key("a"), Duration.ofMillis(1), () -> List.<Object[]>of(new Object[] {loads.incrementAndGet()}));
        assertEquals(2, loads.get());
    }

    @Test
    void 超过容量时淘汰最早的条目() {
        QueryCache cache = QueryCache.inMemory(2);
        AtomicInteger loads = new AtomicInteger();
        for (String sql : List.of("a", "b", "c", "a")) {
            cache.get(key(sql), Duration.ofMinutes(1), () -> {
                loads.incrementAndGet();
                return List.of();
            });
        }
        assertEquals(4, loads.get(), "a was evicted when c arrived, so it loads again");
    }
}

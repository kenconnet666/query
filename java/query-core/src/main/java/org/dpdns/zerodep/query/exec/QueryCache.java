package org.dpdns.zerodep.query.exec;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 标记了 {@code .cached(ttl)} 的查询的结果缓存。缓存的是解码后的行而不是映射后的对象，
 * 因为投影 lambda 可能捕获每次调用都不同的变量。实现它即可接入 Caffeine、Redis 等任意缓存。
 */
public interface QueryCache {
    /** 缓存键：数据库实例名、SQL 文本和参数列表共同决定一条结果。 */
    record Key(String database, String sql, List<Object> parameters) { }

    /** 命中且未过期时返回缓存的行；否则调用 {@code loader} 取数，按 {@code ttl} 存入后返回。 */
    List<Object[]> get(Key key, Duration ttl, Supplier<List<Object[]>> loader);

    /** 清空全部缓存。 */
    void clear();

    /** 进程内缓存，每条记录独立过期，并限制条数上限。 */
    static QueryCache inMemory(int maxEntries) { return new InMemoryQueryCache(maxEntries); }

    /** 上限 1000 条的进程内缓存。 */
    static QueryCache inMemory() { return inMemory(1_000); }
}

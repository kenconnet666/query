package org.dpdns.zerodep.query.exec;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Result cache for queries marked {@code .cached(ttl)}. It stores decoded rows, not mapped objects,
 * because a projection lambda may capture variables that differ between calls. Implement it to
 * use Caffeine, Redis or anything else.
 */
public interface QueryCache {
    record Key(String database, String sql, List<Object> parameters) { }

    List<Object[]> get(Key key, Duration ttl, Supplier<List<Object[]>> loader);

    void clear();

    /** In-process cache with per-entry expiry and a size bound. */
    static QueryCache inMemory(int maxEntries) { return new InMemoryQueryCache(maxEntries); }

    static QueryCache inMemory() { return inMemory(1_000); }
}

package org.dpdns.zerodep.query.exec;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Expired entries are dropped when read; when full, expired entries go first, then the oldest. */
final class InMemoryQueryCache implements QueryCache {
    private record Entry(List<Object[]> rows, long expiresAt, long createdAt) { }

    private final int maxEntries;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    InMemoryQueryCache(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    @Override
    public List<Object[]> get(Key key, Duration ttl, Supplier<List<Object[]>> loader) {
        long now = System.nanoTime();
        Entry e = entries.get(key);
        if (e != null && e.expiresAt() - now > 0) return e.rows();
        List<Object[]> rows = List.copyOf(loader.get());
        if (entries.size() >= maxEntries) evict(now);
        entries.put(key, new Entry(rows, now + ttl.toNanos(), now));
        return rows;
    }

    @Override public void clear() { entries.clear(); }

    private void evict(long now) {
        entries.values().removeIf(e -> e.expiresAt() - now <= 0);
        while (entries.size() >= maxEntries) {
            Key oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (Iterator<Map.Entry<Key, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Key, Entry> me = it.next();
                if (me.getValue().createdAt() < oldestAt) {
                    oldestAt = me.getValue().createdAt();
                    oldest = me.getKey();
                }
            }
            if (oldest == null) return;
            entries.remove(oldest);
        }
    }
}

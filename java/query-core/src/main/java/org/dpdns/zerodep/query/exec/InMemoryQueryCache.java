package org.dpdns.zerodep.query.exec;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 基于 {@link ConcurrentHashMap} 的进程内缓存：读到过期记录就丢弃；写满时先清过期的，再淘汰最早写入的。 */
final class InMemoryQueryCache implements QueryCache {
    /** 一条缓存记录；时间均为 {@link System#nanoTime()} 刻度。 */
    private record Entry(List<Object[]> rows, long expiresAt, long createdAt) { }

    private final int maxEntries;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    /** @param maxEntries 记录数上限，必须为正 */
    InMemoryQueryCache(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    /** 命中则直接返回；未命中或已过期则调用 {@code loader}，这期间不加锁，并发未命中可能重复加载。 */
    @Override
    public List<Object[]> get(Key key, Duration ttl, Supplier<List<Object[]>> loader) {
        long now = System.nanoTime();
        Entry e = entries.get(key);
        // 用差值比较，nanoTime 回绕时依然正确
        if (e != null && e.expiresAt() - now > 0) return e.rows();
        List<Object[]> rows = List.copyOf(loader.get());
        if (entries.size() >= maxEntries) evict(now);
        entries.put(key, new Entry(rows, now + ttl.toNanos(), now));
        return rows;
    }

    /** 清空全部记录。 */
    @Override public void clear() { entries.clear(); }

    /** 腾出空间：先删过期记录，仍满则逐条淘汰创建时间最早的。 */
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

package org.dpdns.zerodep.query.table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 同库物理分表的规则：分表键（表接口的属性名）、全部物理表，以及一个键值或一段范围落在哪些表。
 * 用 {@code Config.shard(Event.class, ShardRule.monthly("createdOn", YearMonth.of(2025, 1), YearMonth.of(2026, 12)))} 注册，
 * 之后查询和写入按条件里的分表键选表，见 {@code docs/api.md} 的"分表"一节。
 */
public interface ShardRule {
    /** 分表键：表接口的属性名。 */
    String key();

    /** 逻辑表 table 的全部物理表，按顺序。 */
    List<String> tables(String table);

    /** 键值 value 所在的物理表。 */
    String route(String table, Object value);

    /**
     * 可能含有 low 到 high 之间的键值的物理表；bound 为 null 表示不限。只能按值路由的规则（取模、自定义）返回全部表。
     * 结果可以多于实际需要（多扫几张表），不能少。
     */
    default List<String> range(String table, Object low, boolean lowInclusive, Object high, boolean highInclusive) { return tables(table); }

    /**
     * 按月分表：物理表是 {@code 表名_yyyyMM}，从 first 到 last（含）每月一张；键是 {@code LocalDate} 或 {@code LocalDateTime}，
     * 等值、IN 和范围条件都能选出对应的月份。
     */
    static ShardRule monthly(String key, YearMonth first, YearMonth last) {
        Objects.requireNonNull(key, "key");
        if (last.isBefore(first)) throw new IllegalArgumentException("monthly shards end before they start: " + first + " .. " + last);
        DateTimeFormatter suffix = DateTimeFormatter.ofPattern("yyyyMM");
        return new ShardRule() {
            @Override public String key() { return key; }

            @Override public List<String> tables(String table) { return between(table, first, last); }

            @Override public String route(String table, Object value) {
                YearMonth m = month(value);
                if (m.isBefore(first) || m.isAfter(last)) throw new IllegalArgumentException(key + " = " + value + " is outside the shards " + first + " .. " + last);
                return table + "_" + m.format(suffix);
            }

            @Override public List<String> range(String table, Object low, boolean lowInclusive, Object high, boolean highInclusive) {
                YearMonth from = low == null ? first : max(first, month(low));
                YearMonth to = high == null ? last : min(last, highInclusive || !startOfMonth(high) ? month(high) : month(high).minusMonths(1));
                return to.isBefore(from) ? List.of() : between(table, from, to);
            }

            /** from 到 to（含）每月一张表。 */
            private List<String> between(String table, YearMonth from, YearMonth to) {
                List<String> out = new ArrayList<>();
                for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) out.add(table + "_" + m.format(suffix));
                return out;
            }
        };
    }

    /** 按取模分表：物理表是 {@code 表名_0} 到 {@code 表名_(count-1)}，整数键 k 在 {@code 表名_(k mod count)}；只有等值和 IN 能选表。 */
    static ShardRule modulo(String key, int count) {
        Objects.requireNonNull(key, "key");
        if (count < 1) throw new IllegalArgumentException("modulo shards need a positive count, got " + count);
        return new ShardRule() {
            @Override public String key() { return key; }

            @Override public List<String> tables(String table) {
                List<String> out = new ArrayList<>();
                for (int i = 0; i < count; i++) out.add(table + "_" + i);
                return out;
            }

            @Override public String route(String table, Object value) {
                if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                    throw new IllegalArgumentException(key + " must be an integer to pick a modulo shard, got " + value);
                }
                return table + "_" + Math.floorMod(n.longValue(), count);
            }
        };
    }

    /** 自定义：物理表是 tables（写全名），router 把键值映射到其中一张；只有等值和 IN 能选表。 */
    static ShardRule custom(String key, List<String> tables, Function<Object, String> router) {
        Objects.requireNonNull(key, "key");
        List<String> all = List.copyOf(tables);
        if (all.isEmpty()) throw new IllegalArgumentException("custom shards need at least one table");
        return new ShardRule() {
            @Override public String key() { return key; }

            @Override public List<String> tables(String table) { return all; }

            @Override public String route(String table, Object value) {
                String target = router.apply(value);
                if (!all.contains(target)) throw new IllegalArgumentException(key + " = " + value + " routes to " + target + ", which is not one of " + all);
                return target;
            }
        };
    }

    /** 日期或时间戳所在的月份。 */
    private static YearMonth month(Object value) {
        if (value instanceof LocalDate d) return YearMonth.from(d);
        if (value instanceof LocalDateTime t) return YearMonth.from(t);
        throw new IllegalArgumentException("monthly shards need a LocalDate or LocalDateTime key, got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    /** value 是不是某月一日的零点：{@code < 2026-02-01} 不含二月。 */
    private static boolean startOfMonth(Object value) {
        if (value instanceof LocalDate d) return d.getDayOfMonth() == 1;
        return value instanceof LocalDateTime t && t.getDayOfMonth() == 1 && t.toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    /** 两个月份里较晚的。 */
    private static YearMonth max(YearMonth a, YearMonth b) { return a.isAfter(b) ? a : b; }

    /** 两个月份里较早的。 */
    private static YearMonth min(YearMonth a, YearMonth b) { return a.isBefore(b) ? a : b; }
}

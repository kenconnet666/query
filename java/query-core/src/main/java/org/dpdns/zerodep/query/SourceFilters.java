package org.dpdns.zerodep.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 数据库实例级的过滤条件：作用于表接口是某个类型（通常是公共列接口）的每张表。 */
final class SourceFilters {
    /** type 是表接口或它继承的接口；condition 收到这张表的表接口。 */
    record Filter(Class<?> type, Function<Object, Cond> condition) { }

    static final SourceFilters NONE = new SourceFilters(List.of());

    private final List<Filter> filters;

    SourceFilters(List<Filter> filters) { this.filters = List.copyOf(filters); }

    /** 这张表要追加的条件：FROM 的表加到 WHERE，JOIN 的表加到 ON。 */
    List<Cond> conditionsFor(Ref<?> ref) {
        if (filters.isEmpty() || !ref.source().filtered()) return List.of();
        List<Cond> out = new ArrayList<>();
        for (Filter f : filters) {
            if (f.type().isAssignableFrom(ref.entity())) out.add(f.condition().apply(ref.param()));
        }
        return out;
    }
}

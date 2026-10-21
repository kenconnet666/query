package org.dpdns.zerodep.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Database-wide conditions applied to every table source whose entity is assignable to a type. */
final class SourceFilters {
    record Filter(Class<?> type, Function<Ref<?>, Cond> condition) { }

    static final SourceFilters NONE = new SourceFilters(List.of());

    private final List<Filter> filters;

    SourceFilters(List<Filter> filters) { this.filters = List.copyOf(filters); }

    List<Cond> conditionsFor(Ref<?> ref) {
        if (filters.isEmpty() || !ref.source().filtered()) return List.of();
        List<Cond> out = new ArrayList<>();
        for (Filter f : filters) {
            if (f.type().isAssignableFrom(ref.entity())) out.add(f.condition().apply(ref));
        }
        return out;
    }
}

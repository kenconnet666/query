package org.dpdns.zerodep.query.dialect;

import java.util.List;
import java.util.stream.Collectors;

/** Final SQL text and its parameters in placeholder order. */
public record Rendered(String sql, List<Binding> bindings) {
    public Rendered {
        bindings = List.copyOf(bindings);
    }

    /** Database-side parameter values, after converters. */
    public List<Object> parameters() {
        return bindings.stream().map(Binding::databaseValue).collect(Collectors.toList());
    }

    @Override public String toString() { return sql + "\n-- parameters " + parameters(); }
}

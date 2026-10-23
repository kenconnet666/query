package org.dpdns.zerodep.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Columns selected by name, from {@link Sql#cols}. A column is named after its column method; a
 * function or aggregate over one column keeps that name ({@code sum(l.quantity())} is "quantity");
 * anything else needs {@code .as("name")}. Rows come back as maps, or as a class through
 * {@code into(X.class)}, which matches columns to properties by name.
 */
public final class Cols implements Selection<Map<String, Object>> {
    private final List<Expr<?>> items;
    private final List<String> names;

    Cols(List<? extends Expr<?>> items) {
        this.items = List.copyOf(items);
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < this.items.size(); i++) {
            String name = this.items.get(i).nameHint();
            if (name == null) {
                throw new IllegalArgumentException("Column " + (i + 1) + " of cols(...) has no name; add .as(\"name\")");
            }
            if (!used.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Two columns are named '" + name + "'; rename one with .as(\"name\")");
            }
            names.add(name);
        }
        this.names = List.copyOf(names);
    }

    /** These columns followed by more. */
    public Cols and(Expr<?>... more) {
        List<Expr<?>> all = new ArrayList<>(items);
        all.addAll(List.of(more));
        return new Cols(all);
    }

    List<Expr<?>> items() { return items; }

    List<String> names() { return names; }
}

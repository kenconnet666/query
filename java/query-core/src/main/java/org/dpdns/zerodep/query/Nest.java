package org.dpdns.zerodep.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Groups flat rows, such as a header joined to its lines, into parents with their children. One
 * query returns the rows; {@code Nest.into} shapes them in memory:
 * <pre>{@code
 * List<Order> orders = Nest.into(db.list(lines), Line::orderNo, Line::item, (first, items) -> new Order(first.orderNo(), items));
 * }</pre>
 */
public final class Nest {
    private Nest() { }

    /**
     * One parent per distinct key, in the order keys first appear, built from the key's first row and
     * its children. A child function that returns null, as for an unmatched LEFT JOIN, adds no child.
     * Composite keys are records or {@code List.of(a, b)}, compared with {@code equals}.
     */
    public static <R, K, C, P> List<P> into(List<? extends R> rows, Function<? super R, ? extends K> key,
                                           Function<? super R, ? extends C> child, BiFunction<? super R, List<C>, ? extends P> parent) {
        Map<K, R> firsts = new LinkedHashMap<>();
        Map<K, List<C>> children = new HashMap<>();
        for (R row : rows) {
            K k = key.apply(row);
            firsts.putIfAbsent(k, row);
            List<C> list = children.computeIfAbsent(k, ignored -> new ArrayList<>());
            C c = child.apply(row);
            if (c != null) list.add(c);
        }
        List<P> parents = new ArrayList<>(firsts.size());
        firsts.forEach((k, first) -> parents.add(parent.apply(first, List.copyOf(children.get(k)))));
        return parents;
    }
}

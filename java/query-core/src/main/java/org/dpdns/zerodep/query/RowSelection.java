package org.dpdns.zerodep.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A constructor fed by position, from {@code row(X::new, a, b, ...)}. Each argument is a column, a
 * nested row or {@code cols(...)}; javac checks every position against the constructor.
 */
public final class RowSelection<R> implements Selection<R> {
    @FunctionalInterface
    interface Builder<R> {
        R build(Object[] args);
    }

    private final Builder<R> builder;
    private final List<Selection<?>> parts;

    RowSelection(Builder<R> builder, Selection<?>... parts) {
        this.builder = builder;
        this.parts = List.of(parts);
    }

    /** The flat SELECT list of a selection, in reading order. */
    static void collect(Selection<?> s, List<Expr<?>> into) {
        if (s instanceof Expr<?> e) into.add(e);
        else if (s instanceof Cols c) into.addAll(c.items());
        else for (Selection<?> part : ((RowSelection<?>) s).parts) collect(part, into);
    }

    /** Reads one selection from a row, advancing the cursor over the columns it consumed. */
    static Object read(Selection<?> s, Object[] values, int[] cursor) {
        if (s instanceof Expr<?>) return values[cursor[0]++];
        if (s instanceof Cols c) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String name : c.names()) map.put(name, values[cursor[0]++]);
            return map;
        }
        RowSelection<?> row = (RowSelection<?>) s;
        Object[] args = new Object[row.parts.size()];
        for (int i = 0; i < args.length; i++) args[i] = read(row.parts.get(i), values, cursor);
        return row.builder.build(args);
    }
}

package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.meta.Getter;

import java.util.List;

/**
 * Reads values inside a projection lambda. The lambda runs once to record what it reads (that
 * becomes the SELECT list, and each read returns a harmless placeholder), then once per result row
 * with the decoded values. Reads must not depend on row values; a replay that reads differently
 * from the recording is rejected.
 */
public final class Row {
    private final List<Expr<?>> recorded;
    private final List<Object> shapes;
    private final Object[] values;
    private int cursor;

    private Row(List<Expr<?>> recorded, List<Object> shapes, Object[] values) {
        this.recorded = recorded;
        this.shapes = shapes;
        this.values = values;
    }

    static Row recording(List<Expr<?>> sink) { return new Row(sink, null, null); }

    /** @param shapes the recorded reads' shapes, computed once per query rather than per row */
    static Row replay(List<Object> shapes, Object[] values) { return new Row(null, shapes, values); }

    @SuppressWarnings("unchecked")
    public <T> T get(Expr<T> expr) {
        if (values == null) {
            recorded.add(expr);
            return expr.type().placeholder();
        }
        if (cursor >= shapes.size() || !shapes.get(cursor).equals(expr.shape())) {
            throw divergence();
        }
        return (T) values[cursor++];
    }

    public <E, T> T get(Ref<E> ref, Getter<? super E, T> getter) { return get(ref.col(getter)); }

    void finish() {
        if (values != null && cursor != shapes.size()) throw divergence();
    }

    private IllegalStateException divergence() {
        return new IllegalStateException("The projection read different columns than when it was recorded (read #" + (cursor + 1)
            + " of " + shapes.size() + "). Read every column unconditionally, then branch on the values.");
    }
}

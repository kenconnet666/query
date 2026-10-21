package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;

import java.util.List;

/** An OVER (...) specification, built from {@link #EMPTY} inside {@code over(w -> w.partitionBy(...))}. */
public final class Window implements Renderable {
    public static final Window EMPTY = new Window(List.of(), List.of(), null);

    private final List<Expr<?>> partition;
    private final List<Order> order;
    private final String frame;

    private Window(List<Expr<?>> partition, List<Order> order, String frame) {
        this.partition = partition;
        this.order = order;
        this.frame = frame;
    }

    public Window partitionBy(Expr<?>... keys) { return new Window(List.of(keys), order, frame); }

    public Window orderBy(Order... orders) { return new Window(partition, List.of(orders), frame); }

    /** ROWS BETWEEN start AND end; RANGE frames differ between databases and live in dialect packages. */
    public Window rows(Bound start, Bound end) { return new Window(partition, order, "ROWS BETWEEN " + start.sql + " AND " + end.sql); }

    @Override public void render(SqlWriter w) {
        String sep = "";
        if (!partition.isEmpty()) {
            w.sql("PARTITION BY ").join(partition, ", ");
            sep = " ";
        }
        if (!order.isEmpty()) {
            w.sql(sep).sql("ORDER BY ").join(order, ", ");
            sep = " ";
        }
        if (frame != null) w.sql(sep).sql(frame);
    }

    public static final class Bound {
        private final String sql;

        private Bound(String sql) { this.sql = sql; }

        public static Bound unboundedPreceding() { return new Bound("UNBOUNDED PRECEDING"); }
        public static Bound preceding(int rows) { return new Bound(nonNegative(rows) + " PRECEDING"); }
        public static Bound currentRow() { return new Bound("CURRENT ROW"); }
        public static Bound following(int rows) { return new Bound(nonNegative(rows) + " FOLLOWING"); }
        public static Bound unboundedFollowing() { return new Bound("UNBOUNDED FOLLOWING"); }

        private static int nonNegative(int rows) {
            if (rows < 0) throw new IllegalArgumentException("Frame offsets must not be negative");
            return rows;
        }
    }
}

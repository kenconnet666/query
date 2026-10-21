package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;

/** One ORDER BY item with its direction, from {@code asc()} or {@code desc()}. */
public final class Order implements Renderable, OrderItem {
    private final Expr<?> expr;
    private final boolean descending;
    private final Boolean nullsFirst;

    Order(Expr<?> expr, boolean descending, Boolean nullsFirst) {
        this.expr = expr;
        this.descending = descending;
        this.nullsFirst = nullsFirst;
    }

    public Order nullsFirst() { return new Order(expr, descending, true); }

    public Order nullsLast() { return new Order(expr, descending, false); }

    @Override public void render(SqlWriter w) { w.dialect().orderItem(w, expr, descending, nullsFirst); }
}

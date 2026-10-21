package org.dpdns.zerodep.query;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/** Several ORDER BY items in order, from {@link Sql#orders}. */
public final class OrderList extends AbstractList<Order> implements OrderItem {
    private final List<Order> items;

    OrderList(List<Order> items) { this.items = List.copyOf(items); }

    /** Flattens items into orders; a bare expression sorts ascending. */
    static List<Order> of(OrderItem... items) {
        List<Order> orders = new ArrayList<>();
        for (OrderItem item : items) {
            if (item instanceof Expr<?> e) orders.add(e.asc());
            else if (item instanceof Order o) orders.add(o);
            else orders.addAll((OrderList) item);
        }
        return orders;
    }

    @Override public Order get(int index) { return items.get(index); }

    @Override public int size() { return items.size(); }
}

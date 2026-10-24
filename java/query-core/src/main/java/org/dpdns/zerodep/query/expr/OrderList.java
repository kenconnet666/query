package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.Sql;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/** 按顺序排列的多个 ORDER BY 项，来自 {@link Sql#orders}。 */
public final class OrderList extends AbstractList<Order> implements OrderItem {
    /** 各排序项，不可变。 */
    private final List<Order> items;

    /** 由 {@link Sql#orders} 创建。 */
    public OrderList(List<Order> items) { this.items = List.copyOf(items); }

    /** 把各排序项摊平成 Order 列表；单独的表达式按升序。 */
    public static List<Order> of(OrderItem... items) {
        List<Order> orders = new ArrayList<>();
        for (OrderItem item : items) {
            if (item instanceof Expr<?> e) orders.add(e.asc());
            else if (item instanceof Order o) orders.add(o);
            else orders.addAll((OrderList) item);
        }
        return orders;
    }

    /** 第 index 个排序项。 */
    @Override public Order get(int index) { return items.get(index); }

    /** 排序项的个数。 */
    @Override public int size() { return items.size(); }
}

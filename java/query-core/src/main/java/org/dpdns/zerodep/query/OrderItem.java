package org.dpdns.zerodep.query;

/**
 * {@code orderBy} 和窗口的 {@code orderBy} 接受的排序项：一个表达式（升序）、
 * 来自 {@code asc()} 或 {@code desc()} 的 {@link Order}，或用 {@link Sql#orders} 组合的多个。
 */
public sealed interface OrderItem permits Expr, Order, OrderList { }

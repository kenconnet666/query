package org.dpdns.zerodep.query;

/**
 * What {@code orderBy} and a window's {@code orderBy} take: an expression (ascending), an
 * {@link Order} from {@code asc()} or {@code desc()}, or several from {@link Sql#orders}.
 */
public sealed interface OrderItem permits Expr, Order, OrderList { }

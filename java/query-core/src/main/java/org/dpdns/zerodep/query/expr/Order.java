package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;

/** 一个带方向的 ORDER BY 项，来自 {@code asc()} 或 {@code desc()}。 */
public final class Order implements Renderable, OrderItem {
    /** 排序依据的表达式。 */
    private final Expr<?> expr;

    /** 是否降序。 */
    private final boolean descending;

    /** NULL 排前面（true）、后面（false），null 表示用方言的默认顺序。 */
    private final Boolean nullsFirst;

    /** 由 {@link Expr#asc} 和 {@link Expr#desc} 创建。 */
    Order(Expr<?> expr, boolean descending, Boolean nullsFirst) {
        this.expr = expr;
        this.descending = descending;
        this.nullsFirst = nullsFirst;
    }

    /** NULL 排在最前。 */
    public Order nullsFirst() { return new Order(expr, descending, true); }

    /** NULL 排在最后。 */
    public Order nullsLast() { return new Order(expr, descending, false); }

    /** 按方言的写法输出排序项，NULL 的位置由方言写出。 */
    @Override public void render(SqlWriter w) { w.dialect().orderItem(w, expr, descending, nullsFirst); }
}

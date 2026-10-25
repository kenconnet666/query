package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.ExprNode;

/**
 * {@link Query#exclude} 去掉的一列：SQL 里写成 NULL，所以结果的形态不变，这一列读出来是 null。
 * 保留原来的表达式，渲染时用它找出要一起去掉的分组键。
 *
 * @param <T> 原来那一列的值类型
 */
final class Omitted<T> extends ExprNode<T> {
    /** 被去掉之前的表达式。 */
    final Expr<T> original;

    /** 包住 original。 */
    Omitted(Expr<T> original) {
        super(original.type());
        this.original = original;
    }

    /** 写出 {@code NULL}。 */
    @Override public void render(SqlWriter w) { w.sql("NULL"); }
}

package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.expr.typed.ExprDate;
import org.dpdns.zerodep.query.expr.typed.ExprDecimal;
import org.dpdns.zerodep.query.expr.typed.ExprText;

/**
 * 有自己方法的那些类型（{@link ExprText}、{@link ExprDecimal}、{@link ExprDate} 等）共同的父接口：F 是这一类型的表达式接口。
 * 列也实现它，F 仍是表达式接口而不是列接口（{@code ColText<T>} 是 {@code Family<T, ExprText<T>>}），
 * 所以泛型函数借它把结果还原成同一类型，例如 {@code lag(l.quantity(), 1).over(...)} 仍是 {@code ExprDecimal}，
 * {@code caseWhen(() -> ..., l.unit()).otherwise("-")} 仍是 {@code ExprText}。
 *
 * @param <T> 值的 Java 类型
 * @param <F> 这一类型的表达式接口
 */
public interface Family<T, F extends Expr<T>> extends Expr<T> {
    /** 把同一值类型的节点包成这一类型的表达式；供泛型函数构造结果，节点的渲染不变。 */
    F retype(Expr<T> node);
}

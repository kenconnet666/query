package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.type.SqlType;

import java.util.Objects;

/**
 * 表达式节点的基类：保存值类型，子类只需实现 {@link #render}。自定义的表达式节点也继承它。
 * 节点本身只有 {@link Expr} 上与类型无关的方法；要用某种类型的方法时，用该类型接口的 {@code of} 包一层，
 * 例如 {@code ExprText.of(node).upper()}。
 *
 * @param <T> 值的 Java 类型
 */
public abstract class ExprNode<T> implements Expr<T> {
    /** 表达式的值类型，决定参数的绑定和结果的读取。 */
    private final SqlType<T> type;

    /** 供表达式节点继承；type 不能为 null。 */
    protected ExprNode(SqlType<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** 值类型。 */
    @Override public final SqlType<T> type() { return type; }
}

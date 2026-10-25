package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;

/**
 * 条件节点。条件方法（{@code eq}、{@code in} 等）把它写进当前的条件块（见 {@link Conditions}），使用方一般不直接拿到它；
 * 方言包和自定义条件继承它。与 {@code Expr<Boolean>} 分开，因为有的数据库（HANA）不能把谓词直接放进投影，要当值用时写 {@code bool(() -> ...)}。
 * {@link #EMPTY} 表示“没有条件”，与其他条件组合时会被丢掉。
 */
public abstract class Cond implements Renderable {
    /** 没有条件。渲染前一定会被丢掉，直接渲染它是编程错误。 */
    public static final Cond EMPTY = new Cond() {
        @Override public void render(SqlWriter w) {
            throw new IllegalStateException("Cond.EMPTY is dropped before rendering");
        }

        @Override public String toString() { return "EMPTY"; }
    };

    /** 供自定义条件节点继承；实现 {@link #render}。 */
    protected Cond() { }

    /** 是否是 {@link #EMPTY}。 */
    public final boolean isEmpty() { return this == EMPTY; }
}

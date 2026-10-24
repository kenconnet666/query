package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;

import java.util.List;

/**
 * 条件。与 {@code Expr<Boolean>} 分开，因为有的数据库（HANA）不能把谓词直接放进投影。
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

    /** 两个条件同时成立；任一方是 {@link #EMPTY} 时结果就是另一方。 */
    public final Cond and(Cond other) { return Nodes.all(List.of(this, other)); }

    /** 两个条件至少一个成立；任一方是 {@link #EMPTY} 时结果就是另一方。 */
    public final Cond or(Cond other) { return Nodes.any(List.of(this, other)); }

    /** 条件取反。 */
    public final Cond not() { return Nodes.not(this); }

    /** 是否是 {@link #EMPTY}。 */
    public final boolean isEmpty() { return this == EMPTY; }
}

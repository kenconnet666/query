package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;

import java.util.List;

/**
 * A condition. Kept apart from {@code Expr<Boolean>} because some databases (HANA) cannot project a
 * predicate directly. {@link #EMPTY} stands for "no condition" and disappears when combined.
 */
public abstract class Cond implements Renderable {
    public static final Cond EMPTY = new Cond() {
        @Override public void render(SqlWriter w) {
            throw new IllegalStateException("Cond.EMPTY is dropped before rendering");
        }

        @Override public String toString() { return "EMPTY"; }
    };

    protected Cond() { }

    public final Cond and(Cond other) { return Nodes.all(List.of(this, other)); }

    public final Cond or(Cond other) { return Nodes.any(List.of(this, other)); }

    public final Cond not() { return Nodes.not(this); }

    public final boolean isEmpty() { return this == EMPTY; }
}

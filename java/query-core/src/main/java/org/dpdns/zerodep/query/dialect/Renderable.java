package org.dpdns.zerodep.query.dialect;

/** Anything that writes itself as SQL. Expressions, conditions, sources and clauses all are. */
@FunctionalInterface
public interface Renderable {
    void render(SqlWriter w);
}

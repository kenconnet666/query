package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

/** A subquery in FROM or JOIN, with the shape class of its named projection. */
final class Derived<E> implements Source<E> {
    private final Query<E> query;
    private final Class<E> shape;

    Derived(Query<E> query) {
        this.query = query;
        this.shape = query.shape();
    }

    @Override public Class<E> entity() { return shape; }

    @Override public void renderSource(SqlWriter w) {
        w.sql("(");
        QueryRenderer.named(w, query);
        w.sql(")");
    }

    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.name(); }

    @Override public String preferredAlias() { return "t"; }
}

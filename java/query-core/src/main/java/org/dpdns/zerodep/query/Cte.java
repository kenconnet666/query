package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.CteDefinition;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

/**
 * A named common table expression. Its rows are described by the shape class of a
 * {@code selectAs(Shape.class, ...)} query; columns are the shape's property names. However deep
 * it is referenced, it renders once in the statement's leading WITH.
 */
public final class Cte<E> implements Source<E>, CteDefinition {
    private final String name;
    private final Query<E> body;
    private final Class<E> shape;

    Cte(String name, Query<E> body) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A CTE needs a name");
        this.name = name;
        this.body = body;
        this.shape = body.shape();
    }

    @Override public String name() { return name; }

    @Override public Class<E> entity() { return shape; }

    @Override public void render(SqlWriter w) { QueryRenderer.named(w, body); }

    @Override public void renderSource(SqlWriter w) {
        w.cte(this);
        w.identifier(name);
    }

    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.name(); }

    @Override public String preferredAlias() { return name; }
}

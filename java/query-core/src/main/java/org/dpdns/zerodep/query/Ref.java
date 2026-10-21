package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.Getter;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

/**
 * One occurrence of a source in a query, handed to lambdas as a typed parameter. The getter must
 * belong to E or to a type E extends or implements, so a column of a table outside the scope does
 * not compile. Aliases are assigned per statement when rendering.
 */
public final class Ref<E> {
    private final Source<E> source;

    Ref(Source<E> source) { this.source = source; }

    /** A column of this source; {@code ? super E} admits getters declared on base classes and interfaces. */
    public <T> Expr<T> col(Getter<? super E, T> getter) {
        return new Nodes.Column<>(this, Metadata.property(source.entity(), getter));
    }

    public Class<E> entity() { return source.entity(); }

    public Source<E> source() { return source; }

    String alias(SqlWriter w) { return w.aliasFor(this, source.preferredAlias()); }

    void renderColumn(SqlWriter w, PropertyMeta property) {
        Object naming = w.attribute(NamingStrategy.class);
        w.alias(alias(w)).sql(".").identifier(source.column(property, naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming));
    }

    void renderSource(SqlWriter w) {
        source.renderSource(w);
        w.sql(" ").alias(alias(w));
    }

    @Override public String toString() { return "Ref(" + source.entity().getSimpleName() + ")"; }
}

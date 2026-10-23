package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.Getter;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

import java.util.List;

/**
 * One occurrence of a source in a query. A table occurrence reaches lambdas as its table interface;
 * a CTE, derived table or constant rows reach them as this Ref, read with {@code r.col(Row::total)}.
 * Aliases are assigned per statement when rendering.
 */
public final class Ref<E> {
    private final Source<E> source;
    private final Object param;

    private Ref(Source<E> source) {
        this.source = source;
        this.param = source.entity().isInterface() ? TableProxy.create(this) : this;
    }

    static <T extends Entity> Ref<T> table(Class<T> table) { return new Ref<>(new TableSource<>(table)); }

    static <E> Ref<E> of(Source<E> source) { return new Ref<>(source); }

    /** A column of a CTE, derived table or constant rows; {@code ? super E} admits getters of base classes and interfaces. */
    public <T> Col<T> col(Getter<? super E, T> getter) {
        if (source.entity().isInterface()) {
            throw new IllegalArgumentException("Columns of " + source.entity().getSimpleName() + " are its methods, e.g. t.material()");
        }
        return new Col<>(this, Metadata.property(source.entity(), getter));
    }

    public Class<E> entity() { return source.entity(); }

    public Source<E> source() { return source; }

    /** What lambdas receive: the table interface for a table, this Ref otherwise. */
    @SuppressWarnings("unchecked")
    <P> P param() { return (P) param; }

    String alias(SqlWriter w) { return w.aliasFor(this, source.preferredAlias()); }

    /** The qualified column, through the property's {@code @ReadAs} template when it has one. */
    void renderColumn(SqlWriter w, PropertyMeta property) {
        Object naming = w.attribute(NamingStrategy.class);
        String column = source.column(property, naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming);
        Renderable qualified = c -> c.alias(alias(c)).sql(".").identifier(column);
        if (property.read() == null) qualified.render(w);
        else Nodes.renderTemplate(w, property.read(), List.of(qualified));
    }

    void renderSource(SqlWriter w) {
        source.renderSource(w);
        w.sql(" ").alias(alias(w));
    }

    @Override public String toString() { return "Ref(" + source.entity().getSimpleName() + ")"; }
}

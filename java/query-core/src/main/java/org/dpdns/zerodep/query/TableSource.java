package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

/** A table entity, described by {@code @Table}. */
final class TableSource<E> implements Source<E> {
    private final EntityMeta<E> meta;

    TableSource(Class<E> entity) {
        this.meta = Metadata.entity(entity);
        if (!meta.isTable()) {
            throw new IllegalArgumentException(entity.getSimpleName() + " has no @Table; read it from a CTE or a derived query instead");
        }
    }

    @Override public Class<E> entity() { return meta.type(); }

    @Override public void renderSource(SqlWriter w) {
        if (meta.schema() != null) w.identifier(meta.schema()).sql(".");
        w.identifier(meta.table());
    }

    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.columnName(naming); }

    @Override public boolean filtered() { return true; }
}

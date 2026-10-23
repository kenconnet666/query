package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

/** A table interface, described by {@code @Table}. */
final class TableSource<E> implements Source<E> {
    private final EntityMeta<E> meta;

    TableSource(Class<E> table) {
        if (!table.isInterface()) {
            throw new IllegalArgumentException(table.getSimpleName() + " is a class; a table is an interface extending Entity, "
                + "and rows of a class are read from a CTE, a derived table or values(...)");
        }
        this.meta = Metadata.entity(table);
        if (!meta.isTable()) throw new IllegalArgumentException(table.getSimpleName() + " has no @Table");
    }

    @Override public Class<E> entity() { return meta.type(); }

    @Override public void renderSource(SqlWriter w) {
        if (meta.schema() != null) w.identifier(meta.schema()).sql(".");
        w.identifier(meta.table());
    }

    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.columnName(naming); }

    @Override public boolean filtered() { return true; }
}

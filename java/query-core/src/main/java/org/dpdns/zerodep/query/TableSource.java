package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

/** A table interface, described by {@code @Table}. */
final class TableSource<E> implements Source<E> {
    /** 表接口的元数据。 */
    private final EntityMeta<E> meta;

    /** table 必须是带 {@code @Table} 的接口，否则抛出异常。 */
    TableSource(Class<E> table) {
        if (!table.isInterface()) {
            throw new IllegalArgumentException(table.getSimpleName() + " is a class; a table is an interface extending Entity, "
                + "and rows of a class are read from a CTE, a derived table or values(...)");
        }
        this.meta = Metadata.entity(table);
        if (!meta.isTable()) throw new IllegalArgumentException(table.getSimpleName() + " has no @Table");
    }

    /** 表接口。 */
    @Override public Class<E> entity() { return meta.type(); }

    /** 写出 {@code schema.table}，没有 schema 时只写表名。 */
    @Override public void renderSource(SqlWriter w) {
        if (meta.schema() != null) w.identifier(meta.schema()).sql(".");
        w.identifier(meta.table());
    }

    /** 列名取自 {@code @Column}，没有时按命名策略推导。 */
    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.columnName(naming); }

    /** 表要套用数据库实例的全局过滤器。 */
    @Override public boolean filtered() { return true; }
}

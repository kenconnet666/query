package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.type.SqlType;

import java.lang.reflect.Field;

/**
 * One column of a table interface, or one property of a result or row class.
 *
 * @param column explicit column name from {@code @Column}, or null to let the naming strategy decide
 * @param field  the backing field of a class property; null for a column method of a table interface
 * @param read   the {@code @ReadAs} template the column is read through, or null
 */
public record PropertyMeta(Class<?> entity, String name, String column, SqlType<?> type, Field field, String read) {

    public String columnName(NamingStrategy naming) {
        return column != null ? column : naming.column(name);
    }

    public Class<?> javaType() { return type.javaType(); }
}

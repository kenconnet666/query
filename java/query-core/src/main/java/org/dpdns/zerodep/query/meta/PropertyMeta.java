package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.annotation.MapFrom;
import org.dpdns.zerodep.query.type.SqlType;

import java.lang.reflect.Field;

/**
 * One property of an entity or shape class.
 *
 * @param column explicit column name from {@code @Column}, or null to let the naming strategy decide
 * @param read   the {@code @ReadAs} template the column is read through, or null
 */
public record PropertyMeta(Class<?> entity, String name, String column, SqlType<?> type, Field field, MapFrom mapFrom, String read) {

    public String columnName(NamingStrategy naming) {
        return column != null ? column : naming.column(name);
    }

    public Class<?> javaType() { return type.javaType(); }
}

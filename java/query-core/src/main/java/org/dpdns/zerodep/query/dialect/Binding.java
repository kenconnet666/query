package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.type.SqlType;

/** A bound parameter: the Java value and the type that converts and binds it. */
public record Binding(Object value, SqlType<?> type) {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object databaseValue() { return ((SqlType) type).toDatabase(value); }
}

package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.type.SqlType;

/**
 * 一个绑定参数：Java 值，以及负责转换和绑定它的类型。
 *
 * @param value 绑定前的 Java 值，可以为 null
 * @param type  把该值转成库中存储值并绑定到语句的类型
 */
public record Binding(Object value, SqlType<?> type) {

    /** 经过类型的转换器之后，实际交给 JDBC 的值。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object databaseValue() { return ((SqlType) type).toDatabase(value); }
}

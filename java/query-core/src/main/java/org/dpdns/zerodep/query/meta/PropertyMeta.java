package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.type.SqlType;

import java.lang.reflect.Field;

/**
 * 表接口的一列，或结果类、行类的一个属性。
 *
 * @param entity 属性所属的类型
 * @param name   属性名（表接口里是列方法名）
 * @param column {@code @Column} 里显式指定的列名；为 null 时由命名策略决定
 * @param type   值的 SQL 类型，已包含 {@code @ConvertWith} 声明的转换器
 * @param field  类属性背后的字段；表接口的列方法没有字段，为 null
 * @param read   该列读取时套用的 {@code @ReadAs} 模板，没有则为 null
 */
public record PropertyMeta(Class<?> entity, String name, String column, SqlType<?> type, Field field, String read) {

    /** 实际列名：优先用显式指定的，否则交给命名策略。 */
    public String columnName(NamingStrategy naming) {
        return column != null ? column : naming.column(name);
    }

    /** 属性值的 Java 类型。 */
    public Class<?> javaType() { return type.javaType(); }
}

package org.dpdns.zerodep.query.meta;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 表接口的表名与列，或结果类、行类的属性（见 {@link Metadata#entity(Class)}）。
 *
 * @param <E> 描述的类型
 */
public final class EntityMeta<E> {
    private final Class<E> type;
    private final String table;
    private final String schema;
    private final List<PropertyMeta> properties;
    private final Map<String, PropertyMeta> byName;

    /** 仅由 {@link Metadata} 构造；属性名在同一类型内必须唯一。 */
    EntityMeta(Class<E> type, String table, String schema, List<PropertyMeta> properties) {
        this.type = type;
        this.table = table;
        this.schema = schema;
        this.properties = List.copyOf(properties);
        this.byName = properties.stream().collect(Collectors.toUnmodifiableMap(PropertyMeta::name, Function.identity()));
    }

    /** 被描述的类型。 */
    public Class<E> type() { return type; }

    /** 表名；类型上没有 {@code @Table} 时为 null。 */
    public String table() { return table; }

    /** 表所在的 schema；没有声明时为 null。 */
    public String schema() { return schema; }

    /** 是否是表（声明了 {@code @Table}），而不是结果类或行类。 */
    public boolean isTable() { return table != null; }

    /** 类的属性按声明顺序排列，父类在前；表的列按名称排序。 */
    public List<PropertyMeta> properties() { return properties; }

    /** 按名称取属性；不存在时抛出异常，并列出所有已知属性名。 */
    public PropertyMeta property(String name) {
        PropertyMeta p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " has no property '" + name + "'; known: " + byName.keySet());
        }
        return p;
    }

    /** 是否存在该名称的属性。 */
    public boolean has(String name) { return byName.containsKey(name); }
}

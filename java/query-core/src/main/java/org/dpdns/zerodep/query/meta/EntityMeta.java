package org.dpdns.zerodep.query.meta;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Table and properties of a class, scanned once (see {@link Metadata#entity(Class)}). */
public final class EntityMeta<E> {
    private final Class<E> type;
    private final String table;
    private final String schema;
    private final List<PropertyMeta> properties;
    private final Map<String, PropertyMeta> byName;

    EntityMeta(Class<E> type, String table, String schema, List<PropertyMeta> properties) {
        this.type = type;
        this.table = table;
        this.schema = schema;
        this.properties = List.copyOf(properties);
        this.byName = properties.stream().collect(Collectors.toUnmodifiableMap(PropertyMeta::name, Function.identity()));
    }

    public Class<E> type() { return type; }

    /** Table name, or null for a class without {@code @Table}. */
    public String table() { return table; }

    public String schema() { return schema; }

    public boolean isTable() { return table != null; }

    /** Properties in declaration order, base class first. */
    public List<PropertyMeta> properties() { return properties; }

    public PropertyMeta property(String name) {
        PropertyMeta p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " has no property '" + name + "'; known: " + byName.keySet());
        }
        return p;
    }

    public boolean has(String name) { return byName.containsKey(name); }
}

package org.dpdns.zerodep.query.type;

import java.util.Objects;

/**
 * The Java type an expression produces, plus how it is stored: the storage type is what JDBC reads
 * and writes, and an optional converter bridges the two. Reading and writing the storage type is
 * left to the database's {@link TypeRegistry}, so a dialect can override JDBC details per type.
 */
public final class SqlType<T> {
    private static final ClassValue<SqlType<?>> PLAIN = new ClassValue<>() {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected SqlType<?> computeValue(Class<?> type) {
            if (type.isEnum()) return new SqlType(type, String.class, new EnumNameConverter(type));
            return new SqlType<>(type, type, null);
        }
    };

    private final Class<T> javaType;
    private final Class<?> storageType;
    private final Converter<T, Object> converter;

    private SqlType(Class<T> javaType, Class<?> storageType, Converter<T, Object> converter) {
        this.javaType = javaType;
        this.storageType = storageType;
        this.converter = converter;
    }

    /** The default type for a Java class; enums are stored by name. */
    @SuppressWarnings("unchecked")
    public static <T> SqlType<T> of(Class<T> javaType) {
        return (SqlType<T>) PLAIN.get(box(Objects.requireNonNull(javaType, "javaType")));
    }

    /** A type stored through a converter, e.g. a LocalDate kept as 'yyyyMMdd' text. */
    @SuppressWarnings("unchecked")
    public static <J, D> SqlType<J> converted(Class<J> javaType, Class<D> storageType, Converter<J, D> converter) {
        return new SqlType<>((Class<J>) box(javaType), box(storageType), (Converter<J, Object>) converter);
    }

    /** A type stored through a converter class referenced from an annotation. */
    @SuppressWarnings("unchecked")
    public static <J> SqlType<J> converted(Class<J> javaType, Class<?> converterType) {
        Class<?> declared = Converters.javaType(converterType);
        if (!box(declared).isAssignableFrom(box(javaType))) {
            throw new IllegalArgumentException(converterType.getSimpleName() + " converts " + declared.getSimpleName()
                + ", but the field is " + javaType.getSimpleName());
        }
        return new SqlType<>((Class<J>) box(javaType), Converters.storageType(converterType),
            (Converter<J, Object>) (Converter<?, ?>) Converters.instance(converterType));
    }

    public Class<T> javaType() { return javaType; }

    public Class<?> storageType() { return storageType; }

    public boolean converted() { return converter != null; }

    /** Java value to the value handed to JDBC; null stays null. */
    public Object toDatabase(T value) {
        if (value == null) return null;
        return converter == null ? value : converter.toDatabase(value);
    }

    /** Value read through JDBC (already of the storage type) to the Java value; null stays null. */
    @SuppressWarnings("unchecked")
    public T fromDatabase(Object raw) {
        if (raw == null) return null;
        return converter == null ? (T) raw : converter.fromDatabase(raw);
    }

    /** A harmless stand-in value, used while recording a projection lambda. */
    @SuppressWarnings("unchecked")
    public T placeholder() { return (T) TypeRegistry.placeholder(javaType); }

    @Override public String toString() {
        return converter == null ? javaType.getSimpleName() : javaType.getSimpleName() + " as " + storageType.getSimpleName();
    }

    public static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static final class EnumNameConverter<E extends Enum<E>> implements Converter<E, Object> {
        private final Class<E> type;

        EnumNameConverter(Class<E> type) { this.type = type; }

        @Override public Object toDatabase(E value) { return value.name(); }
        @Override public E fromDatabase(Object value) { return Enum.valueOf(type, value.toString()); }
    }
}

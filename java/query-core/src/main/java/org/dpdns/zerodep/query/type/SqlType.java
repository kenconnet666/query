package org.dpdns.zerodep.query.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 表达式产生的 Java 类型，以及它的存储方式：存储类型是 JDBC 实际读写的类型，
 * 可选的转换器在两者之间转换。存储类型的读写交给数据库的 {@link TypeRegistry}，
 * 因此方言可以按类型覆盖 JDBC 的细节。
 *
 * @param <T> Java 类型
 */
public final class SqlType<T> {
    /** 无转换器的默认类型，按 Java 类缓存。 */
    private static final ClassValue<SqlType<?>> PLAIN = new ClassValue<>() {
        /** 每个 Java 类一个不带转换器的类型。 */
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected SqlType<?> computeValue(Class<?> type) {
            if (type.isEnum() && CodeEnum.class.isAssignableFrom(type)) {
                EnumCodeConverter codes = new EnumCodeConverter(type);
                return new SqlType(type, codes.codeType, codes);
            }
            if (type.isEnum()) return new SqlType(type, String.class, new EnumNameConverter(type));
            return new SqlType<>(type, type, null);
        }
    };

    private final Class<T> javaType;
    private final Class<?> storageType;
    private final Converter<T, Object> converter;

    /** 由 of 与 converted 创建。 */
    private SqlType(Class<T> javaType, Class<?> storageType, Converter<T, Object> converter) {
        this.javaType = javaType;
        this.storageType = storageType;
        this.converter = converter;
    }

    /** Java 类的默认类型；枚举按名字存储，实现了 {@link CodeEnum} 的按编码存储。基本类型会先装箱。 */
    @SuppressWarnings("unchecked")
    public static <T> SqlType<T> of(Class<T> javaType) {
        return (SqlType<T>) PLAIN.get(box(Objects.requireNonNull(javaType, "javaType")));
    }

    /** 通过转换器存储的类型，例如以 'yyyyMMdd' 文本保存的 LocalDate。 */
    @SuppressWarnings("unchecked")
    public static <J, D> SqlType<J> converted(Class<J> javaType, Class<D> storageType, Converter<J, D> converter) {
        return new SqlType<>((Class<J>) box(javaType), box(storageType), (Converter<J, Object>) converter);
    }

    /** 通过注解引用的转换器类存储的类型；转换器的 Java 类型与 {@code javaType} 不兼容时抛出异常。 */
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

    /** 应用代码看到的 Java 类型。 */
    public Class<T> javaType() { return javaType; }

    /** JDBC 实际读写的存储类型。 */
    public Class<?> storageType() { return storageType; }

    /** 是否经过转换器。 */
    public boolean converted() { return converter != null; }

    /** Java 值转成交给 JDBC 的值；null 保持 null。 */
    public Object toDatabase(T value) {
        if (value == null) return null;
        return converter == null ? value : converter.toDatabase(value);
    }

    /** 把经 JDBC 读到的值（已是存储类型）转成 Java 值；null 保持 null。 */
    @SuppressWarnings("unchecked")
    public T fromDatabase(Object raw) {
        if (raw == null) return null;
        return converter == null ? (T) raw : converter.fromDatabase(raw);
    }

    /** 一个无害的占位值，在录制投影 lambda 时使用。 */
    @SuppressWarnings("unchecked")
    public T placeholder() { return (T) TypeRegistry.placeholder(javaType); }

    @Override public String toString() {
        return converter == null ? javaType.getSimpleName() : javaType.getSimpleName() + " as " + storageType.getSimpleName();
    }

    /** 基本类型装箱为对应的包装类，其他类型原样返回。 */
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

    /** 实现了 {@link CodeEnum} 的枚举与其编码之间的转换；编码的类型取自枚举实现的 {@code CodeEnum<C>}。 */
    private static final class EnumCodeConverter<E extends Enum<E>> implements Converter<E, Object> {
        private final Class<E> type;
        private final Class<?> codeType;
        private final Map<Object, E> byCode = new HashMap<>();

        EnumCodeConverter(Class<E> type) {
            this.type = type;
            this.codeType = codeType(type);
            for (E constant : type.getEnumConstants()) {
                Object code = ((CodeEnum<?>) constant).code();
                if (code == null) throw new IllegalArgumentException(type.getSimpleName() + "." + constant.name() + " has a null code");
                if (byCode.put(key(code), constant) != null) throw new IllegalArgumentException(type.getSimpleName() + " repeats the code " + code);
            }
        }

        @Override public Object toDatabase(E value) { return ((CodeEnum<?>) value).code(); }

        @Override public E fromDatabase(Object value) {
            E constant = byCode.get(key(value));
            if (constant == null) throw new IllegalArgumentException(type.getSimpleName() + " has no constant with the code " + value);
            return constant;
        }

        /** 编码比较时的键：整数统一成 Long，驱动读出的 Integer、Long、BigDecimal 都能对上。 */
        private static Object key(Object code) {
            if (code instanceof Number n && !(code instanceof Double) && !(code instanceof Float)) return n.longValue();
            return code;
        }

        /** 枚举实现的 {@code CodeEnum<C>} 里的 C；写成原始类型时报错。 */
        private static Class<?> codeType(Class<?> type) {
            for (Type t : type.getGenericInterfaces()) {
                if (t instanceof ParameterizedType p && p.getRawType() == CodeEnum.class && p.getActualTypeArguments()[0] instanceof Class<?> c) return c;
            }
            throw new IllegalArgumentException(type.getSimpleName() + " must implement CodeEnum<C> with a concrete code type, e.g. CodeEnum<String>");
        }
    }

    /** 枚举与其名字之间的转换。 */
    private static final class EnumNameConverter<E extends Enum<E>> implements Converter<E, Object> {
        private final Class<E> type;

        EnumNameConverter(Class<E> type) { this.type = type; }

        @Override public Object toDatabase(E value) { return value.name(); }
        @Override public E fromDatabase(Object value) { return Enum.valueOf(type, value.toString()); }
    }
}

package org.dpdns.zerodep.query.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/** Converter instances and their declared type arguments, resolved once per converter class. */
public final class Converters {
    private record Info(Converter<Object, Object> instance, Class<?> javaType, Class<?> storageType) { }

    private static final ClassValue<Info> INFO = new ClassValue<>() {
        @Override
        protected Info computeValue(Class<?> type) {
            Class<?>[] args = typeArguments(type);
            try {
                var ctor = type.getDeclaredConstructor();
                ctor.setAccessible(true);
                @SuppressWarnings("unchecked")
                Converter<Object, Object> instance = (Converter<Object, Object>) ctor.newInstance();
                return new Info(instance, args[0], args[1]);
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(type.getName() + " needs a no-argument constructor to be used as a converter", e);
            }
        }
    };

    private Converters() { }

    public static Converter<Object, Object> instance(Class<?> converterType) { return INFO.get(converterType).instance(); }

    public static Class<?> javaType(Class<?> converterType) { return INFO.get(converterType).javaType(); }

    public static Class<?> storageType(Class<?> converterType) { return INFO.get(converterType).storageType(); }

    /** Walks the class hierarchy to find the concrete J and D of Converter<J, D>. */
    static Class<?>[] typeArguments(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Type t : c.getGenericInterfaces()) {
                if (t instanceof ParameterizedType p && p.getRawType() == Converter.class) {
                    return new Class<?>[] {raw(p.getActualTypeArguments()[0], type), raw(p.getActualTypeArguments()[1], type)};
                }
            }
        }
        throw new IllegalArgumentException(type.getName() + " must implement Converter<J, D> with concrete type arguments");
    }

    private static Class<?> raw(Type t, Class<?> owner) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        if (t instanceof TypeVariable<?>) {
            throw new IllegalArgumentException(owner.getName() + " leaves a Converter type argument generic; declare concrete types");
        }
        throw new IllegalArgumentException("Unsupported converter type argument " + t + " in " + owner.getName());
    }
}

package org.dpdns.zerodep.query.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/** 转换器实例及其声明的类型参数，每个转换器类只解析一次并缓存。 */
public final class Converters {
    /** 一个转换器类解析后的结果：共享实例，以及 J 和 D 的具体类型。 */
    private record Info(Converter<Object, Object> instance, Class<?> javaType, Class<?> storageType) { }

    private static final ClassValue<Info> INFO = new ClassValue<>() {
        /** 读取转换器类实现的 Converter 的类型参数，并实例化它。 */
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

    /** 工具类，不能实例化。 */
    private Converters() { }

    /** 转换器类的共享实例。 */
    public static Converter<Object, Object> instance(Class<?> converterType) { return INFO.get(converterType).instance(); }

    /** 转换器声明的 Java 类型 J。 */
    public static Class<?> javaType(Class<?> converterType) { return INFO.get(converterType).javaType(); }

    /** 转换器声明的存储类型 D。 */
    public static Class<?> storageType(Class<?> converterType) { return INFO.get(converterType).storageType(); }

    /** 沿类的继承链向上查找 Converter<J, D> 中具体的 J 和 D。 */
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

    /** 取类型参数的原始类；仍是泛型变量或不支持的形式时抛出异常。 */
    private static Class<?> raw(Type t, Class<?> owner) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        if (t instanceof TypeVariable<?>) {
            throw new IllegalArgumentException(owner.getName() + " leaves a Converter type argument generic; declare concrete types");
        }
        throw new IllegalArgumentException("Unsupported converter type argument " + t + " in " + owner.getName());
    }
}

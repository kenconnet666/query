package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ConvertWith;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.MapFrom;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.type.Converter;
import org.dpdns.zerodep.query.type.SqlType;

import java.lang.annotation.Annotation;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazily built, cached metadata. Entity scans and getter resolutions are keyed by class through
 * {@link ClassValue}, so they are computed once and never keep a class loader alive.
 */
public final class Metadata {
    private static final ClassValue<EntityMeta<?>> ENTITIES = new ClassValue<>() {
        @Override
        protected EntityMeta<?> computeValue(Class<?> type) { return scan(type); }
    };

    /** Keyed by the lambda's class; filled on first use because resolving needs an instance. */
    private static final ClassValue<AtomicReference<GetterInfo>> GETTERS = new ClassValue<>() {
        @Override
        protected AtomicReference<GetterInfo> computeValue(Class<?> type) { return new AtomicReference<>(); }
    };

    /** @param returnType the getter's declared return type, or null when it cannot be loaded */
    private record GetterInfo(String property, Class<?> returnType) { }

    private Metadata() { }

    @SuppressWarnings("unchecked")
    public static <E> EntityMeta<E> entity(Class<E> type) { return (EntityMeta<E>) ENTITIES.get(type); }

    /** The property a getter reference names: getX/isX for beans, the accessor name for records. */
    public static <E> String propertyName(Getter<E, ?> getter) { return info(getter).property(); }

    /**
     * Resolves a getter against a concrete entity. The getter may be declared on a base class or an
     * interface the entity implements; resolution is by property name on the entity itself.
     */
    public static <E> PropertyMeta property(Class<E> entity, Getter<? super E, ?> getter) {
        GetterInfo info = info(getter);
        PropertyMeta property = entity(entity).property(info.property());
        Class<?> returned = info.returnType();
        if (returned != null && !SqlType.box(returned).isAssignableFrom(property.javaType())) {
            throw new IllegalArgumentException("Getter for '" + info.property() + "' returns " + returned.getSimpleName()
                + " but " + entity.getSimpleName() + "." + info.property() + " is " + property.javaType().getSimpleName());
        }
        return property;
    }

    private static GetterInfo info(Getter<?, ?> getter) {
        AtomicReference<GetterInfo> holder = GETTERS.get(getter.getClass());
        GetterInfo info = holder.get();
        if (info == null) {
            info = resolve(getter);
            holder.set(info);
        }
        return info;
    }

    private static GetterInfo resolve(Getter<?, ?> getter) {
        SerializedLambda lambda;
        try {
            Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            lambda = (SerializedLambda) writeReplace.invoke(getter);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalArgumentException("Expected a getter reference such as Lips::getMaterial, got " + getter.getClass(), e);
        }
        String method = lambda.getImplMethodName();
        if (method.startsWith("lambda$")) {
            throw new IllegalArgumentException("Use a method reference (Lips::getMaterial), not a lambda, to name a column");
        }
        String signature = lambda.getImplMethodSignature();
        String returned = signature.substring(signature.indexOf(')') + 1);
        ClassLoader loader = getter.getClass().getClassLoader();
        boolean record = isRecordClass(lambda.getImplClass(), loader);
        String property = record ? method
            : method.startsWith("get") && method.length() > 3 ? decapitalize(method.substring(3))
            : method.startsWith("is") && method.length() > 2 && returned.equals("Z") ? decapitalize(method.substring(2))
            : method;
        return new GetterInfo(property, descriptorType(returned, loader));
    }

    private static boolean isRecordClass(String internalName, ClassLoader loader) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, loader).isRecord();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Class<?> descriptorType(String d, ClassLoader loader) {
        return switch (d) {
            case "I" -> int.class;
            case "J" -> long.class;
            case "D" -> double.class;
            case "Z" -> boolean.class;
            case "F" -> float.class;
            case "S" -> short.class;
            case "B" -> byte.class;
            case "C" -> char.class;
            default -> {
                if (!d.startsWith("L")) yield null;
                try {
                    yield Class.forName(d.substring(1, d.length() - 1).replace('/', '.'), false, loader);
                } catch (ClassNotFoundException e) {
                    yield null;
                }
            }
        };
    }

    private static <E> EntityMeta<E> scan(Class<E> type) {
        Table table = type.getAnnotation(Table.class);
        List<PropertyMeta> properties = new ArrayList<>();
        for (Field f : fields(type)) properties.add(describe(type, f));
        return new EntityMeta<>(type, table == null ? null : table.value(),
            table == null || table.schema().isEmpty() ? null : table.schema(), properties);
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent rc : type.getRecordComponents()) {
                try {
                    out.add(type.getDeclaredField(rc.getName()));
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException(e);
                }
            }
            return out;
        }
        Deque<Class<?>> chain = new ArrayDeque<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) chain.push(c);
        for (Class<?> c : chain) {
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic() || f.isAnnotationPresent(Ignore.class)) continue;
                out.add(f);
            }
        }
        return out;
    }

    private static PropertyMeta describe(Class<?> owner, Field f) {
        Column column = f.getAnnotation(Column.class);
        Class<?> converter = column != null && column.converter() != Converter.class ? column.converter() : null;
        for (Annotation a : f.getAnnotations()) {
            ConvertWith composed = a.annotationType().getAnnotation(ConvertWith.class);
            if (composed == null) continue;
            if (converter != null && converter != composed.value()) {
                throw new IllegalArgumentException(owner.getSimpleName() + "." + f.getName() + " declares two converters");
            }
            converter = composed.value();
        }
        SqlType<?> type = converter == null ? SqlType.of(f.getType()) : SqlType.converted(f.getType(), converter);
        String name = column == null || column.value().isEmpty() ? null : column.value();
        return new PropertyMeta(owner, f.getName(), name, type, f, f.getAnnotation(MapFrom.class));
    }

    private static String decapitalize(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1)) && Character.isUpperCase(s.charAt(0))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}

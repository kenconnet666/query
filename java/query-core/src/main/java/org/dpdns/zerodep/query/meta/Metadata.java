package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.Col;
import org.dpdns.zerodep.query.Entity;
import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ConvertWith;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.ReadAs;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.type.Converter;
import org.dpdns.zerodep.query.type.SqlType;

import java.lang.annotation.Annotation;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazily built, cached metadata. Scans and getter resolutions are keyed by class through
 * {@link ClassValue}, so they are computed once and never keep a class loader alive.
 *
 * <p>A table is an interface extending {@link Entity}; its columns are the abstract methods returning
 * {@code Col<T>}. Result and row classes (records, beans) are described by their fields.
 */
public final class Metadata {
    private static final ClassValue<EntityMeta<?>> ENTITIES = new ClassValue<>() {
        @Override
        protected EntityMeta<?> computeValue(Class<?> type) { return type.isInterface() ? scanTable(type) : scanClass(type); }
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

    /**
     * Resolves a getter or record accessor of a row class, e.g. {@code Row::total} on a CTE. The
     * getter may be declared on a base class or an interface; resolution is by property name.
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

    // ---------- table interfaces ----------

    private static <E> EntityMeta<E> scanTable(Class<E> type) {
        if (!Entity.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getSimpleName() + " is an interface but does not extend Entity");
        }
        Map<String, PropertyMeta> columns = new TreeMap<>();
        for (Method m : type.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Entity.class) continue;
            if (columns.containsKey(m.getName())) continue;
            List<Method> declarations = declarations(type, m.getName());
            if (declarations.isEmpty() || declarations.get(0).isDefault()) continue;   // overridden by a default method
            Method first = declarations.get(0);
            if (first.getReturnType() != Col.class || first.getParameterCount() != 0) {
                throw new IllegalArgumentException(type.getSimpleName() + "." + m.getName()
                    + "() is neither a column (Col<T> name()) nor a default method");
            }
            columns.put(m.getName(), describeColumn(type, m.getName(), declarations));
        }
        Table table = type.getAnnotation(Table.class);
        return new EntityMeta<>(type, table == null ? null : table.value(),
            table == null || table.schema().isEmpty() ? null : table.schema(), List.copyOf(columns.values()));
    }

    /** No-argument methods of that name, the most specific declaration first, so a sub-interface can rename a column. */
    private static List<Method> declarations(Class<?> type, String name) {
        List<Method> out = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty()) {
            Class<?> c = queue.removeFirst();
            if (!seen.add(c)) continue;
            try {
                out.add(c.getDeclaredMethod(name));
            } catch (NoSuchMethodException ignored) {
                // declared further up, if at all
            }
            queue.addAll(List.of(c.getInterfaces()));
        }
        return out;
    }

    private static PropertyMeta describeColumn(Class<?> owner, String name, List<Method> declarations) {
        Method annotated = declarations.stream().filter(Metadata::describesColumn).findFirst().orElse(declarations.get(0));
        Type returned = declarations.get(0).getGenericReturnType();
        if (!(returned instanceof ParameterizedType p) || !(p.getActualTypeArguments()[0] instanceof Class<?> javaType)) {
            throw new IllegalArgumentException(owner.getSimpleName() + "." + name + "() must declare its value type, e.g. Col<String>");
        }
        Column column = annotated.getAnnotation(Column.class);
        SqlType<?> type = sqlType(owner, name, annotated, javaType, column);
        String columnName = column == null || column.value().isEmpty() ? null : column.value();
        return new PropertyMeta(owner, name, columnName, type, null, readTemplate(owner, name, annotated));
    }

    private static boolean describesColumn(Method m) {
        if (m.isAnnotationPresent(Column.class) || m.isAnnotationPresent(ReadAs.class)) return true;
        for (Annotation a : m.getAnnotations()) {
            Class<? extends Annotation> t = a.annotationType();
            if (t.isAnnotationPresent(ConvertWith.class) || t.isAnnotationPresent(ReadAs.class)) return true;
        }
        return false;
    }

    // ---------- result and row classes ----------

    private static <E> EntityMeta<E> scanClass(Class<E> type) {
        if (type.isAnnotationPresent(Table.class)) {
            throw new IllegalArgumentException(type.getSimpleName() + " is a class; declare a table as an interface extending Entity");
        }
        List<PropertyMeta> properties = new ArrayList<>();
        for (Field f : fields(type)) {
            // A composed converter annotation on a field decodes the column when native SQL maps into the class.
            properties.add(new PropertyMeta(type, f.getName(), null, sqlType(type, f.getName(), f, f.getType(), null), f, null));
        }
        return new EntityMeta<>(type, null, null, properties);
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

    // ---------- shared ----------

    private static SqlType<?> sqlType(Class<?> owner, String name, AnnotatedElement element, Class<?> javaType, Column column) {
        Class<?> converter = column != null && column.converter() != Converter.class ? column.converter() : null;
        for (Annotation a : element.getAnnotations()) {
            ConvertWith composed = a.annotationType().getAnnotation(ConvertWith.class);
            if (composed == null) continue;
            if (converter != null && converter != composed.value()) {
                throw new IllegalArgumentException(owner.getSimpleName() + "." + name + " declares two converters");
            }
            converter = composed.value();
        }
        return converter == null ? SqlType.of(javaType) : SqlType.converted(javaType, converter);
    }

    /** The {@link ReadAs} template on the column itself or on one composed annotation. */
    private static String readTemplate(Class<?> owner, String name, AnnotatedElement element) {
        String found = null;
        List<ReadAs> declared = new ArrayList<>();
        if (element.getAnnotation(ReadAs.class) != null) declared.add(element.getAnnotation(ReadAs.class));
        for (Annotation a : element.getAnnotations()) {
            ReadAs composed = a.annotationType().getAnnotation(ReadAs.class);
            if (composed != null) declared.add(composed);
        }
        for (ReadAs r : declared) {
            if (found != null) throw new IllegalArgumentException(owner.getSimpleName() + "." + name + " declares two read templates");
            if (!r.value().contains("{0}")) {
                throw new IllegalArgumentException(owner.getSimpleName() + "." + name + ": a read template needs {0} for the column");
            }
            found = r.value();
        }
        return found;
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
            throw new IllegalArgumentException("Expected a getter reference such as Row::total, got " + getter.getClass(), e);
        }
        String method = lambda.getImplMethodName();
        if (method.startsWith("lambda$")) {
            throw new IllegalArgumentException("Use a method reference (Row::total), not a lambda, to name a column");
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

    private static String decapitalize(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1)) && Character.isUpperCase(s.charAt(0))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}

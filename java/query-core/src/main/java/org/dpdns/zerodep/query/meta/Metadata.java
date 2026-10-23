package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ConvertWith;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.ReadAs;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
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
 * 惰性构建并缓存的元数据。类的扫描结果和 getter 的解析结果都通过 {@link ClassValue} 按类缓存，
 * 只计算一次，也不会让类加载器无法回收。
 *
 * <p>表是继承 {@link Entity} 的接口，列是返回 {@code Col<T>} 的抽象方法。
 * 结果类和行类（record、bean）则按字段描述。
 */
public final class Metadata {
    /** 每个类型的元数据：接口按表扫描，其余按结果类或行类扫描。 */
    private static final ClassValue<EntityMeta<?>> ENTITIES = new ClassValue<>() {
        @Override
        protected EntityMeta<?> computeValue(Class<?> type) { return type.isInterface() ? scanTable(type) : scanClass(type); }
    };

    /** 以 lambda 的类为键；解析需要拿到实例，所以首次使用时才填充。 */
    private static final ClassValue<AtomicReference<GetterInfo>> GETTERS = new ClassValue<>() {
        @Override
        protected AtomicReference<GetterInfo> computeValue(Class<?> type) { return new AtomicReference<>(); }
    };

    /**
     * 一个 getter 引用解析出的信息。
     *
     * @param property   属性名
     * @param returnType getter 声明的返回类型；无法加载时为 null
     */
    private record GetterInfo(String property, Class<?> returnType) { }

    private Metadata() { }

    /** 取得类型的元数据：接口按表描述，其余类按结果类或行类描述。 */
    @SuppressWarnings("unchecked")
    public static <E> EntityMeta<E> entity(Class<E> type) { return (EntityMeta<E>) ENTITIES.get(type); }

    /**
     * 解析行类的 getter 或 record 访问器，例如 CTE 上的 {@code Row::total}。
     * getter 可以声明在父类或接口上，解析按属性名进行；返回类型与属性类型不兼容时报错。
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

    // ---------- 表接口 ----------

    /** 扫描表接口：把返回 {@code Col<T>} 的抽象方法收集为列，非列的抽象方法报错。 */
    private static <E> EntityMeta<E> scanTable(Class<E> type) {
        if (!Entity.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getSimpleName() + " is an interface but does not extend Entity");
        }
        Map<String, PropertyMeta> columns = new TreeMap<>();
        for (Method m : type.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Entity.class) continue;
            if (columns.containsKey(m.getName())) continue;
            List<Method> declarations = declarations(type, m.getName());
            if (declarations.isEmpty() || declarations.get(0).isDefault()) continue;   // 已被 default 方法覆盖
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

    /** 同名的无参方法声明，最具体的在前，这样子接口可以重新指定列的注解。 */
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
                // 可能声明在更上层的接口里，也可能根本没有
            }
            queue.addAll(List.of(c.getInterfaces()));
        }
        return out;
    }

    /** 描述一列：注解取自第一个带列注解的声明，值类型取自最具体的声明的泛型参数。 */
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

    /** 方法上是否带有描述列的注解：{@code @Column}、{@code @ReadAs}，或带 {@code @ConvertWith}/{@code @ReadAs} 元注解的组合注解。 */
    private static boolean describesColumn(Method m) {
        if (m.isAnnotationPresent(Column.class) || m.isAnnotationPresent(ReadAs.class)) return true;
        for (Annotation a : m.getAnnotations()) {
            Class<? extends Annotation> t = a.annotationType();
            if (t.isAnnotationPresent(ConvertWith.class) || t.isAnnotationPresent(ReadAs.class)) return true;
        }
        return false;
    }

    // ---------- 结果类与行类 ----------

    /** 扫描结果类或行类：每个字段是一个属性；声明了 {@code @Table} 的类报错，表必须是接口。 */
    private static <E> EntityMeta<E> scanClass(Class<E> type) {
        if (type.isAnnotationPresent(Table.class)) {
            throw new IllegalArgumentException(type.getSimpleName() + " is a class; declare a table as an interface extending Entity");
        }
        List<PropertyMeta> properties = new ArrayList<>();
        for (Field f : fields(type)) {
            // 字段上的组合转换器注解，用于原生 SQL 映射进该类时解码列。
            properties.add(new PropertyMeta(type, f.getName(), null, sqlType(type, f.getName(), f, f.getType(), null), f, null));
        }
        return new EntityMeta<>(type, null, null, properties);
    }

    /** 类的属性字段：record 按组件顺序，其余类父类在前，跳过静态、transient、合成和 {@code @Ignore} 字段。 */
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

    // ---------- 共用 ----------

    /** 决定属性的 SQL 类型：{@code @Column} 与组合注解声明的转换器至多一个，没有则按 Java 类型推断。 */
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

    /** 列自身或其组合注解上的 {@link ReadAs} 模板；至多一个，且必须含 {@code {0}} 占位符。 */
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

    /** 取得 getter 的解析结果，按 lambda 的类缓存。 */
    private static GetterInfo info(Getter<?, ?> getter) {
        AtomicReference<GetterInfo> holder = GETTERS.get(getter.getClass());
        GetterInfo info = holder.get();
        if (info == null) {
            info = resolve(getter);
            holder.set(info);
        }
        return info;
    }

    /** 通过序列化 lambda 的 {@code writeReplace} 还原被引用的方法，并由方法名推出属性名。 */
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

    /** 内部类名对应的类是否是 record；加载不到时视为否。 */
    private static boolean isRecordClass(String internalName, ClassLoader loader) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, loader).isRecord();
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 把 JVM 返回类型描述符转成 Class；无法加载或是数组等其他描述符时为 null。 */
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

    /** 与 JavaBeans 规则一致：前两个字母都大写时保持原样，否则首字母小写。 */
    private static String decapitalize(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1)) && Character.isUpperCase(s.charAt(0))) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}

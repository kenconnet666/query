package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ConvertWith;
import org.dpdns.zerodep.query.annotation.Generated;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.LogicDelete;
import org.dpdns.zerodep.query.annotation.ManyToOne;
import org.dpdns.zerodep.query.annotation.OneToMany;
import org.dpdns.zerodep.query.annotation.ReadAs;
import org.dpdns.zerodep.query.annotation.ReadOnly;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.Version;
import org.dpdns.zerodep.query.annotation.WriteOnce;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.table.column.Col;
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
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
 * <p>表是继承 {@link Entity} 的接口，列是返回列接口（{@code ColText<T>}、{@code ColDecimal<T>} 等，统称 {@link Col}）的抽象方法；
 * 列接口按库里的存储类型选，见 {@link #describeColumn}。
 * 结果类和行类（record、bean）则按字段描述。
 */
public final class Metadata {
    /** 每个类型的元数据：接口按表扫描，其余按结果类或行类扫描。 */
    private static final ClassValue<EntityMeta<?>> ENTITIES = new ClassValue<>() {
        /** 表是接口，结果类和行类是普通类。 */
        @Override
        protected EntityMeta<?> computeValue(Class<?> type) { return type.isInterface() ? scanTable(type) : scanClass(type); }
    };

    /** 以 lambda 的类为键；解析需要拿到实例，所以首次使用时才填充。 */
    private static final ClassValue<AtomicReference<GetterInfo>> GETTERS = new ClassValue<>() {
        /** 每个 getter 类一个空槽，第一次解析后填入。 */
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

    /** 工具类，不能实例化。 */
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

    /** 扫描表接口：把返回列接口的抽象方法收集为列，非列的抽象方法报错。 */
    private static <E> EntityMeta<E> scanTable(Class<E> type) {
        if (!Entity.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getSimpleName() + " is an interface but does not extend Entity");
        }
        Map<String, PropertyMeta> columns = new TreeMap<>();
        Map<String, RelationMeta> relations = new TreeMap<>();
        Map<Class<? extends Annotation>, List<String>> marked = new HashMap<>();
        for (Method m : type.getMethods()) {
            if (m.isDefault() || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Entity.class) continue;
            if (columns.containsKey(m.getName()) || relations.containsKey(m.getName())) continue;
            List<Method> declarations = declarations(type, m.getName());
            if (declarations.isEmpty() || declarations.get(0).isDefault()) continue;   // 已被 default 方法覆盖
            Method first = declarations.get(0);
            RelationMeta relation = relation(type, first);
            if (relation != null) {
                relations.put(m.getName(), relation);
                continue;
            }
            if (!first.getReturnType().isInterface() || !Col.class.isAssignableFrom(first.getReturnType()) || first.getParameterCount() != 0) {
                throw new IllegalArgumentException(type.getSimpleName() + "." + m.getName()
                    + "() is neither a column (e.g. ColText<String> name()) nor a default method");
            }
            columns.put(m.getName(), describeColumn(type, m.getName(), declarations));
            for (Class<? extends Annotation> a : List.of(Id.class, Version.class, Generated.class, LogicDelete.class, ReadOnly.class, WriteOnce.class)) {
                if (declarations.stream().anyMatch(d -> d.isAnnotationPresent(a))) marked.computeIfAbsent(a, k -> new ArrayList<>()).add(m.getName());
            }
        }
        Table table = type.getAnnotation(Table.class);
        return new EntityMeta<>(type, table == null ? null : table.value(),
            table == null || table.schema().isEmpty() ? null : table.schema(), List.copyOf(columns.values()), relations,
            writes(type, columns, marked));
    }

    /** 由列上的写入注解得到 {@link WriteMeta}；版本列、生成列、逻辑删除列各至多一列，版本列必须是整数。 */
    private static WriteMeta writes(Class<?> type, Map<String, PropertyMeta> columns, Map<Class<? extends Annotation>, List<String>> marked) {
        if (marked.isEmpty()) return WriteMeta.NONE;
        List<String> ids = marked.getOrDefault(Id.class, List.of()).stream().sorted().toList();
        String version = single(type, marked, Version.class);
        if (version != null) {
            Class<?> v = columns.get(version).javaType();
            if (v != Integer.class && v != Long.class) {
                throw new IllegalArgumentException(type.getSimpleName() + "." + version + "(): @Version needs ColInteger<Integer> or ColBigint<Long>");
            }
        }
        String logic = single(type, marked, LogicDelete.class);
        Set<String> readOnly = Set.copyOf(marked.getOrDefault(ReadOnly.class, List.of()));
        Set<String> writeOnce = Set.copyOf(marked.getOrDefault(WriteOnce.class, List.of()));
        for (String name : readOnly) {
            if (writeOnce.contains(name) || ids.contains(name) || name.equals(version)) {
                throw new IllegalArgumentException(type.getSimpleName() + "." + name + "(): @ReadOnly cannot be combined with @WriteOnce, @Id or @Version");
            }
        }
        return new WriteMeta(ids, version, single(type, marked, Generated.class), logic == null ? null : logicDelete(type, columns.get(logic)),
            readOnly, writeOnce);
    }

    /** 带注解 a 的唯一一列；没有时为 null，多于一列时报错。 */
    private static String single(Class<?> type, Map<Class<? extends Annotation>, List<String>> marked, Class<? extends Annotation> a) {
        List<String> names = marked.getOrDefault(a, List.of());
        if (names.size() > 1) throw new IllegalArgumentException(type.getSimpleName() + " has more than one @" + a.getSimpleName() + " column: " + names);
        return names.isEmpty() ? null : names.get(0);
    }

    /** 逻辑删除列：时间戳列记录删除时间，其余按列的类型解析未删除和已删除的值。 */
    private static WriteMeta.LogicDeleteMeta logicDelete(Class<?> type, PropertyMeta column) {
        Class<?> javaType = column.javaType();
        if (javaType == LocalDateTime.class) return new WriteMeta.LogicDeleteMeta(column.name(), null, null, true, false);
        LogicDelete a = null;
        for (Method d : declarations(type, column.name())) if ((a = d.getAnnotation(LogicDelete.class)) != null) break;
        String where = type.getSimpleName() + "." + column.name() + "()";
        if (a.epochMillis()) {
            if (javaType != Long.class) throw new IllegalArgumentException(where + ": @LogicDelete(epochMillis = true) needs ColBigint<Long>");
            return new WriteMeta.LogicDeleteMeta(column.name(), parse(where, javaType, a.active()), null, false, true);
        }
        return new WriteMeta.LogicDeleteMeta(column.name(), parse(where, javaType, a.active()), parse(where, javaType, a.deleted()), false, false);
    }

    /** 把 {@code @LogicDelete} 里写的值按列的 Java 类型解析。 */
    private static Object parse(String where, Class<?> javaType, String text) {
        if (javaType == String.class) return text;
        if (javaType == Integer.class) return Integer.valueOf(text);
        if (javaType == Long.class) return Long.valueOf(text);
        if (javaType == Short.class) return Short.valueOf(text);
        if (javaType == Boolean.class) {
            if (text.equals("1") || text.equalsIgnoreCase("true")) return true;
            if (text.equals("0") || text.equalsIgnoreCase("false")) return false;
        }
        throw new IllegalArgumentException(where + ": @LogicDelete cannot use '" + text + "' for a " + javaType.getSimpleName() + " column");
    }

    /** 用 {@code @ManyToOne} 或 {@code @OneToMany} 声明的关系方法；不是关系时返回 null。 */
    private static RelationMeta relation(Class<?> owner, Method m) {
        ManyToOne one = m.getAnnotation(ManyToOne.class);
        OneToMany many = m.getAnnotation(OneToMany.class);
        if (one == null && many == null) return null;
        String where = owner.getSimpleName() + "." + m.getName() + "()";
        if (one != null && many != null) throw new IllegalArgumentException(where + " is declared both @ManyToOne and @OneToMany");
        if (m.getParameterCount() != 0) throw new IllegalArgumentException(where + ": a relation method takes no arguments");
        if (one != null) {
            if (!m.getReturnType().isInterface() || !Entity.class.isAssignableFrom(m.getReturnType())) {
                throw new IllegalArgumentException(where + ": @ManyToOne must return a table interface");
            }
            return new RelationMeta(m.getName(), false, m.getReturnType(), List.of(one.self()), List.of(one.target()));
        }
        if (m.getReturnType() != Many.class || !(m.getGenericReturnType() instanceof ParameterizedType p)
            || !(p.getActualTypeArguments()[0] instanceof Class<?> target)) {
            throw new IllegalArgumentException(where + ": @OneToMany must return Many<Table>");
        }
        return new RelationMeta(m.getName(), true, target, List.of(many.self()), List.of(many.target()));
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

    /**
     * 列的值类型：{@code ColText<String>} 的 String；本身带泛型的（转换器存成 JSON 文字的 {@code ColClob<Map<String, Object>>}、
     * {@code ColClob<List<Line>>}）取它的原始类，同转换器的类型参数（{@code Converters}）。是类型变量、通配符时为 null。
     */
    private static Class<?> valueClass(Type argument) {
        if (argument instanceof Class<?> c) return c;
        if (argument instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) return raw;
        return null;
    }

    /**
     * 描述一列：注解取自第一个带列注解的声明，值类型取自最具体的声明的泛型参数。列接口要与库里的存储类型一致：
     * 文本列是 {@code ColText}（不限长度的是 {@code ColClob}），以文本保存的日期（转换器存成 String）也是 {@code ColText<LocalDate>}；
     * 声明成别的接口时报错，免得日期函数作用在文本上生成错误的 SQL。
     */
    private static PropertyMeta describeColumn(Class<?> owner, String name, List<Method> declarations) {
        Method annotated = declarations.stream().filter(Metadata::describesColumn).findFirst().orElse(declarations.get(0));
        Type returned = declarations.get(0).getGenericReturnType();
        Class<?> javaType = returned instanceof ParameterizedType p ? valueClass(p.getActualTypeArguments()[0]) : null;
        if (javaType == null) {
            throw new IllegalArgumentException(owner.getSimpleName() + "." + name + "() must declare its value type, e.g. ColText<String>");
        }
        Column column = annotated.getAnnotation(Column.class);
        SqlType<?> type = sqlType(owner, name, annotated, javaType, column);
        Class<?> expected = Col.interfaceFor(type.storageType());
        if (!Col.declarable(declarations.get(0).getReturnType(), type.storageType())) {
            throw new IllegalArgumentException(owner.getSimpleName() + "." + name + "() is stored as " + type.storageType().getSimpleName()
                + "; declare it as " + expected.getSimpleName() + "<" + javaType.getSimpleName() + ">");
        }
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
        return new EntityMeta<>(type, null, null, properties, Map.of(), WriteMeta.NONE);
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

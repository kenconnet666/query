package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.type.SqlType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按列标签把一行映射成 record、Lombok {@code @Data} 的 bean（含链式 setter）、{@code @Builder} 类、
 * 带字段的普通类，或者一个标量。每个（目标类型，标签列表）只构建一次并缓存；
 * 构造和 setter 调用都走 {@link MethodHandle}。
 *
 * <p>标签与属性名匹配时忽略大小写和下划线，所以 {@code PART_NUMBER} 会填入 {@code partNumber}。
 * 目标类型没有的列会被忽略；目标属性找不到对应的列则报错，笔误不会悄悄变成 null。
 *
 * @param <R> 映射的目标类型
 */
public final class MappingPlan<R> {
    /** 缓存键：目标类型加结果集的列标签。 */
    private record Key(Class<?> target, List<String> labels) { }

    private static final Map<Key, MappingPlan<?>> CACHE = new ConcurrentHashMap<>();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** 把一行已解码的值构造成目标对象。 */
    private interface Strategy { Object map(Object[] values) throws Throwable; }

    private final Class<R> target;
    private final Strategy strategy;
    /** 按列下标排列：目标读取该列所用的类型，目标不用的列为 null。 */
    private final SqlType<?>[] columnTypes;

    private MappingPlan(Class<R> target, Strategy strategy, SqlType<?>[] columnTypes) {
        this.target = target;
        this.strategy = strategy;
        this.columnTypes = columnTypes;
    }

    /** 取得（必要时构建）映射到 {@code target} 的方案；{@code labels} 是结果集的列标签，按列顺序。 */
    @SuppressWarnings("unchecked")
    public static <R> MappingPlan<R> of(Class<R> target, List<String> labels) {
        return (MappingPlan<R>) CACHE.computeIfAbsent(new Key(target, List.copyOf(labels)), k -> build(target, k.labels()));
    }

    /** 把一行已解码的值映射成目标对象；非运行时异常包装为 {@link IllegalStateException}。 */
    public R map(Object[] values) {
        try {
            return target.cast(strategy.map(values));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot create " + target.getSimpleName() + " from a row", e);
        }
    }

    /** 目标期望如何读取该列（含转换器）；目标不使用这一列时为 null。 */
    public SqlType<?> columnType(int index) { return columnTypes[index]; }

    /** 按目标类型选择映射方式：标量、record、静态 {@code builder()}，其余按 bean 处理。 */
    private static <R> MappingPlan<R> build(Class<R> target, List<String> labels) {
        SqlType<?>[] types = new SqlType<?>[labels.size()];
        if (labels.size() == 1 && isScalar(target)) {
            types[0] = SqlType.of(target);
            return new MappingPlan<>(target, values -> convert(values[0], target, labels.get(0)), types);
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) index.putIfAbsent(normalize(labels.get(i)), i);
        try {
            if (target.isRecord()) return record(target, labels, index, types);
            Method builder = staticBuilder(target);
            if (builder != null) return builder(target, builder, labels, index, types);
            return bean(target, labels, index, types);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot map rows into " + target.getName(), e);
        }
    }

    /** 通过规范构造器构造 record，参数按组件顺序从对应列取值。 */
    private static <R> MappingPlan<R> record(Class<R> target, List<String> labels, Map<String, Integer> index, SqlType<?>[] types)
            throws ReflectiveOperationException {
        RecordComponent[] components = target.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        int[] columns = new int[components.length];
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
            columns[i] = column(target, labels, index, components[i].getName());
            types[columns[i]] = Metadata.entity(target).property(components[i].getName()).type();
        }
        Constructor<R> ctor = target.getDeclaredConstructor(parameterTypes);
        ctor.setAccessible(true);
        MethodHandle handle = LOOKUP.unreflectConstructor(ctor).asSpreader(Object[].class, components.length);
        String[] names = new String[components.length];
        for (int i = 0; i < components.length; i++) names[i] = components[i].getName();
        return new MappingPlan<>(target, values -> {
            Object[] args = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) args[i] = convert(values[columns[i]], parameterTypes[i], names[i]);
            return handle.invoke(args);
        }, types);
    }

    /** 先调用无参构造器，再逐个属性调用 setter；没有 setter 的属性直接写字段。 */
    private static <R> MappingPlan<R> bean(Class<R> target, List<String> labels, Map<String, Integer> index, SqlType<?>[] types)
            throws ReflectiveOperationException {
        Constructor<R> ctor;
        try {
            ctor = target.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(target.getSimpleName() + " needs a no-argument constructor, a static builder(), or to be a record");
        }
        ctor.setAccessible(true);
        MethodHandle create = LOOKUP.unreflectConstructor(ctor);
        List<MethodHandle> setters = new ArrayList<>();
        List<Integer> columns = new ArrayList<>();
        List<Class<?>> valueTypes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        EntityMeta<R> meta = Metadata.entity(target);
        for (PropertyMeta p : meta.properties()) {
            int column = column(target, labels, index, p.name());
            Method setter = setter(target, p.name());
            MethodHandle h;
            Class<?> valueType;
            if (setter != null) {
                setter.setAccessible(true);
                valueType = setter.getParameterTypes()[0];
                h = LOOKUP.unreflect(setter).asType(MethodType.methodType(void.class, Object.class, Object.class));
            } else {
                Field f = p.field();
                f.setAccessible(true);
                valueType = f.getType();
                h = LOOKUP.unreflectSetter(f).asType(MethodType.methodType(void.class, Object.class, Object.class));
            }
            setters.add(h);
            columns.add(column);
            valueTypes.add(valueType);
            names.add(p.name());
            types[column] = p.type();
        }
        return new MappingPlan<>(target, values -> {
            Object bean = create.invoke();
            for (int i = 0; i < setters.size(); i++) {
                setters.get(i).invoke(bean, convert(values[columns.get(i)], valueTypes.get(i), names.get(i)));
            }
            return bean;
        }, types);
    }

    /** 调用静态 {@code builder()}，逐个属性调用同名的链式方法，最后 {@code build()}。 */
    private static <R> MappingPlan<R> builder(Class<R> target, Method factory, List<String> labels, Map<String, Integer> index, SqlType<?>[] types)
            throws ReflectiveOperationException {
        factory.setAccessible(true);
        MethodHandle create = LOOKUP.unreflect(factory);
        Class<?> builderType = factory.getReturnType();
        List<MethodHandle> steps = new ArrayList<>();
        List<Integer> columns = new ArrayList<>();
        List<Class<?>> valueTypes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (PropertyMeta p : Metadata.entity(target).properties()) {
            Method step = null;
            for (Method m : builderType.getMethods()) {
                if (m.getName().equals(p.name()) && m.getParameterCount() == 1) step = m;
            }
            if (step == null) throw new IllegalArgumentException(target.getSimpleName() + "'s builder has no method " + p.name());
            step.setAccessible(true);
            int column = column(target, labels, index, p.name());
            steps.add(LOOKUP.unreflect(step).asType(MethodType.methodType(Object.class, Object.class, Object.class)));
            columns.add(column);
            valueTypes.add(step.getParameterTypes()[0]);
            names.add(p.name());
            types[column] = p.type();
        }
        Method build = builderType.getMethod("build");
        build.setAccessible(true);
        MethodHandle finish = LOOKUP.unreflect(build).asType(MethodType.methodType(Object.class, Object.class));
        return new MappingPlan<>(target, values -> {
            Object b = create.invoke();
            for (int i = 0; i < steps.size(); i++) b = steps.get(i).invoke(b, convert(values[columns.get(i)], valueTypes.get(i), names.get(i)));
            return finish.invoke(b);
        }, types);
    }

    /** 找到属性对应的列下标；找不到则报错并列出查询产出的列。 */
    private static int column(Class<?> target, List<String> labels, Map<String, Integer> index, String property) {
        Integer i = index.get(normalize(property));
        if (i == null) {
            throw new IllegalArgumentException(target.getSimpleName() + "." + property + " has no column; the query produces " + labels
                + ". Add the column, label it with .as(...), or mark the field @Ignore.");
        }
        return i;
    }

    /** 类上的公共静态 {@code builder()} 方法，没有则为 null。 */
    private static Method staticBuilder(Class<?> type) {
        try {
            Method m = type.getMethod("builder");
            return Modifier.isStatic(m.getModifiers()) ? m : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** 沿继承链查找 {@code setXxx} 方法（含链式返回），没有则为 null。 */
    private static Method setter(Class<?> type, String property) {
        String name = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 && !Modifier.isStatic(m.getModifiers())) return m;
            }
        }
        return null;
    }

    /** 去掉下划线并转小写，用于忽略大小写和下划线地匹配标签与属性名。 */
    private static String normalize(String name) { return name.replace("_", "").toLowerCase(Locale.ROOT); }

    /** 能直接作为单列结果的类型：字符串、数字、布尔、枚举、java.time 与字节数组。 */
    public static boolean isScalar(Class<?> type) {
        Class<?> t = SqlType.box(type);
        return t == String.class || Number.class.isAssignableFrom(t) || t == Boolean.class || t == Character.class
            || t.isEnum() || t.getName().startsWith("java.time.") || t == byte[].class;
    }

    /**
     * 宽松的值转换，使同一条查询既能填充 Double 的 VO，也能填充 BigDecimal 的 record。
     * 数据库值为 null 而目标是基本类型时报错。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object convert(Object value, Class<?> target, String property) {
        if (value == null) {
            if (target.isPrimitive()) {
                throw new IllegalStateException(property + " is NULL but its Java type is primitive " + target + "; use the wrapper type");
            }
            return null;
        }
        Class<?> t = SqlType.box(target);
        if (t.isInstance(value)) return value;
        if (value instanceof Number n) {
            if (t == Integer.class) return n.intValue();
            if (t == Long.class) return n.longValue();
            if (t == Double.class) return n.doubleValue();
            if (t == Float.class) return n.floatValue();
            if (t == Short.class) return n.shortValue();
            if (t == Byte.class) return n.byteValue();
            if (t == BigDecimal.class) return n instanceof BigInteger bi ? new BigDecimal(bi) : new BigDecimal(n.toString());
            if (t == BigInteger.class) return new BigDecimal(n.toString()).toBigIntegerExact();
            if (t == Boolean.class) return n.intValue() != 0;
        }
        if (t == String.class) return value.toString();
        if (t.isEnum() && value instanceof String s) return Enum.valueOf((Class) t, s);
        if (value instanceof java.sql.Date d && t == LocalDate.class) return d.toLocalDate();
        if (value instanceof java.sql.Timestamp ts) {
            if (t == LocalDateTime.class) return ts.toLocalDateTime();
            if (t == Instant.class) return ts.toInstant();
            if (t == LocalDate.class) return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDateTime ldt) {
            if (t == LocalDate.class) return ldt.toLocalDate();
            if (t == Instant.class) return ldt.toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException(property + ": cannot convert " + value.getClass().getSimpleName() + " to " + target.getSimpleName());
    }
}

package org.dpdns.zerodep.wire;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 编码对象时取哪些属性，按类缓存：record 取组件；其余的类取公开的 getter（{@code getX}，布尔的 {@code isX}）和公开字段，
 * 与 Jackson 默认看到的一致。getter 的属性名按同名字段的写法（忽略大小写对上 Lombok 生成的 {@code getURL} 之类），
 * 没有字段时把首字母改成小写。顺序是声明字段的顺序，没有字段的 getter 排在后面。
 */
final class Properties {
    /** 一个属性：名字和读取它的方式。 */
    record Property(String name, Reader reader) {
        /** 读出 target 上的这个属性；getter 抛出的异常带上类名和属性名重新抛出。 */
        Object read(Object target) {
            try {
                return reader.read(target);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("Wire.encode: " + target.getClass().getSimpleName() + "." + name + " threw", e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Wire.encode: cannot read " + target.getClass().getSimpleName() + "." + name, e);
            }
        }
    }

    /** 从对象上读一个属性。 */
    @FunctionalInterface
    interface Reader {
        Object read(Object target) throws IllegalAccessException, InvocationTargetException;
    }

    private static final ClassValue<List<Property>> CACHE = new ClassValue<>() {
        @Override protected List<Property> computeValue(Class<?> type) { return List.copyOf(type.isRecord() ? components(type) : bean(type)); }
    };

    private Properties() { }

    /** type 的属性，按顺序。 */
    static List<Property> of(Class<?> type) { return CACHE.get(type); }

    /** record 的组件。 */
    private static List<Property> components(Class<?> type) {
        List<Property> out = new ArrayList<>();
        for (RecordComponent c : type.getRecordComponents()) {
            Method accessor = c.getAccessor();
            accessor.trySetAccessible();
            out.add(new Property(c.getName(), accessor::invoke));
        }
        return out;
    }

    /** 普通类的公开 getter 与公开字段。 */
    private static List<Property> bean(Class<?> type) {
        Map<String, String> declared = new LinkedHashMap<>();   // 小写的名字 → 字段的写法，按声明顺序
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) declared.putIfAbsent(f.getName().toLowerCase(Locale.ROOT), f.getName());
            }
        }
        Map<String, Property> found = new LinkedHashMap<>();
        for (Method m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() > 0 || m.isBridge() || m.getDeclaringClass() == Object.class) continue;
            String base = base(m);
            if (base == null) continue;
            String name = declared.getOrDefault(base.toLowerCase(Locale.ROOT), Character.toLowerCase(base.charAt(0)) + base.substring(1));
            m.trySetAccessible();
            found.putIfAbsent(name, new Property(name, m::invoke));
        }
        for (Field f : type.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) found.putIfAbsent(f.getName(), new Property(f.getName(), f::get));
        }
        List<Property> out = new ArrayList<>();
        for (String name : declared.values()) if (found.containsKey(name)) out.add(found.remove(name));
        out.addAll(found.values());
        return out;
    }

    /** getter 去掉 get / is 前缀后的部分；不是 getter 时为 null。 */
    private static String base(Method m) {
        String n = m.getName();
        if (m.getReturnType() == void.class) return null;
        if (n.startsWith("get") && n.length() > 3) return n.substring(3);
        boolean bool = m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class;
        if (bool && n.startsWith("is") && n.length() > 2) return n.substring(2);
        return null;
    }
}

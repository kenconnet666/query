package org.dpdns.zerodep.query.write;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

/** 把数据库生成的值（自增主键）放进一行：record 重建一份，普通类直接设属性。 */
final class GeneratedValues {
    /** 工具类，不能实例化。 */
    private GeneratedValues() { }

    /** 行里名为 property 的属性换成 value；行类没有这个属性时报错 */
    static Object fill(Object row, String property, Object value) {
        Class<?> type = row.getClass();
        return type.isRecord() ? rebuild(row, type, property, value) : assign(row, type, property, value);
    }

    /** record：其余组件照抄，这一个换成 value，用规范构造器建一份新的 */
    private static Object rebuild(Object row, Class<?> type, String property, Object value) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        boolean found = false;
        try {
            for (int i = 0; i < components.length; i++) {
                if (components[i].getName().equals(property)) {
                    args[i] = value;
                    found = true;
                } else {
                    components[i].getAccessor().trySetAccessible();
                    args[i] = components[i].getAccessor().invoke(row);
                }
            }
            if (!found) throw missing(type, property);
            Constructor<?> canonical = type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            canonical.trySetAccessible();
            return canonical.newInstance(args);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot rebuild " + type.getSimpleName() + " with the generated " + property, e);
        }
    }

    /** 普通类：直接设字段（沿父类找） */
    private static Object assign(Object row, Class<?> type, String property, Object value) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(property);
                field.trySetAccessible();
                field.set(row, value);
                return row;
            } catch (NoSuchFieldException ignored) {
                // 在父类里接着找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot set " + type.getSimpleName() + "." + property, e);
            }
        }
        throw missing(type, property);
    }

    private static IllegalArgumentException missing(Class<?> type, String property) {
        return new IllegalArgumentException(type.getSimpleName() + " has no property '" + property + "' to receive the generated value; "
            + "use db.insertKeys(...) to get the values alone");
    }
}

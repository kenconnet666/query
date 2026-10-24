package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** 行类（record 或普通类）的属性与表的列的对应，插入和按主键更新、删除共用。 */
final class RowColumns {
    /** 工具类，不能实例化。 */
    private RowColumns() { }

    /** 行类的一个属性和它对应的表的列。 */
    record Column(PropertyMeta column, Field field) {
        /** 属性名，也是列方法名。 */
        String name() { return column.name(); }

        /** 读出 row 里这个属性的值。 */
        Object value(Object row) {
            try {
                return field.get(row);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + row.getClass().getSimpleName() + "." + field.getName(), e);
            }
        }
    }

    /** 行类的每个属性对应的表的列，按行类的属性顺序；属性在表里找不到同名列、或类型对不上时报错。 */
    static List<Column> of(EntityMeta<?> table, Class<?> rowType) {
        List<Column> out = new ArrayList<>();
        for (PropertyMeta p : Metadata.entity(rowType).properties()) {
            PropertyMeta column = column(table, p.name(), rowType.getSimpleName() + "." + p.name());
            Class<?> fieldType = box(p.field().getType());
            if (fieldType != column.javaType() && !column.javaType().isAssignableFrom(fieldType)) {
                throw new IllegalArgumentException(rowType.getSimpleName() + "." + p.name() + " is " + p.field().getType().getSimpleName()
                    + " but " + table.type().getSimpleName() + "." + p.name() + "() holds " + column.javaType().getSimpleName());
            }
            p.field().trySetAccessible();
            out.add(new Column(column, p.field()));
        }
        return List.copyOf(out);
    }

    /** 表里名为 name 的列；没有时报错，what 说明 name 来自哪里。 */
    static PropertyMeta column(EntityMeta<?> table, String name, String what) {
        if (!table.has(name)) {
            throw new IllegalArgumentException(what + " '" + name + "' has no column in " + table.type().getSimpleName() + "; known: "
                + table.properties().stream().map(PropertyMeta::name).toList());
        }
        return table.property(name);
    }

    /** 所有行必须是同一个类；返回这个类。 */
    static Class<?> sameClass(List<?> rows, String statement) {
        Class<?> type = null;
        for (Object row : rows) {
            if (row == null) throw new IllegalArgumentException(statement + " got a null row");
            if (type != null && type != row.getClass()) {
                throw new IllegalArgumentException(statement + " rows must all be of one class; got " + type.getSimpleName() + " and "
                    + row.getClass().getSimpleName());
            }
            type = row.getClass();
        }
        return type;
    }

    /** 基本类型换成包装类型，以便与列的类型比较。 */
    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        return Character.class;
    }
}

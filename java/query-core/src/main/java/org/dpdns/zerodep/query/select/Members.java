package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 按名字找作用域里的列和关系，selectAs 的对象树和 where(条件对象) 共用。表的一次出现按它的接口实例找同名的无参方法
 * （列、计算列或关系）；CTE 等行类来源按同名属性找列。
 */
final class Members {
    /** 工具类，不能实例化。 */
    private Members() { }

    /**
     * 作用域里恰好一张表上名为 name 的列或关系。没有时为 null；多于一张表有时报错，owner 用于报错信息。
     */
    static Object find(SelectSpec spec, String name, String owner) {
        List<Object> hits = new ArrayList<>();
        for (SelectSpec.Join j : spec.joins) {
            Object hit = member(j.ref(), name);
            if (hit != null) hits.add(hit);
        }
        if (hits.size() > 1) throw new IllegalArgumentException(owner + " matches " + name + " in " + hits.size() + " tables; write the table's relation path");
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 一次出现 ref 上名为 name 的列或关系：表接口调用同名方法，行类来源取同名属性的列；都没有时为 null。 */
    private static Object member(Ref<?> ref, String name) {
        if (ref.entity().isInterface()) return invoke(ref.param(), name);
        EntityMeta<?> meta = Metadata.entity(ref.entity());
        return meta.has(name) ? new Col<>(ref, meta.property(name)) : null;
    }

    /** 调用表接口实例上名为 name 的无参方法（只看它实现的接口，不含 hashCode 之类）；没有这个方法时为 null。 */
    static Object invoke(Object row, String name) {
        Method method = null;
        for (Class<?> type : row.getClass().getInterfaces()) {
            try {
                method = type.getMethod(name);
                break;
            } catch (NoSuchMethodException e) {
                // 继续找下一个接口
            }
        }
        if (method == null) return null;
        method.trySetAccessible();
        try {
            return method.invoke(row);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot call " + name + "() on " + row.getClass().getInterfaces()[0].getSimpleName(), e);
        }
    }
}

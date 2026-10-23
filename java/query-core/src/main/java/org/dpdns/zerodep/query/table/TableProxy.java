package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Cols;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 为表的一次出现实现表接口：列方法返回这次出现的列，default 方法照原样运行。
 * 作用域可能在多个线程里被延伸使用，所以内部状态用并发集合。
 */
public final class TableProxy implements InvocationHandler {
    /** default 方法的调用句柄缓存，各代理共用。 */
    private static final Map<Method, MethodHandle> DEFAULTS = new ConcurrentHashMap<>();

    /** 这个代理所属的表出现。 */
    private final Ref<?> ref;

    /** 表接口的元数据。 */
    private final EntityMeta<?> meta;

    /** 已创建的列，按方法名缓存，保证同一方法总是返回同一个对象。 */
    private final Map<String, Col<?>> columns = new ConcurrentHashMap<>();

    /** 只能通过 {@link #create} 创建。 */
    private TableProxy(Ref<?> ref) {
        this.ref = ref;
        this.meta = Metadata.entity(ref.entity());
    }

    /** 创建 ref 的表接口代理。 */
    static Object create(Ref<?> ref) {
        Class<?> type = ref.entity();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new TableProxy(ref));
    }

    /** 分派接口方法：Object 的方法按代理自身处理，{@code all()} 返回全部列，default 方法照原样运行，其余的列方法返回对应的列。 */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> ref.toString();
            };
        }
        if (method.getDeclaringClass() == Entity.class) return all();
        if (method.isDefault()) return special(method).bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args);
        return column(method.getName());
    }

    /** 按属性名取这次出现的列。 */
    Col<?> column(String name) { return columns.computeIfAbsent(name, n -> new Col<>(ref, meta.property(n))); }

    /** 这张表的全部列，按属性顺序。 */
    private Cols all() {
        List<Expr<?>> all = new ArrayList<>();
        for (PropertyMeta p : meta.properties()) all.add(column(p.name()));
        return new Cols(all);
    }

    /** 在代理自身上调用 default 方法；privateLookupIn 也能访问非 public 的接口。 */
    private static MethodHandle special(Method method) {
        return DEFAULTS.computeIfAbsent(method, m -> {
            Class<?> owner = m.getDeclaringClass();
            try {
                return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflectSpecial(m, owner);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot call " + owner.getSimpleName() + "." + m.getName()
                    + "(); in a named module, open its package to org.dpdns.zerodep.query", e);
            }
        });
    }
}

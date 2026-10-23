package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;

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
 * Implements a table interface for one occurrence: column methods return that occurrence's columns,
 * default methods run as written. Scopes may be extended from several threads, so state is concurrent.
 */
final class TableProxy implements InvocationHandler {
    private static final Map<Method, MethodHandle> DEFAULTS = new ConcurrentHashMap<>();

    private final Ref<?> ref;
    private final EntityMeta<?> meta;
    private final Map<String, Col<?>> columns = new ConcurrentHashMap<>();

    private TableProxy(Ref<?> ref) {
        this.ref = ref;
        this.meta = Metadata.entity(ref.entity());
    }

    static Object create(Ref<?> ref) {
        Class<?> type = ref.entity();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new TableProxy(ref));
    }

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

    Col<?> column(String name) { return columns.computeIfAbsent(name, n -> new Col<>(ref, meta.property(n))); }

    private Cols all() {
        List<Expr<?>> all = new ArrayList<>();
        for (PropertyMeta p : meta.properties()) all.add(column(p.name()));
        return new Cols(all);
    }

    /** A default method called on the proxy itself; privateLookupIn also reaches non-public interfaces. */
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

package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.meta.RelationMeta;
import org.dpdns.zerodep.query.select.Cols;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 为表的一次出现实现表接口：列方法返回这次出现的列，default 方法照原样运行并记住结果，
 * {@code one(...)}、{@code many(...)} 与注解声明的关系从这次出现出发。
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

    /** default 方法与关系方法的结果，按方法和实参记住，所以同一个关系只连接一次。 */
    private final Map<List<Object>, Object> remembered = new ConcurrentHashMap<>();

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

    /**
     * 分派接口方法：Object 的方法按代理自身处理；{@code columns}、{@code one}、{@code many} 由本类实现；
     * default 方法照原样运行；注解声明的关系按注解连接；其余的列方法返回对应的列。
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> ref.toString();
            };
        }
        if (method.getDeclaringClass() == Entity.class) {
            return switch (method.getName()) {
                case "one" -> one((Class<Entity>) args[0], (Function<Entity, Cond>) args[1]);
                case "many" -> new Many<>(ref, (Class<Entity>) args[0], (Function<Entity, Cond>) args[1], null);
                default -> columns();
            };
        }
        Object[] arguments = args == null ? new Object[0] : args;
        if (method.isDefault()) return remember(method, arguments, () -> special(method).bindTo(proxy).invokeWithArguments(arguments));
        RelationMeta relation = meta.relation(method.getName());
        if (relation != null) return remember(method, arguments, () -> annotated(relation));
        return column(method.getName());
    }

    /** 按属性名取这次出现的列。 */
    Col<?> column(String name) { return columns.computeIfAbsent(name, n -> new Col<>(ref, meta.property(n))); }

    /** 这张表的全部列，按属性顺序。 */
    private Cols columns() {
        List<Expr<?>> all = new ArrayList<>();
        for (PropertyMeta p : meta.properties()) all.add(column(p.name()));
        return new Cols(all);
    }

    /** 多对一：LEFT JOIN 目标表的一次新出现，登记为本次出现的隐式连接。 */
    @SuppressWarnings("unchecked")
    private <R extends Entity> R one(Class<R> target, Function<R, Cond> on) {
        Ref<R> joined = Ref.table(target);
        R row = joined.param();
        ref.relate(joined, on.apply(row));
        return row;
    }

    /** 注解声明的关系：两边的列按名配对做等值连接。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object annotated(RelationMeta relation) {
        Function<Entity, Cond> on = target -> {
            TableProxy other = (TableProxy) Proxy.getInvocationHandler(target);
            List<Cond> pairs = new ArrayList<>();
            for (int i = 0; i < relation.self().size(); i++) {
                pairs.add(Nodes.compare(other.column(relation.keys().get(i)), "=", column(relation.self().get(i))));
            }
            return Sql.all(pairs);
        };
        Class<? extends Entity> target = (Class<? extends Entity>) relation.target();
        return relation.many() ? new Many(ref, target, on, null) : one(target, (Function) on);
    }

    /** 同一方法、同样实参的结果只计算一次。递归调用不能用 computeIfAbsent，所以先查后放。 */
    private Object remember(Method method, Object[] args, Call call) throws Throwable {
        List<Object> key = List.of(method, Arrays.asList(args));
        Object known = remembered.get(key);
        if (known != null) return known;
        Object result = call.run();
        if (result == null) return null;
        Object raced = remembered.putIfAbsent(key, result);
        return raced != null ? raced : result;
    }

    /** 一次可能抛出任何异常的调用。 */
    @FunctionalInterface
    private interface Call {
        /** 执行调用并返回结果。 */
        Object run() throws Throwable;
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

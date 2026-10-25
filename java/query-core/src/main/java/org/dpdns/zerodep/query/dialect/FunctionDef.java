package org.dpdns.zerodep.query.dialect;

import java.util.List;

/**
 * 函数的身份标识。渲染时按顺序查找：数据库实例的覆盖、方言的注册，最后是本定义自带的标准写法。
 * 归某个方言独有的定义，在其他方言上会拒绝渲染，而不是猜一个等价写法。
 */
public final class FunctionDef {
    private final String name;
    private final FunctionRenderer standard;
    /** 独占该函数的方言；null 表示所有方言通用。 */
    private final Class<? extends Dialect> owner;
    /** 是否是聚合函数（多行合成一个值），见 {@link #aggregating()}。 */
    private final boolean aggregate;

    /** 由 of、only 与 aggregating 创建。 */
    private FunctionDef(String name, FunctionRenderer standard, Class<? extends Dialect> owner, boolean aggregate) {
        this.name = name;
        this.standard = standard;
        this.owner = owner;
        this.aggregate = aggregate;
    }

    /** 在所有数据库上都写成 NAME(arg, ...) 的函数。 */
    public static FunctionDef of(String name) { return new FunctionDef(name, (w, args) -> call(w, name, args), null, false); }

    /** 所有数据库通用、但标准写法不是 NAME(arg, ...) 的函数。 */
    public static FunctionDef of(String name, FunctionRenderer standard) { return new FunctionDef(name, standard, null, false); }

    /** 只存在于一个方言（及其子类）的函数，写成 NAME(arg, ...)。 */
    public static FunctionDef only(Class<? extends Dialect> owner, String name) {
        return new FunctionDef(name, (w, args) -> call(w, name, args), owner, false);
    }

    /** 只存在于一个方言（及其子类）的函数，并自带标准写法。 */
    public static FunctionDef only(Class<? extends Dialect> owner, String name, FunctionRenderer standard) {
        return new FunctionDef(name, standard, owner, false);
    }

    /**
     * 同一个函数，标成聚合函数（MEDIAN、STRING_AGG、CORR 等）：渲染时报到 {@link Aggregation}，于是按结果列筛选时它进 HAVING，
     * 数总行数时不把投影换成 COUNT(*)。{@code COUNT}、{@code SUM} 这些由 {@code Aggregate} 节点自己报到，不经过这里。
     */
    public FunctionDef aggregating() { return new FunctionDef(name, standard, owner, true); }

    /** 函数名，用于错误信息和调试。 */
    public String name() { return name; }

    /** 按"实例覆盖、方言注册、标准写法"的顺序选出渲染器并写出调用；独有函数在别的方言上抛出异常。 */
    public void render(SqlWriter w, List<? extends Renderable> args) {
        if (aggregate) Aggregation.aggregate(w);
        FunctionRenderer r = w.functionOverride(this);
        if (r == null) r = w.dialect().renderer(this);
        if (r == null) {
            if (owner != null && !owner.isInstance(w.dialect())) {
                throw new UnsupportedOperationException(name + " is a " + owner.getSimpleName() + " function; this database uses "
                    + w.dialect().name() + ". Use that dialect's equivalent, or register a renderer for it.");
            }
            r = standard;
        }
        r.render(w, args);
    }

    /** 写出 NAME(arg1, arg2, ...)。 */
    public static void call(SqlWriter w, String name, List<? extends Renderable> args) {
        w.sql(name).sql("(").join(args, ", ").sql(")");
    }

    @Override public String toString() { return name; }
}

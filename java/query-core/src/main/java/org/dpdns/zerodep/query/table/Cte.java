package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.CteDefinition;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;

import java.util.function.Function;

/**
 * 具名的公共表表达式（CTE）。行的结构由 {@code selectAs(Shape.class, ...)} 查询的 Shape 类描述，列名就是 Shape 的属性名。
 * 无论在语句里被引用多深，都只在语句开头的 WITH 里渲染一次。
 */
public final class Cte<E> implements Source<E>, CteDefinition {
    /** CTE 的名字，同时是 FROM 里的表名和默认别名。 */
    private final String name;

    /** CTE 的查询体；递归 CTE 在构造后、引用自己的部分建好时才赋值。 */
    private Query<E> body;

    /** 行的结构类，来自查询体的 {@code selectAs}。 */
    private final Class<E> shape;

    /** 递归 CTE 所属的方言，普通 CTE 为 null。 */
    private final Class<? extends Dialect> recursiveIn;

    /** 由 {@link Sql} 创建；名字不能为空。 */
    public Cte(String name, Query<E> body) {
        this(name, body.shape(), null);
        this.body = body;
    }

    private Cte(String name, Class<E> shape, Class<? extends Dialect> recursiveIn) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A CTE needs a name");
        if (shape == null) throw new IllegalArgumentException("A CTE body must map its rows to a class: end the query with into(Row.class) or selectAs(...)");
        this.name = name;
        this.shape = shape;
        this.recursiveIn = recursiveIn;
    }

    /**
     * 递归 CTE，供方言包使用（PostgreSQL、MySQL 的 {@code recursiveCte}）：查询体是 {@code anchor UNION ALL step(自己)}，
     * step 收到这个 CTE 本身，在里面 {@code from(self)} 引用上一轮的行。只能在 dialect 上渲染。
     */
    public static <E> Cte<E> recursive(String name, Query<E> anchor, Function<Cte<E>, Query<E>> step, Class<? extends Dialect> dialect) {
        return recursive(name, anchor, step, dialect, false);
    }

    /**
     * 同上；distinct 时写成 {@code anchor UNION step(自己)}：每一轮去掉已有的行，多条路径汇到同一行时只走一遍，
     * 父子成环也会停下（PostgreSQL、MySQL 8、SQLite 都支持）。
     */
    public static <E> Cte<E> recursive(String name, Query<E> anchor, Function<Cte<E>, Query<E>> step, Class<? extends Dialect> dialect, boolean distinct) {
        Cte<E> self = new Cte<>(name, anchor.shape(), dialect);
        Query<E> next = step.apply(self);
        self.body = distinct ? anchor.union(next) : anchor.unionAll(next);
        return self;
    }

    /** 递归 CTE 所属的方言，普通 CTE 为 null。 */
    @Override public Class<? extends Dialect> recursiveIn() { return recursiveIn; }

    /** CTE 的名字。 */
    @Override public String name() { return name; }

    /** 行的结构类。 */
    @Override public Class<E> entity() { return shape; }

    /** 写出语句开头 WITH 里的查询体（带列名）。 */
    @Override public void render(SqlWriter w) { QueryRenderer.named(w, body); }

    /** 登记到语句开头的 WITH，并在 FROM 或 JOIN 处写出 CTE 的名字。 */
    @Override public void renderSource(SqlWriter w) {
        w.cte(this);
        w.identifier(name);
    }

    /** CTE 的列名就是属性名，不受命名策略影响。 */
    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.name(); }

    /** 别名默认与 CTE 同名。 */
    @Override public String preferredAlias() { return name; }
}

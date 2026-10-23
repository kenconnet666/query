package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.CteDefinition;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;

/**
 * 具名的公共表表达式（CTE）。行的结构由 {@code selectAs(Shape.class, ...)} 查询的 Shape 类描述，列名就是 Shape 的属性名。
 * 无论在语句里被引用多深，都只在语句开头的 WITH 里渲染一次。
 */
public final class Cte<E> implements Source<E>, CteDefinition {
    /** CTE 的名字，同时是 FROM 里的表名和默认别名。 */
    private final String name;

    /** CTE 的查询体。 */
    private final Query<E> body;

    /** 行的结构类，来自查询体的 {@code selectAs}。 */
    private final Class<E> shape;

    /** 由 {@link Sql} 创建；名字不能为空。 */
    public Cte(String name, Query<E> body) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A CTE needs a name");
        this.name = name;
        this.body = body;
        this.shape = body.shape();
    }

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

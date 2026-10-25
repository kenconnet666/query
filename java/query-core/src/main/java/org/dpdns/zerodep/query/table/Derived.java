package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;

/** FROM 或 JOIN 里的子查询（派生表），行的结构由其具名投影的 Shape 类描述。 */
public final class Derived<E> implements Source<E> {
    /** 作为派生表的子查询。 */
    private final Query<?> query;

    /** 行的结构类（来自子查询的 {@code into}）。 */
    private final Class<E> shape;

    /** 别名的首选名。 */
    private final String alias;

    /** 由 {@link Query} 作为派生表使用时创建。 */
    public Derived(Query<E> query) {
        this.query = query;
        this.shape = query.shape();
        this.alias = "t";
    }

    /** 行的结构类。 */
    @Override public Class<E> entity() { return shape; }

    /** 写出带括号的子查询（带列名），别名由 {@link Ref} 追加。 */
    @Override public void renderSource(SqlWriter w) {
        w.sql("(");
        QueryRenderer.named(w, query);
        w.sql(")");
    }

    /** 派生表的列名就是属性名，不受命名策略影响。 */
    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.name(); }

    /** 别名的首选名，默认是 t。 */
    @Override public String preferredAlias() { return alias; }
}

package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;

/**
 * 一对多 {@code first(...)} 连接进来的那一行：构建时还不知道方言，两种写法都备好，渲染时按方言选（0018）。
 * <ul>
 *   <li>支持 LATERAL 的库（{@code Dialect.supportsLateral()}：PostgreSQL、MySQL）写成
 *       {@code LEFT JOIN LATERAL (SELECT … WHERE 键 = 父行的列 ORDER BY … LIMIT 1) x ON TRUE}：每个父行一次按索引取一行，
 *       父查询分页、只取几十行时不必给整张子表排名；</li>
 *   <li>其余（HANA、SQLite、版本未知的 PolarDB）写成 ROW_NUMBER 排名的派生表，连接条件是键相等且行号为 1。</li>
 * </ul>
 * 两种写法的列都是表接口的属性名，所以投影、后续的导航不分写法。
 */
final class FirstRow<E> implements Source<E> {
    /** ROW_NUMBER 排名的子查询（带行号列）。 */
    private final Query<?> ranked;

    /** 按父行取一行的相关子查询。 */
    private final Query<?> lateral;

    /** 行的表接口。 */
    private final Class<E> rows;

    /** 别名的首选名。 */
    private final String alias;

    FirstRow(Query<?> ranked, Query<?> lateral, Class<E> rows, String alias) {
        this.ranked = ranked;
        this.lateral = lateral;
        this.rows = rows;
        this.alias = alias;
    }

    @Override public Class<E> entity() { return rows; }

    /** 写出子查询：支持 LATERAL 的库写 {@code LATERAL (…)}，其余写排名的派生表。 */
    @Override public void renderSource(SqlWriter w) {
        boolean each = w.dialect().supportsLateral();
        w.sql(each ? "LATERAL (" : "(");
        QueryRenderer.named(w, each ? lateral : ranked);
        w.sql(")");
    }

    /** 子查询的列名就是属性名，不受命名策略影响。 */
    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.name(); }

    @Override public String preferredAlias() { return alias; }
}

package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.SqlTemplate;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

/**
 * 原生 SQL 写成的数据源，来自 {@code sqlTable(StockRow.class, "SELECT ... WHERE WERKS = {0}", plant)}：放进 FROM 或 JOIN，
 * 行的结构由 rows 类描述，列用 {@code t.col(StockRow::stock)} 读取。查询的每一列要用属性名作别名，并按方言加引号
 * （HANA、PostgreSQL 用双引号，MySQL 用反引号），因为外层按属性名引用这些列。模板和参数的规则见 {@link SqlTemplate}；
 * 用 {@link #on} 给某个方言另一种写法。
 *
 * @param <E> 描述行结构的类
 */
public final class SqlTable<E> implements Source<E> {
    /** 描述行结构的类。 */
    private final Class<E> rows;

    /** 查询的模板。 */
    private final SqlTemplate template;

    /** 由 {@code sqlTable(...)} 创建。 */
    public SqlTable(Class<E> rows, SqlTemplate template) {
        this.rows = rows;
        this.template = template;
    }

    /** 在 dialect 及其子类上改用 template；返回新的数据源。 */
    public SqlTable<E> on(Class<? extends Dialect> dialect, String template) { return new SqlTable<>(rows, this.template.on(dialect, template)); }

    /** 行的结构类。 */
    @Override public Class<E> entity() { return rows; }

    /** 写出带括号的查询，别名由 {@link Ref} 追加。 */
    @Override public void renderSource(SqlWriter w) { w.sql("(").render(template).sql(")"); }

    /** 别名的首选名，与派生表一样是 t。 */
    @Override public String preferredAlias() { return "t"; }

    /** 列名就是属性名，不受命名策略影响。 */
    @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.name(); }
}

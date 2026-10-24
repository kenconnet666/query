package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.SqlWriter;

/**
 * 原生 SQL 写成的条件，来自 {@code sqlCond("{0} IN ({1})", l.unit(), units)}。模板和参数的规则见 {@link SqlTemplate}；
 * 用 {@link #on} 给某个方言另一种写法。
 */
public final class SqlCond extends Cond {
    /** 要渲染的模板。 */
    private final SqlTemplate template;

    /** 由 {@code sqlCond(...)} 创建。 */
    public SqlCond(SqlTemplate template) { this.template = template; }

    /** 在 dialect 及其子类上改用 template。 */
    public SqlCond on(Class<? extends Dialect> dialect, String template) { return new SqlCond(this.template.on(dialect, template)); }

    /** 按当前方言写出模板。 */
    @Override public void render(SqlWriter w) { w.render(template); }
}

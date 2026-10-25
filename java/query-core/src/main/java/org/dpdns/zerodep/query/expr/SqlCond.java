package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.SqlWriter;

/**
 * 原生 SQL 写成的条件，来自 {@code sqlCond("{0} IN ({1})", l.unit(), units)}，创建时就写进当前的条件块。模板和参数的规则见 {@link SqlTemplate}；
 * 用 {@link #on} 给某个方言另一种写法。
 */
public final class SqlCond extends Cond {
    /** 要渲染的模板；{@link #on} 把它换成带方言写法的模板。 */
    private SqlTemplate template;

    /** 由 {@code sqlCond(...)} 创建。 */
    public SqlCond(SqlTemplate template) { this.template = template; }

    /** 在 dialect 及其子类上改用 template。条件已经写进条件块，所以改的是这个条件本身，返回它以便接着写。 */
    public SqlCond on(Class<? extends Dialect> dialect, String template) {
        this.template = this.template.on(dialect, template);
        return this;
    }

    /** 按当前方言写出模板。 */
    @Override public void render(SqlWriter w) { w.render(template); }
}

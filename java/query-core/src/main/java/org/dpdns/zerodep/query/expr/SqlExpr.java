package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

/**
 * 原生 SQL 写成的表达式，来自 {@code sql(String.class, "LPAD({0}, 18, '0')", l.material())}，结果类型由调用方声明；
 * 要用某种类型的方法时包一层，例如 {@code ExprText.of(sql(String.class, ...)).upper()}。
 * 模板和参数的规则见 {@link SqlTemplate}；用 {@link #on} 给某个方言另一种写法。
 *
 * @param <T> 表达式的值类型
 */
public final class SqlExpr<T> extends ExprNode<T> {
    /** 要渲染的模板。 */
    private final SqlTemplate template;

    /** 结果类型和模板。 */
    public SqlExpr(SqlType<T> type, SqlTemplate template) {
        super(type);
        this.template = template;
    }

    /** 在 dialect 及其子类上改用 template，例如 {@code .on(MysqlDialect.class, "LPAD({0}, 18, '0')")}。 */
    public SqlExpr<T> on(Class<? extends Dialect> dialect, String template) { return new SqlExpr<>(type(), this.template.on(dialect, template)); }

    /** 按当前方言写出模板。 */
    @Override public void render(SqlWriter w) { w.render(template); }
}

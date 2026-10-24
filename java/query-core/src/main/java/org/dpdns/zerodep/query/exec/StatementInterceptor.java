package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;

/**
 * 语句执行前的钩子：查询、写语句、计数、原生 SQL 都经过它。返回要执行的语句，原样返回表示不改；
 * 抛出异常则语句不执行，异常原样传给调用方。用 {@code new Rendered(sql, statement.bindings(), statement.dialect())}
 * 改写 SQL 文本，例如加注释或提示；改写不要改变参数的个数和顺序。
 * <pre>{@code
 * Hana.database(connections, c -> c.intercept(s -> {
 *     if (s.sql().startsWith("DELETE") && readOnlyMode()) throw new IllegalStateException("read-only");
 *     return new Rendered(s.sql() + "\n-- app=report", s.bindings(), s.dialect());
 * }));
 * }</pre>
 * 多个钩子按注册顺序依次调用，前一个的结果交给后一个。设置了 {@code cached(...)} 的查询按改写后的 SQL 作缓存键。
 */
@FunctionalInterface
public interface StatementInterceptor {
    /** 返回要执行的语句；不能返回 null。 */
    Rendered intercept(Rendered statement);
}

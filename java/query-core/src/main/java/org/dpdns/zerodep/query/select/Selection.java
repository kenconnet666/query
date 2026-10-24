package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.expr.Expr;

/**
 * {@code select(...)} 接受的投影：一个表达式（单列查询）、{@link Sql#cols}（按列名取行，可用
 * {@code into(X.class)} 映射成类），或 {@code row(X::new, ...)}（按位置喂给构造器，可以继续嵌套 row）。
 *
 * @param <R> 每行读出的 Java 类型
 */
public interface Selection<R> { }

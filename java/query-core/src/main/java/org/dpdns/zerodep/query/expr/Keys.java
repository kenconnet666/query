package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.Sql;

/**
 * {@code groupBy} 和 {@code selectAs} 接受的键：一个表达式，或用 {@link Sql#keys} 组合的多个。
 * 一个键写 {@code groupBy(l -> l.col(Lips::getMaterial))}，多个键写
 * {@code groupBy(l -> keys(l.col(Lips::getMaterial), l.col(Lips::getUnit)))}。
 */
public sealed interface Keys permits Expr, KeyList { }

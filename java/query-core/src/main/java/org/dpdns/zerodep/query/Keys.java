package org.dpdns.zerodep.query;

/**
 * What {@code groupBy} and {@code selectAs} take: one expression, or several from {@link Sql#keys}.
 * Write {@code groupBy(l -> l.col(Lips::getMaterial))} for one key and
 * {@code groupBy(l -> keys(l.col(Lips::getMaterial), l.col(Lips::getUnit)))} for more.
 */
public sealed interface Keys permits Expr, KeyList { }

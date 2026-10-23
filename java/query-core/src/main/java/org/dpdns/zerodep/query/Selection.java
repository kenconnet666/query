package org.dpdns.zerodep.query;

/**
 * What {@code select(...)} takes: one expression (a single-column query), {@link Sql#cols} (rows by
 * column name, mapped with {@code into(X.class)}), or {@code row(X::new, ...)} (a constructor fed by
 * position, nesting further rows).
 */
public sealed interface Selection<R> permits Expr, Cols, RowSelection { }

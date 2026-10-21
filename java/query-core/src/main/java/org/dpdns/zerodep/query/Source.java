package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

import java.util.Locale;

/**
 * Something a query reads rows from: a table, a CTE, a derived table. Implement it to add a source
 * kind, e.g. a parameterized view of one dialect.
 *
 * @param <E> the class whose properties describe the rows
 */
public interface Source<E> {
    Class<E> entity();

    /** Writes the FROM or JOIN item without its alias. */
    void renderSource(SqlWriter w);

    /** The column name a property has in this source. */
    String column(PropertyMeta property, NamingStrategy naming);

    default String preferredAlias() { return entity().getSimpleName().toLowerCase(Locale.ROOT); }

    /** Whether database-wide filters apply to this source; true for tables. */
    default boolean filtered() { return false; }
}

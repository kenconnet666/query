package org.dpdns.zerodep.query.dialect;

/**
 * A dialect-only piece of a SELECT statement, such as HANA's {@code WITH HINT(...)}, PostgreSQL's
 * {@code DISTINCT ON (...)} or a MySQL optimizer hint. Rendering on another dialect is rejected.
 */
public interface Clause extends Renderable {
    enum Position {
        /** Right after the SELECT keyword, e.g. a MySQL optimizer hint comment. */
        HINT,
        /** In place of DISTINCT, e.g. DISTINCT ON (...). */
        DISTINCT,
        /** After ORDER BY and LIMIT. */
        END
    }

    Position position();

    /** The dialect this clause belongs to; null for clauses valid everywhere. */
    Class<? extends Dialect> dialect();
}

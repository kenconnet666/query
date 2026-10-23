package org.dpdns.zerodep.query;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * The {@code skipEmpty()} mode of a scope: while its WHERE, HAVING and ON lambdas run, a comparison
 * with null, a blank string or an empty collection is no condition at all. A subquery built inside
 * such a lambda inherits the mode. Outside it, comparing with null stays an error.
 */
final class SkipEmpty {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private SkipEmpty() { }

    static <T> T within(boolean on, Supplier<T> body) {
        if (!on || active()) return body.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            ACTIVE.remove();
        }
    }

    static boolean active() { return ACTIVE.get() != null; }

    /** Whether a condition on this value is dropped. */
    static boolean skips(Object value) { return active() && isEmpty(value); }

    private static boolean isEmpty(Object value) {
        return value == null || value instanceof String s && s.isBlank() || value instanceof Collection<?> c && c.isEmpty();
    }
}

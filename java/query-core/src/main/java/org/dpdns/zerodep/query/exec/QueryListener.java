package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;

import java.time.Duration;

/** Observes executed statements: logging, timing, slow-query reports. Exceptions it throws are suppressed. */
@FunctionalInterface
public interface QueryListener {
    /**
     * @param rows  rows read, or -1 for a streamed result whose size is not known when it starts
     * @param error the failure, or null
     */
    record Event(String database, Rendered statement, Duration elapsed, long rows, Throwable error, boolean cached) { }

    void executed(Event event);
}

package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;

/** A failed statement, carrying its SQL so the cause can be found without a debugger. */
public final class QueryExecutionException extends RuntimeException {
    private final transient Rendered statement;
    private final boolean timeout;

    public QueryExecutionException(String database, Rendered statement, Throwable cause, boolean timeout) {
        super("Statement " + (timeout ? "timed out" : "failed") + " on " + database + ": " + cause.getMessage() + "\n" + statement.sql(), cause);
        this.statement = statement;
        this.timeout = timeout;
    }

    public Rendered statement() { return statement; }

    /** Whether the database cancelled the statement because it ran past its timeout. */
    public boolean isTimeout() { return timeout; }
}

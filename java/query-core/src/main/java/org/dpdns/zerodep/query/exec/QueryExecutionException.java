package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;

/** A failed statement, carrying its SQL so the cause can be found without a debugger. */
public final class QueryExecutionException extends RuntimeException {
    private final transient Rendered statement;

    public QueryExecutionException(String database, Rendered statement, Throwable cause) {
        super("Statement failed on " + database + ": " + cause.getMessage() + "\n" + statement.sql(), cause);
        this.statement = statement;
    }

    public Rendered statement() { return statement; }
}

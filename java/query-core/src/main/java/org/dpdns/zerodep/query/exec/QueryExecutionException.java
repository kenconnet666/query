package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;

/** 语句执行失败，异常里带着 SQL，不开调试器也能定位原因。 */
public final class QueryExecutionException extends RuntimeException implements CodedException {
    private final transient Rendered statement;
    private final boolean timeout;

    /**
     * @param database  执行语句的数据库实例名
     * @param statement 失败的语句
     * @param cause     底层异常，通常是 {@link java.sql.SQLException}
     * @param timeout   是否因超时被数据库取消
     */
    public QueryExecutionException(String database, Rendered statement, Throwable cause, boolean timeout) {
        super("Statement " + (timeout ? "timed out" : "failed") + " on " + database + ": " + cause.getMessage() + "\n" + statement.sql(), cause);
        this.statement = statement;
        this.timeout = timeout;
    }

    /** 失败的那条已渲染语句。 */
    public Rendered statement() { return statement; }

    /** 数据库是否因语句超过超时时间而取消了它。 */
    public boolean isTimeout() { return timeout; }

    /** 超时是 {@code query.statement-timeout}，其余是 {@code query.execution-failed}。 */
    @Override
    public QueryErrorCode code() { return timeout ? QueryErrorCode.STATEMENT_TIMEOUT : QueryErrorCode.EXECUTION_FAILED; }
}

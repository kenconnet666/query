package org.dpdns.zerodep.query.exec;

/**
 * query 的业务错误码（flow 仓库 docs/decisions/0008）：HTTP 接口按它写响应体的 {@code code}，前端按它取文案。
 * 与 spec/error-codes.json 里 package 为 core 的条目一致，先后也一样；{@link #code()} 是响应体里的写法。
 */
public enum QueryErrorCode {
    /** 表格请求读不懂（{@link InvalidRequestException}） */
    REQUEST_INVALID("query.request-invalid"),
    /** 要求恰好一行的查询没有读到行（{@link EmptyResultException}） */
    EMPTY_RESULT("query.empty-result"),
    /** 最多一行的查询读到了多行（{@link TooManyRowsException}） */
    TOO_MANY_ROWS("query.too-many-rows"),
    /** 写语句影响的行数与期望的不符（{@link UnexpectedRowCountException}） */
    UNEXPECTED_ROW_COUNT("query.unexpected-row-count"),
    /** 语句超时被数据库取消（{@link QueryExecutionException#isTimeout()}） */
    STATEMENT_TIMEOUT("query.statement-timeout"),
    /** 语句执行失败（{@link QueryExecutionException}） */
    EXECUTION_FAILED("query.execution-failed");

    private final String code;

    QueryErrorCode(String code) { this.code = code; }

    /** 响应体里的写法 */
    public String code() { return code; }
}

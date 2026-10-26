package org.dpdns.zerodep.query.exec;

/**
 * 表格请求读不懂：页码、条数写错，条件树写错，排序、筛选用了结果里没有的列。说明的是前端传来的参数，Web 层可以原样给前端看。
 * 继承 {@link IllegalArgumentException}，按参数错误统一处理的全局异常处理照常认它。
 */
public class InvalidRequestException extends IllegalArgumentException implements CodedException {
    /** 带说明的异常。 */
    public InvalidRequestException(String message) { super(message); }

    /** 带说明和底层异常。 */
    public InvalidRequestException(String message, Throwable cause) { super(message, cause); }

    @Override
    public QueryErrorCode code() { return QueryErrorCode.REQUEST_INVALID; }
}

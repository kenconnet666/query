package org.dpdns.zerodep.query.exec;

/**
 * 带业务错误码的异常：query 抛出的异常都实现它，Web 层不必逐个认类型，
 * {@code e instanceof CodedException c ? c.code().code() : null} 就能写成响应体的 {@code code}。
 */
public interface CodedException {
    /** 业务错误码 */
    QueryErrorCode code();
}

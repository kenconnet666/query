package org.dpdns.zerodep.query.exec;

/**
 * 要求恰好一行（{@code db.one(q)}）的查询没有读到行。继承 {@link IllegalStateException}，
 * 应用可以在全局异常处理里把它单独映射成"未找到"。
 */
public class EmptyResultException extends IllegalStateException {
    /** 带说明的异常。 */
    public EmptyResultException(String message) { super(message); }
}

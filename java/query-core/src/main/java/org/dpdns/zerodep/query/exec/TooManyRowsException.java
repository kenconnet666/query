package org.dpdns.zerodep.query.exec;

/**
 * 最多一行（{@code db.one(q)}、{@code db.optional(q)}）的查询读到了多行。继承 {@link IllegalStateException}，
 * 通常说明条件写漏了，应用可以单独处理。
 */
public class TooManyRowsException extends IllegalStateException {
    /** 带说明的异常。 */
    public TooManyRowsException(String message) { super(message); }
}

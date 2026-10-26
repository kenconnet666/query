package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;

/**
 * 写语句影响的行数与期望的不符：{@code expect(n)} 写明的行数，或 {@code byId(row)} 期望的每行一行。
 * 带版本列的按主键更新出现它，通常是别人已经改过或删掉了这一行（乐观锁冲突）。
 */
public final class UnexpectedRowCountException extends RuntimeException implements CodedException {
    /** 期望影响的行数。 */
    private final long expected;

    /** 实际影响的行数。 */
    private final long actual;

    /** 由数据库实例在执行写语句后抛出；statement 是第一条语句，用于报错信息。 */
    public UnexpectedRowCountException(String database, Rendered statement, long expected, long actual) {
        super(database + ": expected " + expected + " row(s) to change but " + actual + " did; the rows may have been changed or deleted by "
            + "someone else\n" + statement.sql());
        this.expected = expected;
        this.actual = actual;
    }

    /** 期望影响的行数。 */
    public long expected() { return expected; }

    /** 实际影响的行数。 */
    public long actual() { return actual; }

    @Override
    public QueryErrorCode code() { return QueryErrorCode.UNEXPECTED_ROW_COUNT; }
}

package org.dpdns.zerodep.query.dialect;

/** 加锁读（{@code forUpdate(...)}）遇到别的事务锁住的行时怎么办。 */
public enum Lock {
    /** 等待锁释放（{@code FOR UPDATE}）。 */
    WAIT,
    /** 不等待，立即报错（{@code FOR UPDATE NOWAIT}）。 */
    NOWAIT,
    /** 跳过被锁住的行（PostgreSQL、MySQL 的 {@code SKIP LOCKED}，HANA 的 {@code IGNORE LOCKED}），适合多个工作进程各自领取任务。 */
    SKIP_LOCKED
}

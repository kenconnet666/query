package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;

import java.time.Duration;

/** 观察已执行的语句：日志、计时、慢查询报告。监听器抛出的异常会被忽略。 */
@FunctionalInterface
public interface QueryListener {
    /**
     * 一次语句执行的结果。
     *
     * @param database  执行语句的数据库实例名
     * @param statement 已渲染的语句
     * @param elapsed   耗时；流式结果从开始算到流关闭
     * @param rows      读取的行数；流式结果在流关闭时报告已读出的行数
     * @param error     失败原因，成功时为 null
     * @param cached    是否命中了查询缓存（命中时没有访问数据库）
     */
    record Event(String database, Rendered statement, Duration elapsed, long rows, Throwable error, boolean cached) { }

    /** 每条语句执行结束后调用一次。 */
    void executed(Event event);
}

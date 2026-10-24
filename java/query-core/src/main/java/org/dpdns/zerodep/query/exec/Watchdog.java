package org.dpdns.zerodep.query.exec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 给不按 {@code setQueryTimeout} 取消语句的驱动（sqlite-jdbc）补上超时：到时调用 {@code cancel()}，
 * 驱动随即以中断错误结束语句；语句关闭时撤销，所以已经结束的语句不会被误取消。
 */
final class Watchdog {
    /** 所有看门狗共用的一个守护线程。 */
    private static final ScheduledThreadPoolExecutor TIMER = timer();

    /** 工具类，不能实例化。 */
    private Watchdog() { }

    /** 新建守护线程的定时器，撤销的任务立即移除。 */
    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "query-statement-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        t.setRemoveOnCancelPolicy(true);
        return t;
    }

    /** 包一层：limit 之后取消 ps，关闭返回的语句时撤销定时并关闭 ps。 */
    static PreparedStatement guard(PreparedStatement ps, Duration limit) {
        ScheduledFuture<?> timeout = TIMER.schedule(() -> {
            try {
                ps.cancel();
            } catch (SQLException ignored) {
                // 语句已经结束或已关闭：没有可取消的了
            }
        }, limit.toMillis(), TimeUnit.MILLISECONDS);
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
            if (method.getName().equals("close") && method.getParameterCount() == 0) timeout.cancel(false);
            try {
                return method.invoke(ps, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}

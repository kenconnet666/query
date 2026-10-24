package org.dpdns.zerodep.query.exec;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 把每条执行过的语句打印成 MyBatis 风格的几行，由 {@code Config.printSql(...)} 装上：
 * <pre>
 * ==> Preparing: SELECT LIPS.VBELN AS "delivery" FROM ERP.LIPS LIPS WHERE LIPS.MATNR = ?
 * ==> Parameters: M1(String)
 * &lt;== Total: 3, Time: 12 ms
 * </pre>
 * 命中缓存时在最后一行标明 {@code (cached)}，失败时最后一行是错误信息。
 */
public final class SqlPrinter implements QueryListener {
    /** 默认输出：JDK 自带的 {@link System.Logger}，名为 {@code org.dpdns.zerodep.query.sql}，级别 INFO。 */
    private static final System.Logger LOGGER = System.getLogger("org.dpdns.zerodep.query.sql");

    /** 打印的去处。 */
    private final Consumer<String> out;

    /** 打印到 out，例如 {@code log::info} 或 {@code System.out::println}。 */
    public SqlPrinter(Consumer<String> out) { this.out = Objects.requireNonNull(out, "out"); }

    /** 打印到 JDK 的 {@link System.Logger}；接 SLF4J 时加上 slf4j-jdk-platform-logging 即可。 */
    public static SqlPrinter toSystemLogger() { return new SqlPrinter(line -> LOGGER.log(System.Logger.Level.INFO, line)); }

    /** 把一条语句的执行结果格式化成多行文本，作为一条消息输出。 */
    @Override
    public void executed(Event event) { out.accept(format(event)); }

    /** 格式化一条语句：SQL、带类型的参数，以及行数和耗时（或错误）。 */
    public static String format(Event event) {
        StringBuilder s = new StringBuilder("==> Preparing: ").append(event.statement().sql());
        if (!event.statement().bindings().isEmpty()) s.append("\n==> Parameters: ").append(event.statement().parameterText());
        if (event.error() != null) {
            s.append("\n<== Error: ").append(event.error().getMessage());
        } else {
            s.append("\n<== Total: ").append(event.rows()).append(", Time: ").append(event.elapsed().toMillis()).append(" ms");
            if (event.cached()) s.append(" (cached)");
        }
        return s.toString();
    }
}

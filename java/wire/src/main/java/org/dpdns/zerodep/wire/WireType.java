package org.dpdns.zerodep.wire;

import java.util.Locale;

/**
 * 线上要还原的值的种类：JSON 不能原样承载的值写成文字，{@code $types} 记下它们在哪里、是哪一种。
 * 与 TypeScript 的 {@code @zerodep-dev/wire}、vue-tsx-pro 的请求层是同一套词（docs/decisions/0015）。
 *
 * <table>
 *   <caption>种类、Java 类型与文字</caption>
 *   <tr><th>种类</th><th>Java</th><th>TypeScript</th><th>文字</th></tr>
 *   <tr><td>bigint</td><td>Long、BigInteger</td><td>bigint</td><td>{@code "9007199254740993"}</td></tr>
 *   <tr><td>decimal</td><td>BigDecimal</td><td>Decimal</td><td>{@code "12.50"}，不用科学计数法</td></tr>
 *   <tr><td>double</td><td>NaN、±Infinity 的 Double、Float</td><td>number</td><td>{@code "NaN"}；有限的值是 JSON 数字</td></tr>
 *   <tr><td>date</td><td>LocalDate</td><td>CalendarDate</td><td>{@code "2024-01-31"}</td></tr>
 *   <tr><td>time</td><td>LocalTime</td><td>Time</td><td>{@code "13:45:00"}</td></tr>
 *   <tr><td>timestamp</td><td>LocalDateTime</td><td>CalendarDateTime</td><td>{@code "2024-01-31T13:45:00"}，秒以下有值时带小数</td></tr>
 *   <tr><td>timestamptz</td><td>OffsetDateTime、Instant、ZonedDateTime</td><td>ZonedDateTime、Date</td><td>{@code "2024-01-31T05:45:00Z"}，换算成 UTC</td></tr>
 *   <tr><td>bytes</td><td>byte[]</td><td>Uint8Array</td><td>标准 base64</td></tr>
 * </table>
 *
 * 其余的值照 JSON 原样：文字、布尔、Integer、Short、Byte 和有限的 Double、Float。
 */
public enum WireType {
    /** 任意大小的整数。 */
    BIGINT,
    /** 精确的小数。 */
    DECIMAL,
    /** 不是有限数的浮点数。 */
    DOUBLE,
    /** 日历日期。 */
    DATE,
    /** 一天里的时刻。 */
    TIME,
    /** 不带时区的日期时间。 */
    TIMESTAMP,
    /** 带时差的时刻，写成 UTC。 */
    TIMESTAMPTZ,
    /** 字节。 */
    BYTES;

    /** {@code $types} 里的写法：小写的名字。 */
    public String wireName() { return name().toLowerCase(Locale.ROOT); }
}

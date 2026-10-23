package org.dpdns.zerodep.query.type;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 逻辑上的 CAST 目标类型；具体类型名由各方言自己渲染。
 *
 * @param <T> 转换后表达式的 Java 类型
 */
public final class CastType<T> {
    /** 目标类型的种类，方言按它选择类型名。 */
    public enum Kind { TEXT, DECIMAL, INTEGER, BIGINT, DOUBLE, DATE, TIMESTAMP, BOOLEAN }

    private final Kind kind;
    private final int length;
    private final int scale;
    private final Class<T> javaType;

    private CastType(Kind kind, int length, int scale, Class<T> javaType) {
        this.kind = kind;
        this.length = length;
        this.scale = scale;
        this.javaType = javaType;
    }

    /** 定长上限的文本类型，如 VARCHAR(length)；长度必须为正。 */
    public static CastType<String> text(int length) {
        if (length < 1) throw new IllegalArgumentException("Text length must be positive");
        return new CastType<>(Kind.TEXT, length, 0, String.class);
    }

    /** 定点小数类型 DECIMAL(precision, scale)；scale 不能大于 precision。 */
    public static CastType<BigDecimal> decimal(int precision, int scale) {
        if (precision < 1 || scale < 0 || scale > precision) throw new IllegalArgumentException("Invalid DECIMAL(" + precision + "," + scale + ")");
        return new CastType<>(Kind.DECIMAL, precision, scale, BigDecimal.class);
    }

    /** 32 位整数。 */
    public static CastType<Integer> integer() { return new CastType<>(Kind.INTEGER, 0, 0, Integer.class); }
    /** 64 位整数。 */
    public static CastType<Long> bigint() { return new CastType<>(Kind.BIGINT, 0, 0, Long.class); }
    /** 双精度浮点数。 */
    public static CastType<Double> doublePrecision() { return new CastType<>(Kind.DOUBLE, 0, 0, Double.class); }
    /** 日期。 */
    public static CastType<LocalDate> date() { return new CastType<>(Kind.DATE, 0, 0, LocalDate.class); }
    /** 不带时区的时间戳。 */
    public static CastType<LocalDateTime> timestamp() { return new CastType<>(Kind.TIMESTAMP, 0, 0, LocalDateTime.class); }
    /** 布尔值。 */
    public static CastType<Boolean> bool() { return new CastType<>(Kind.BOOLEAN, 0, 0, Boolean.class); }

    /** 类型种类。 */
    public Kind kind() { return kind; }

    /** TEXT 的长度，DECIMAL 的精度；其他种类为 0。 */
    public int length() { return length; }

    /** DECIMAL 的小数位数；其他种类为 0。 */
    public int scale() { return scale; }

    /** 转换后表达式对应的 Java 类型。 */
    public Class<T> javaType() { return javaType; }
}

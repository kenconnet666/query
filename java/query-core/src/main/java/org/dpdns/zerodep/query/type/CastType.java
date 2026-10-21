package org.dpdns.zerodep.query.type;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A logical CAST target; each dialect renders its own type name. */
public final class CastType<T> {
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

    public static CastType<String> text(int length) {
        if (length < 1) throw new IllegalArgumentException("Text length must be positive");
        return new CastType<>(Kind.TEXT, length, 0, String.class);
    }

    public static CastType<BigDecimal> decimal(int precision, int scale) {
        if (precision < 1 || scale < 0 || scale > precision) throw new IllegalArgumentException("Invalid DECIMAL(" + precision + "," + scale + ")");
        return new CastType<>(Kind.DECIMAL, precision, scale, BigDecimal.class);
    }

    public static CastType<Integer> integer() { return new CastType<>(Kind.INTEGER, 0, 0, Integer.class); }
    public static CastType<Long> bigint() { return new CastType<>(Kind.BIGINT, 0, 0, Long.class); }
    public static CastType<Double> doublePrecision() { return new CastType<>(Kind.DOUBLE, 0, 0, Double.class); }
    public static CastType<LocalDate> date() { return new CastType<>(Kind.DATE, 0, 0, LocalDate.class); }
    public static CastType<LocalDateTime> timestamp() { return new CastType<>(Kind.TIMESTAMP, 0, 0, LocalDateTime.class); }
    public static CastType<Boolean> bool() { return new CastType<>(Kind.BOOLEAN, 0, 0, Boolean.class); }

    public Kind kind() { return kind; }

    /** Length for TEXT, precision for DECIMAL. */
    public int length() { return length; }

    public int scale() { return scale; }

    public Class<T> javaType() { return javaType; }
}

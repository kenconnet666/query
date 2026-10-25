package org.dpdns.zerodep.query.type;

import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.typed.ExprBigint;
import org.dpdns.zerodep.query.expr.typed.ExprBoolean;
import org.dpdns.zerodep.query.expr.typed.ExprDate;
import org.dpdns.zerodep.query.expr.typed.ExprDecimal;
import org.dpdns.zerodep.query.expr.typed.ExprDouble;
import org.dpdns.zerodep.query.expr.typed.ExprInteger;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.expr.typed.ExprTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * 逻辑上的 CAST 目标类型；具体类型名由各方言自己渲染。
 *
 * @param <T> 转换后表达式的 Java 类型
 * @param <F> 转换后表达式的类型接口，例如 {@code ExprText<String>}
 */
public final class CastType<T, F extends Expr<T>> {
    /** 目标类型的种类，方言按它选择类型名。 */
    public enum Kind { TEXT, DECIMAL, INTEGER, BIGINT, DOUBLE, DATE, TIMESTAMP, BOOLEAN }

    private final Kind kind;
    private final int length;
    private final int scale;
    private final Class<T> javaType;
    private final Function<Expr<T>, F> family;

    /** 由各个静态工厂创建。 */
    private CastType(Kind kind, int length, int scale, Class<T> javaType, Function<Expr<T>, F> family) {
        this.kind = kind;
        this.length = length;
        this.scale = scale;
        this.javaType = javaType;
        this.family = family;
    }

    /** 定长上限的文本类型，如 VARCHAR(length)；长度必须为正。 */
    public static CastType<String, ExprText<String>> text(int length) {
        if (length < 1) throw new IllegalArgumentException("Text length must be positive");
        return new CastType<>(Kind.TEXT, length, 0, String.class, ExprText::of);
    }

    /** 定点小数类型 DECIMAL(precision, scale)；scale 不能大于 precision。 */
    public static CastType<BigDecimal, ExprDecimal<BigDecimal>> decimal(int precision, int scale) {
        if (precision < 1 || scale < 0 || scale > precision) throw new IllegalArgumentException("Invalid DECIMAL(" + precision + "," + scale + ")");
        return new CastType<>(Kind.DECIMAL, precision, scale, BigDecimal.class, ExprDecimal::of);
    }

    /** 32 位整数。 */
    public static CastType<Integer, ExprInteger<Integer>> integer() { return new CastType<>(Kind.INTEGER, 0, 0, Integer.class, ExprInteger::of); }
    /** 64 位整数。 */
    public static CastType<Long, ExprBigint<Long>> bigint() { return new CastType<>(Kind.BIGINT, 0, 0, Long.class, ExprBigint::of); }
    /** 双精度浮点数。 */
    public static CastType<Double, ExprDouble<Double>> doublePrecision() { return new CastType<>(Kind.DOUBLE, 0, 0, Double.class, ExprDouble::of); }
    /** 日期。 */
    public static CastType<LocalDate, ExprDate<LocalDate>> date() { return new CastType<>(Kind.DATE, 0, 0, LocalDate.class, ExprDate::of); }
    /** 不带时区的时间戳。 */
    public static CastType<LocalDateTime, ExprTimestamp<LocalDateTime>> timestamp() {
        return new CastType<>(Kind.TIMESTAMP, 0, 0, LocalDateTime.class, ExprTimestamp::of);
    }
    /** 布尔值。 */
    public static CastType<Boolean, ExprBoolean<Boolean>> bool() { return new CastType<>(Kind.BOOLEAN, 0, 0, Boolean.class, ExprBoolean::of); }

    /** 类型种类。 */
    public Kind kind() { return kind; }

    /** TEXT 的长度，DECIMAL 的精度；其他种类为 0。 */
    public int length() { return length; }

    /** DECIMAL 的小数位数；其他种类为 0。 */
    public int scale() { return scale; }

    /** 转换后表达式对应的 Java 类型。 */
    public Class<T> javaType() { return javaType; }

    /** 把 CAST 节点包成目标类型的表达式。 */
    public F wrap(Expr<T> cast) { return family.apply(cast); }
}

package org.dpdns.zerodep.query.expr.typed;

import org.dpdns.zerodep.query.expr.Aggregate;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.Window;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 库里存成数值的表达式的共同父接口；具体的类型是 {@link ExprInteger}、{@link ExprBigint}、{@link ExprDecimal}、{@link ExprDouble} 等，
 * 它们把这里的方法重写为确切的结果类型。辅助方法要收"任意数值"时参数写 {@code ExprNumber<?>}。
 *
 * <p>加减乘（{@code plus}、{@code minus}、{@code times}）只在具体类型上：结果取两边里"宽"的那个，
 * 浮点 &gt; 小数 &gt; 长整数 &gt; 整数（短整数相加按整数），例如整数加小数得到 {@code ExprDecimal}。
 *
 * @param <T> 值的 Java 类型；按编码存的枚举等经转换器的值也可以是数值列，计算结果则是库里的数值类型
 */
public interface ExprNumber<T> extends Expr<T> {
    // ---------- 结果仍是本类型（具体类型给出确切的类型） ----------

    @Override ExprNumber<T> coalesce(T fallback);

    @Override ExprNumber<T> coalesce(Expr<T> fallback);

    @Override ExprNumber<T> coalesce(Expr<T> fallback, Expr<T> last);

    @Override ExprNumber<T> nullif(T value);

    @Override ExprNumber<T> max();

    @Override ExprNumber<T> min();

    @Override ExprNumber<T> filter(Runnable condition);

    @Override ExprNumber<T> over(UnaryOperator<Window> window);

    // ---------- 结果是库里的数值类型（具体类型给出确切的类型） ----------

    /** 绝对值。 */
    ExprNumber<? extends Number> abs();

    /** 向下取整。 */
    ExprNumber<? extends Number> floor();

    /** 向上取整。 */
    ExprNumber<? extends Number> ceil();

    /** 保留 scale 位小数四舍五入。 */
    ExprNumber<? extends Number> round(int scale);

    /** 取负：{@code -(this)}。 */
    ExprNumber<? extends Number> negate();

    /** 余数，符号与被除数相同：-7 对 3 取余是 -1。除数为 0 时 HANA、PostgreSQL 报错，MySQL 得到 NULL，与除法相同。 */
    ExprNumber<? extends Number> mod(int divisor);

    /** 同上，除数是表达式。 */
    ExprNumber<? extends Number> mod(Expr<? extends Number> divisor);

    /** 求和（聚合）。 */
    ExprNumber<? extends Number> sum();

    /** SUM(DISTINCT this)：相同的值只加一次。 */
    ExprNumber<? extends Number> sumDistinct();

    // ---------- 结果类型固定 ----------

    /** 符号：负数为 -1，零为 0，正数为 1。 */
    default ExprInteger<Integer> sign() { return ExprInteger.of(Nodes.call(SqlType.of(Integer.class), "SIGN", List.of(this))); }

    /** 平方根，结果是浮点数；负数时报错。 */
    default ExprDouble<Double> sqrt() { return ExprDouble.of(Nodes.call(SqlType.of(Double.class), "SQRT", List.of(this))); }

    /** this 的 exponent 次方，结果是浮点数。 */
    default ExprDouble<Double> power(int exponent) { return ExprDouble.of(Nodes.call(SqlType.of(Double.class), "POWER", List.of(this, new Nodes.Literal<>(SqlType.of(Integer.class), exponent)))); }

    /** 同上，指数是表达式。 */
    default ExprDouble<Double> power(Expr<? extends Number> exponent) { return ExprDouble.of(Nodes.call(SqlType.of(Double.class), "POWER", List.of(this, exponent))); }

    /** 平均值（聚合），结果是小数。 */
    default ExprDecimal<BigDecimal> avg() { return ExprDecimal.of(new Aggregate<>(SqlType.of(BigDecimal.class), "AVG", false, List.of(this))); }

    /** AVG(DISTINCT this)，结果是小数。 */
    default ExprDecimal<BigDecimal> avgDistinct() { return ExprDecimal.of(new Aggregate<>(SqlType.of(BigDecimal.class), "AVG", true, List.of(this))); }

    /**
     * 样本标准差 STDDEV_SAMP（聚合），只有一行时为 NULL。参数先转成 DOUBLE：HANA 对整数按定点小数计算，
     * 只保留 6 位小数，转换后三库结果一致。
     */
    default ExprDouble<Double> stddevSamp() { return statistic("STDDEV_SAMP"); }

    /** 总体标准差 STDDEV_POP；参数先转成 DOUBLE，见 {@link #stddevSamp}。 */
    default ExprDouble<Double> stddevPop() { return statistic("STDDEV_POP"); }

    /** 样本方差 VAR_SAMP，只有一行时为 NULL；参数先转成 DOUBLE，见 {@link #stddevSamp}。 */
    default ExprDouble<Double> varSamp() { return statistic("VAR_SAMP"); }

    /** 总体方差 VAR_POP；参数先转成 DOUBLE，见 {@link #stddevSamp}。 */
    default ExprDouble<Double> varPop() { return statistic("VAR_POP"); }

    /** 浮点结果的统计聚合。MySQL 的 STDDEV 是总体标准差，与另两库不同，所以只提供带 _SAMP / _POP 的名字。 */
    private ExprDouble<Double> statistic(String name) {
        return ExprDouble.of(new Aggregate<>(SqlType.of(Double.class), name, false, List.of(cast(CastType.doublePrecision()))));
    }
}

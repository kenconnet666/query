package org.dpdns.zerodep.query.annotation;

import org.dpdns.zerodep.query.expr.Expr;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 条件对象（{@code where(filter)}）里一个字段怎样变成条件。没有这个注解的字段与同名列比较相等（集合用 IN）。
 * <pre>{@code
 * public record DeliverySearch(
 *     List<String> material,                                         // MATNR IN (...)
 *     @Where(value = "createdOn", op = Where.Op.GE) LocalDate from,  // ERDAT >= ?
 *     @Where(value = "createdOn", op = Where.Op.LE) LocalDate to,    // ERDAT <= ?
 *     @Where(value = {"mara.partNumber", "text.text"}, op = Where.Op.CONTAINS) String keyword,  // 任一列包含
 *     @Ignore String sort) { }
 * }</pre>
 * 值为 null、空白字符串或空集合的字段不产生条件。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Where {
    /**
     * 比较的列，默认是字段名。可以经过多对一关系，如 {@code "mara.partNumber"}；写多个时任一满足即可（OR）。
     */
    String[] value() default {};

    /** 比较方式，默认相等。 */
    Op op() default Op.EQ;

    /**
     * 自定义的比较，优先于 {@link #op}：实现 {@link Operator} 且有无参构造方法的类，每个类只创建一次。
     * 例如按月筛选 {@code @Where(value = "createdOn", using = InMonth.class) YearMonth month}。
     */
    Class<? extends Operator> using() default Operator.class;

    /** 自定义的比较：字段值为 null、空白字符串或空集合时不调用，直接省略条件。 */
    interface Operator {
        /** 由列和字段值写下条件，写法与 where 块里相同，例如 {@code ((ExprDate<?>) column).range(start, end)}；不写表示不加条件。 */
        void apply(Expr<?> column, Object value);
    }

    /** 字段值与列的比较方式。值是集合时 EQ 为 IN，NE 为 NOT IN。 */
    enum Op {
        /** 等于；集合值为 IN。 */
        EQ,
        /** 不等于；集合值为 NOT IN。 */
        NE,
        /** 大于。 */
        GT,
        /** 大于等于。 */
        GE,
        /** 小于。 */
        LT,
        /** 小于等于。 */
        LE,
        /** 文本包含，值里的 % 和 _ 按普通字符匹配。 */
        CONTAINS,
        /** 文本以值开头。 */
        STARTS_WITH,
        /** 文本以值结尾。 */
        ENDS_WITH
    }
}

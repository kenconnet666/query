package org.dpdns.zerodep.query.annotation;

import org.dpdns.zerodep.query.type.Converter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把表接口上的列方法映射到数据库列。不加该注解时，由数据库的命名策略根据方法名推导列名。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Column {
    /** 列名，原样使用；为空表示由方法名推导。 */
    String value() default "";

    /** 列的 Java 类型与库中存储值之间的转换器；{@code Converter.class} 表示不转换。 */
    @SuppressWarnings("rawtypes")
    Class<? extends Converter> converter() default Converter.class;

    // 以下只用于结构变更（db.schema()），查询和写入不读它们。

    /** 文本或字节列的长度；0 表示默认（文本 255）。 */
    int length() default 0;

    /** 小数的总位数；0 表示默认（DECIMAL(18,2)）。 */
    int precision() default 0;

    /** 小数的小数位，precision 不为 0 时才有意义。 */
    int scale() default 0;

    /** 是否可以为 NULL；主键列总是 NOT NULL。 */
    boolean nullable() default true;

    /** 默认值的 SQL 文本，例如 {@code "0"}、{@code "'open'"}、{@code "CURRENT_TIMESTAMP"}；为空表示没有默认值。 */
    String defaultValue() default "";

    /** 列的注释。 */
    String comment() default "";

    /** 改名前的列名：库里没有新列名、但有这个旧名时，计划写成 RENAME COLUMN 而不是加列。 */
    String previousName() default "";
}

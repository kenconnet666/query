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
}

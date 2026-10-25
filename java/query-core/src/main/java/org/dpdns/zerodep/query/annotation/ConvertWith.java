package org.dpdns.zerodep.query.annotation;

import org.dpdns.zerodep.query.type.Converter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组合类型注解用的元注解，让挂载的类型在列上只写一个词：
 * <pre>{@code
 * @Retention(RUNTIME) @Target(METHOD) @ConvertWith(SapDateConverter.class)
 * public @interface SapDate { }
 *
 * @Column("ERDAT") @SapDate ColText<LocalDate> createdOn();   // 库里是 'yyyyMMdd' 文本，所以是文本列
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ConvertWith {
    /** 转换器类，作用与 {@link Column#converter()} 相同。 */
    @SuppressWarnings("rawtypes")
    Class<? extends Converter> value();
}

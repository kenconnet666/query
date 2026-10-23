package org.dpdns.zerodep.query.annotation;

import org.dpdns.zerodep.query.type.Converter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation for composed type annotations, so a mounted type reads as one word on the column:
 * <pre>{@code
 * @Retention(RUNTIME) @Target(METHOD) @ConvertWith(SapDateConverter.class)
 * public @interface SapDate { }
 *
 * @Column("ERDAT") @SapDate Col<LocalDate> createdOn();
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ConvertWith {
    @SuppressWarnings("rawtypes")
    Class<? extends Converter> value();
}

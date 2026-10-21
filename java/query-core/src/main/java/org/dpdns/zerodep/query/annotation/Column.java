package org.dpdns.zerodep.query.annotation;

import org.dpdns.zerodep.query.type.Converter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Maps a field to a column. Without it the database's naming strategy derives the column from the property name. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Column {
    /** Column name, used exactly as written; empty means "derive from the property name". */
    String value() default "";

    /** Converter between the field's Java type and the stored value; {@code Converter.class} means none. */
    @SuppressWarnings("rawtypes")
    Class<? extends Converter> converter() default Converter.class;
}

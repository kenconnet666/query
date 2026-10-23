package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a table interface, one that extends {@code Entity}. Results, CTE rows and constant rows are plain classes without it. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {
    /** Table name, used exactly as written. */
    String value();

    /** Schema name; empty means the connection's current schema. */
    String schema() default "";
}

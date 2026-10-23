package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The column reads without surrounding spaces: {@code TRIM(column)}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ReadAs("TRIM({0})")
public @interface Trimmed { }

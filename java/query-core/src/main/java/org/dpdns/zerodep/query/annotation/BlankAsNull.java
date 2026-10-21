package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The column reads an empty string as NULL, as SAP tables store "no value": {@code NULLIF(column, '')}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@ReadAs("NULLIF({0}, '')")
public @interface BlankAsNull { }

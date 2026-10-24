package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 把列里的空字符串读成 NULL。SAP 表用空字符串表示"无值"，读取时按 {@code NULLIF(column, '')} 处理。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ReadAs("NULLIF({0}, '')")
public @interface BlankAsNull { }

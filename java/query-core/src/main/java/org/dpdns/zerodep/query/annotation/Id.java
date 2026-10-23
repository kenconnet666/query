package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记主键列，写在表接口的列方法上；多列主键在每一列上都标。{@code update(...).byId(row)}、
 * {@code deleteFrom(...).byId(row)} 按它们定位行，插入或更新的 {@code onConflict()} 不写列时也用它们。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Id { }

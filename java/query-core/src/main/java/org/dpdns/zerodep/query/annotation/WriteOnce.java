package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记只在插入时写的列，例如创建时间、创建人：插入时照常写；{@code byId} 更新和插入或更新的更新部分不写它，
 * 即使行里有同名属性。确实要改时用 {@code update(...).set(...)} 显式写。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WriteOnce { }

package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记只读的列，例如由数据库默认值、触发器或计算列维护的列：插入、{@code byId} 更新和插入或更新都不写它，
 * 即使行里有同名属性；在 {@code update(...).set(...)} 里显式写它会报错。查询照常读取。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReadOnly { }

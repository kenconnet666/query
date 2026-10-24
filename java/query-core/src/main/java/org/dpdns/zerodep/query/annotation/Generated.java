package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记由数据库生成值的列（HANA、PostgreSQL 的 IDENTITY，MySQL 的 AUTO_INCREMENT），一张表至多一列。
 * 插入时不写这一列，即使行里有同名属性；{@code db.insertKeys(insert)} 取回每行生成的值。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Generated { }

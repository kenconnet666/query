package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记乐观锁的版本列（{@code Col<Integer>} 或 {@code Col<Long>}），一张表至多一列。
 * {@code update(...).byId(row)} 写成 {@code SET version = version + 1 ... WHERE id = ? AND version = ?}，
 * 没有改到行（别人已经改过或删掉了）时抛出 {@code UnexpectedRowCountException}；{@code deleteFrom(...).byId(row)} 同样检查版本。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Version { }

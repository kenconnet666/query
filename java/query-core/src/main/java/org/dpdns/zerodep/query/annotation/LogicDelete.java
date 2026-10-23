package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记逻辑删除列，一张表至多一列。这张表的每次出现（查询、子查询、连接、UPDATE、DELETE）都自动加上"未删除"的条件，
 * 与全局过滤器一样可以用 {@code unfiltered()} 关闭；{@code deleteFrom(...)} 改写成把这一列设为"已删除"的 UPDATE，
 * {@code .physically()} 才真正删除。
 *
 * <p>列是 {@code Boolean}、整数或文本时，active 和 deleted 按列的类型解析，例如 {@code @LogicDelete(active = "N", deleted = "Y")}；
 * 列是 {@code LocalDateTime} 时（记录删除时间），未删除是 {@code IS NULL}，删除写成 {@code CURRENT_TIMESTAMP}，两个属性不用写。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LogicDelete {
    /** 未删除时的值。 */
    String active() default "0";

    /** 删除后的值。 */
    String deleted() default "1";
}

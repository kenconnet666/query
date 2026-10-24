package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一对多关系的简写，标在返回 {@code Many<目标表>} 的抽象方法上，按列方法名配对做等值连接：
 * <pre>{@code
 * @OneToMany(self = "delivery", target = "delivery") Many<Lips> items();
 * }</pre>
 * 等价于 {@code default Many<Lips> items() { return many(Lips.class, i -> i.delivery().eq(delivery())); }}；
 * 条件更复杂时直接写 default 方法。self 与 target 一一对应，可以有多列。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OneToMany {
    /** 本表参与连接的列方法名。 */
    String[] self();

    /** 目标表参与连接的列方法名，与 self 一一对应。 */
    String[] target();
}

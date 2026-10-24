package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多对一或一对一关系的简写，标在返回目标表接口的抽象方法上，按列方法名配对做等值连接：
 * <pre>{@code
 * @ManyToOne(self = "customerNo", target = "customerNo") Kna1 customer();
 * }</pre>
 * 等价于 {@code default Kna1 customer() { return one(Kna1.class, k -> k.customerNo().eq(customerNo())); }}；
 * 条件更复杂时直接写 default 方法。self 与 target 一一对应，可以有多列。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ManyToOne {
    /** 本表参与连接的列方法名。 */
    String[] self();

    /** 目标表参与连接的列方法名，与 self 一一对应。 */
    String[] target();
}

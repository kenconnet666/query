package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记表接口，即继承 {@code Entity} 的接口。结果类、CTE 行和常量行是不带该注解的普通类。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {
    /** 表名，原样使用。 */
    String value();

    /** schema 名；为空表示使用连接当前的 schema。 */
    String schema() default "";
}

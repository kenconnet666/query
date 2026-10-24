package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 表上的外键，写在表接口上，可以写多个：{@code @ForeignKey(name = "FK_TASK_INST", columns = "instanceId", references = Instance.class)}。
 * columns 是本表的列方法名，referencedColumns 是被引用表的列方法名，不写时用被引用表的 {@code @Id}。
 * 只用于结构变更；建表时外键最后建，所以被引用的表不必排在前面。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ForeignKey.List.class)
public @interface ForeignKey {
    /** 约束名。 */
    String name();

    /** 本表的列方法名。 */
    String[] columns();

    /** 被引用的表接口。 */
    Class<?> references();

    /** 被引用表的列方法名；为空时取它的 {@code @Id}。 */
    String[] referencedColumns() default {};

    /** 同一个表接口上的多个 {@link ForeignKey}。 */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        /** 各个外键。 */
        ForeignKey[] value();
    }
}

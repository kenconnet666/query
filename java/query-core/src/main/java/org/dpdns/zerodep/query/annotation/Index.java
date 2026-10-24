package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 表上的索引，写在表接口上，可以写多个：{@code @Index(name = "IX_NOTE_TENANT", columns = {"tenant", "status"})}。
 * columns 是列方法名，按声明的顺序。只用于结构变更（{@code db.schema().plan(...)}），查询不读它。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Index.List.class)
public @interface Index {
    /** 索引名。 */
    String name();

    /** 列方法名，按索引里的顺序。 */
    String[] columns();

    /** 是否唯一索引。 */
    boolean unique() default false;

    /** 同一个表接口上的多个 {@link Index}。 */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        /** 各个索引。 */
        Index[] value();
    }
}

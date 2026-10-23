package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 列在所有语句中的读取方式：一个 SQL 模板，{@code {0}} 代表带限定的列，
 * 类似 Hibernate 的 {@code @ColumnTransformer(read = ...)}。之后该列在 SELECT、WHERE、JOIN、
 * GROUP BY 和 ORDER BY 里都按转换后的值读取；需要操作存储原值时，在同一列上再声明一个方法。
 * 可以加在列方法上，也可以加在另一个注解上，组合出可复用的注解：
 * <pre>{@code
 * @Retention(RUNTIME) @Target(METHOD) @ReadAs("NULLIF({0}, '')")
 * public @interface BlankAsNull { }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface ReadAs {
    /** SQL 模板，{@code {0}} 会被替换为带限定的列。 */
    String value();
}

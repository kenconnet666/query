package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How a column is read in every statement: an SQL template where {@code {0}} stands for the qualified
 * column, like Hibernate's {@code @ColumnTransformer(read = ...)}. The column then reads transformed
 * in SELECT, WHERE, JOIN, GROUP BY and ORDER BY alike; declare a second field on the same column to
 * work with the stored value. Put it on a field, or on an annotation to compose a reusable one:
 * <pre>{@code
 * @Retention(RUNTIME) @Target(FIELD) @ReadAs("NULLIF({0}, '')")
 * public @interface BlankAsNull { }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
public @interface ReadAs {
    String value();
}

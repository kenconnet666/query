package org.dpdns.zerodep.query.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a DTO field read by {@code selectAs(Dto.class)}: take the value from another entity's property.
 * Use Lombok's {@code @FieldNameConstants} for the property so a rename breaks compilation:
 * {@code @MapFrom(value = Lips.class, property = Lips.Fields.material)}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface MapFrom {
    Class<?> value();

    String property();
}

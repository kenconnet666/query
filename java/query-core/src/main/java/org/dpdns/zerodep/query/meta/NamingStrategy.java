package org.dpdns.zerodep.query.meta;

/** Derives a column name for a property without an explicit {@code @Column} name. */
@FunctionalInterface
public interface NamingStrategy {
    String column(String property);

    NamingStrategy AS_IS = property -> property;
    NamingStrategy UPPER_SNAKE = property -> snake(property).toUpperCase();
    NamingStrategy LOWER_SNAKE = property -> snake(property).toLowerCase();

    private static String snake(String property) {
        return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
    }
}

package org.dpdns.zerodep.query.meta;

/** 为没有显式 {@code @Column} 名称的属性推导列名。 */
@FunctionalInterface
public interface NamingStrategy {
    /** 返回属性名对应的列名。 */
    String column(String property);

    /** 列名与属性名完全相同。 */
    NamingStrategy AS_IS = property -> property;
    /** 驼峰转大写下划线，如 {@code partNumber} 变为 {@code PART_NUMBER}。 */
    NamingStrategy UPPER_SNAKE = property -> snake(property).toUpperCase();
    /** 驼峰转小写下划线，如 {@code partNumber} 变为 {@code part_number}。 */
    NamingStrategy LOWER_SNAKE = property -> snake(property).toLowerCase();

    /** 在小写字母或数字与大写字母之间插入下划线，不改变大小写。 */
    private static String snake(String property) {
        return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
    }
}

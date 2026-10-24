package org.dpdns.zerodep.query.annotation;

import org.dpdns.zerodep.query.dialect.Dialect;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 只在某个方言上加在 CREATE TABLE 末尾的选项，写在表接口上，可以每个方言写一个；最常见的是原生分区：
 * <pre>{@code
 * @TableOption(dialect = PostgresDialect.class, value = "PARTITION BY RANGE (\"DAY\")")
 * @TableOption(dialect = MysqlDialect.class, value = "PARTITION BY RANGE COLUMNS(DAY) (PARTITION p2026 VALUES LESS THAN ('2027-01-01'), PARTITION pmax VALUES LESS THAN (MAXVALUE))")
 * @TableOption(dialect = HanaDialect.class, value = "PARTITION BY RANGE (DAY) (PARTITION '2026-01-01' <= VALUES < '2027-01-01', PARTITION OTHERS)")
 * }</pre>
 * 文本原样写进语句，分区由数据库裁剪，查询不变。只用于结构变更（{@code db.schema()}），比较实库时不核对它。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(TableOption.List.class)
public @interface TableOption {
    /** 适用的方言（含子类）。 */
    Class<? extends Dialect> dialect();

    /** 加在建表语句右括号之后的 SQL 文本。 */
    String value();

    /** 同一个表接口上的多个 {@link TableOption}。 */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        /** 各个方言的选项。 */
        TableOption[] value();
    }
}

package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.TableOption;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** MySQL 专属能力的测试表：JSON 文档和带 FULLTEXT 索引的标题、正文。 */
final class MysqlTables {
    private MysqlTables() { }

    /** 文章：JSON 文档，FULLTEXT ft_article(TITLE, BODY)。 */
    @Table(schema = "QUERY_IT", value = "MY_ARTICLE")
    interface Article extends Entity {
        /** 编号。 */
        @Column("ID") Col<Integer> id();

        /** JSON 文档，按文本读写。 */
        @Column("DOC") Col<String> doc();

        /** 标题。 */
        @Column("TITLE") Col<String> title();

        /** 正文。 */
        @Column("BODY") Col<String> body();
    }

    /** 按日期范围分区的日志：分区键 DAY 必须在主键里。 */
    @Table(schema = "QUERY_IT", value = "PART_LOG")
    @TableOption(dialect = MysqlDialect.class, value = "PARTITION BY RANGE COLUMNS(`DAY`) (PARTITION p2025 VALUES LESS THAN ('2026-01-01'), PARTITION p2026 VALUES LESS THAN ('2027-01-01'), PARTITION pmax VALUES LESS THAN (MAXVALUE))")
    interface PartLog extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 日期，分区键。 */
        @Id @Column("DAY") Col<LocalDate> day();

        /** 金额。 */
        @Column(value = "AMOUNT", precision = 10, scale = 2) Col<BigDecimal> amount();
    }

    /** 分区日志的一行。 */
    record PartLogRow(Long id, LocalDate day, BigDecimal amount) { }
}

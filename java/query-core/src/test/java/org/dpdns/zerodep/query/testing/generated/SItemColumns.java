package org.dpdns.zerodep.query.testing.generated;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Generated;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.column.ColBigint;
import org.dpdns.zerodep.query.table.column.ColDate;
import org.dpdns.zerodep.query.table.column.ColDecimal;
import org.dpdns.zerodep.query.table.column.ColText;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * QUERY_IT.S_ITEM（items）的列，由 query:pull 从数据库生成；重新生成会覆盖这个文件，不要手改。
 * 在自己的表接口里继承它：{@code @Table(schema = "QUERY_IT", value = "S_ITEM") interface X extends SItemColumns { ... }}。
 */
public interface SItemColumns extends Entity {
    /** ID：BIGINT，主键，数据库生成 */
    @Id @Generated @Column("ID") ColBigint<Long> id();

    /** CODE：TEXT(20)，NOT NULL */
    @Column(value = "CODE", length = 20, nullable = false) ColText<String> code();

    /** CREATED_ON：DATE */
    @Column("CREATED_ON") ColDate<LocalDate> createdOn();

    /** OWNER_ID：BIGINT */
    @Column("OWNER_ID") ColBigint<Long> ownerId();

    /** PRICE：DECIMAL(13,3) */
    @Column(value = "PRICE", precision = 13, scale = 3) ColDecimal<BigDecimal> price();

    /** STATUS：TEXT(10)，NOT NULL */
    @Column(value = "STATUS", length = 10, nullable = false, defaultValue = "'open'") ColText<String> status();

    /** TITLE：TEXT(40)。title */
    @Column(value = "TITLE", length = 40, comment = "title") ColText<String> title();
}

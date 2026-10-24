package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.TableOption;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/** HANA 专属来源的测试表：计算视图、表函数和层级的组织表。 */
final class HanaTables {
    private HanaTables() { }

    /** 带输入参数的计算视图（只渲染，HXE 的租户没有 HDI，实库上不建）。 */
    @Table(schema = "QUERY_IT", value = "CV_SALES")
    interface SalesView extends Entity {
        /** 物料号。 */
        @Column("MATNR") Col<String> material();

        /** 金额。 */
        @Column("AMOUNT") Col<BigDecimal> amount();
    }

    /** 表函数：某个单位的交货行。 */
    @Table(schema = "QUERY_IT", value = "FN_LIPS")
    interface LipsByUnit extends Entity {
        /** 交货单号。 */
        @Column("VBELN") Col<String> delivery();

        /** 数量。 */
        @Column("LFIMG") Col<BigDecimal> quantity();
    }

    /** 组织：用上级编号连成树。 */
    @Table(schema = "QUERY_IT", value = "H_ORG")
    interface Org extends Entity {
        /** 编号。 */
        @Column("ID") Col<String> id();

        /** 上级编号，根为 NULL。 */
        @Column("PARENT_ID") Col<String> parent();

        /** 名称。 */
        @Column("NAME") Col<String> name();
    }

    /** 层级的一行：来源的列加上层级函数生成的列。 */
    record OrgNode(String nodeId, String parentId, String name, Integer hierarchyRank, Integer hierarchyLevel, Integer hierarchyParentRank,
                   Integer hierarchyDistance) { }

    /** 按日期范围分区的日志：分区键 DAY 必须在主键里。 */
    @Table(schema = "QUERY_IT", value = "PART_LOG")
    @TableOption(dialect = HanaDialect.class, value = "PARTITION BY RANGE (DAY) (PARTITION '2025-01-01' <= VALUES < '2026-01-01', PARTITION '2026-01-01' <= VALUES < '2027-01-01', PARTITION OTHERS)")
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

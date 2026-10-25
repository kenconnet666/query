package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.column.ColDecimal;
import org.dpdns.zerodep.query.table.column.ColText;

import java.math.BigDecimal;

/** HANA 专属来源的测试表：计算视图、表函数和层级的组织表。 */
final class HanaTables {
    private HanaTables() { }

    /** 带输入参数的计算视图（只渲染，HXE 的租户没有 HDI，实库上不建）。 */
    @Table(schema = "QUERY_IT", value = "CV_SALES")
    interface SalesView extends Entity {
        /** 物料号。 */
        @Column("MATNR") ColText<String> material();

        /** 金额。 */
        @Column("AMOUNT") ColDecimal<BigDecimal> amount();
    }

    /** 表函数：某个单位的交货行。 */
    @Table(schema = "QUERY_IT", value = "FN_LIPS")
    interface LipsByUnit extends Entity {
        /** 交货单号。 */
        @Column("VBELN") ColText<String> delivery();

        /** 数量。 */
        @Column("LFIMG") ColDecimal<BigDecimal> quantity();
    }

    /** 组织：用上级编号连成树。 */
    @Table(schema = "QUERY_IT", value = "H_ORG")
    interface Org extends Entity {
        /** 编号。 */
        @Column("ID") ColText<String> id();

        /** 上级编号，根为 NULL。 */
        @Column("PARENT_ID") ColText<String> parent();

        /** 名称。 */
        @Column("NAME") ColText<String> name();
    }

    /** 层级的一行：来源的列加上层级函数生成的列。 */
    record OrgNode(String nodeId, String parentId, String name, Integer hierarchyRank, Integer hierarchyLevel, Integer hierarchyParentRank,
                   Integer hierarchyDistance) { }
}

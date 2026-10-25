package org.dpdns.zerodep.query.it.dialect;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.TableOption;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.hana.HanaDialect;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Writes.Doc;
import org.dpdns.zerodep.query.testing.Writes.DocRow;
import org.dpdns.zerodep.query.testing.Writes.Note;
import org.dpdns.zerodep.query.testing.Writes.NoteRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.hana.Hana.*;
import static org.dpdns.zerodep.query.it.support.Db.HANA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HANA 专属的写法（{@code Hana} 门面）：日期函数、提示子句、分组集合、NULL 时为 NULL 的 GREATEST、FULL JOIN、中位数与百分位数、
 * 层级、表函数、全文检索与 JSON、分区表，以及联表更新一行匹配多行时报错。数据见 {@link SampleData}。
 */
@LiveTest(only = HANA)
@DisplayName("HANA 专属写法")
class HanaTest {
    @Parameter Live live;

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

    record PartLogRow(Long id, LocalDate day, BigDecimal amount) { }

    /**
     * 层级用的组织树 A → B → C、A → D，和返回某个单位的交货行的表函数，都手写；联表更新用 {@code testing.Writes} 的便签和文档表。
     */
    @BeforeParameterizedClassInvocation
    static void objects(Live live) {
        live.execute("-DROP TABLE QUERY_IT.H_ORG", "CREATE COLUMN TABLE QUERY_IT.H_ORG (ID NVARCHAR(10), PARENT_ID NVARCHAR(10), NAME NVARCHAR(20))",
            "INSERT INTO QUERY_IT.H_ORG VALUES ('A', NULL, 'root')", "INSERT INTO QUERY_IT.H_ORG VALUES ('B', 'A', 'b')",
            "INSERT INTO QUERY_IT.H_ORG VALUES ('C', 'B', 'c')", "INSERT INTO QUERY_IT.H_ORG VALUES ('D', 'A', 'd')",
            "-DROP FUNCTION QUERY_IT.FN_LIPS",
            "CREATE FUNCTION QUERY_IT.FN_LIPS (P_UNIT NVARCHAR(3)) RETURNS TABLE (VBELN NVARCHAR(255), LFIMG DECIMAL(13,3)) LANGUAGE SQLSCRIPT AS "
                + "BEGIN RETURN SELECT VBELN, LFIMG FROM QUERY_IT.LIPS WHERE VRKME = :P_UNIT; END");
        live.recreate(Note.class, Doc.class);
    }

    @Test
    void 日期函数() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        assertEquals(31, (int) live.db().one(from(Lips.class).limit(1).select(l -> daysBetween(lit(day), addMonths(lit(day), 1)))));
        assertEquals("20260105", live.db().one(from(Lips.class).limit(1).select(l -> toVarchar(addDays(lit(day), 4), "YYYYMMDD"))));
    }

    @Test
    void 带排序的STRING_AGG与提示子句() {
        List<String> rows = live.db().list(from(Lips.class)
            .with(hint("NO_CS_JOIN"))
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> row((m, d) -> m + "=" + d, l.material(), stringAgg(l.delivery(), ",", l.delivery().asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }

    @Test
    void 分组集合与CUBE() {
        List<String> sets = live.db().list(from(Lips.class)
            .groupBy(l -> groupingSets(keys(l.material()), keys()))       // 按物料，加上总计
            .orderBy(l -> orders(grouping(l.material()), l.material()))
            .select(l -> row((m, total) -> m + "=" + total.stripTrailingZeros().toPlainString(), l.material(), sum(l.quantity()))));
        assertEquals(List.of("M1=6", "M2=12", "null=18"), sets);
        List<Long> cells = live.db().list(from(Lips.class).groupBy(l -> cube(l.material(), l.unit())).select(l -> count()));
        assertEquals(6, cells.size(), "material x unit, material, unit and the grand total");
    }

    @Test
    void GREATEST与LEAST遇到NULL时返回NULL() {
        List<String> rows = live.db().list(from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> row((d, high, low) -> d + ":" + plain(high) + ":" + plain(low),
                l.delivery(), greatest(l.quantity(), val(new BigDecimal("4"))), least(l.quantity(), val(new BigDecimal("4"))))));
        assertEquals(List.of("D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"), rows);
        assertNull(live.db().one(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> greatest(l.quantity(), nullOf(BigDecimal.class)))),
            "PostgreSQL 跳过 NULL，HANA 和 MySQL 返回 NULL");
    }

    @Test
    void TO_INTEGER把文本转成整数() {
        assertEquals(List.of(1, 2, 3, 4, 5), live.db().list(from(Lips.class).orderBy(l -> l.delivery()).select(l -> toInteger(substring(l.delivery(), 2)))));
    }

    @Test
    void 联表更新一行匹配多行时整条语句报错且没有行被改() {
        live.db().execute(deleteFrom(Note.class).all());
        live.db().execute(deleteFrom(Doc.class).all());
        live.db().execute(insertInto(Note.class).values(new NoteRow("N1", "T1", "first", 1, "open"), new NoteRow("N2", "T1", null, 2, "open")));
        live.db().execute(insertInto(Doc.class).values(new DocRow("D1", "first", 1), new DocRow("D2", "first", 1)));
        // N1 的标题 first 对上两份文档；PostgreSQL、MySQL 会任取一行，HANA 的 MERGE 报 689
        assertThrows(QueryExecutionException.class, () -> live.db().execute(updateJoin(Note.class, Doc.class, (n, d) -> n.title().eq(d.title()))
            .set((n, d) -> n.status().to(d.id())).all()));
        assertEquals(List.of("open", "open"), live.db().list(from(Note.class).orderBy(n -> n.id()).select(n -> n.status())));
    }

    @Test
    void FULL_JOIN保留两侧没有匹配的行且有全局过滤条件时报错() {
        // 不带过滤器时：6 张交货行各配上物料（客户端 100 和 200 的 M1 都配上），再加上没有交货的 M3、M4。
        List<Map<String, Object>> rows = live.db().list(from(Lips.class).unfiltered()
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material()))
            .select((l, m) -> cols(l.delivery(), m.material())));
        assertEquals(12, rows.size());
        assertEquals(2, rows.stream().filter(r -> r.get("delivery") == null).count());
        assertThrows(IllegalStateException.class, () -> live.db().list(from(Lips.class)
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material())).select((l, m) -> l.delivery())), "a global filter has no side to go to");
    }

    @Test
    void 中位数与窗口式的百分位数() {
        assertEquals(List.of("M1:2.0:2", "M2:6.0:5"), live.db().list(from(Lips.class)
                .distinct()
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(),
                    percentileCont(0.5, l.quantity()).over(w -> w.partitionBy(l.material())).as("median"),
                    percentileDisc(0.5, l.quantity()).over(w -> w.partitionBy(l.material())).as("lower"))))
            .stream().map(r -> r.get("material") + ":" + r.get("median") + ":" + plain(r.get("lower"))).toList());
        assertEquals(List.of("M1:2.0:1", "M2:6.0:1"), live.db().list(from(Lips.class)
                .groupBy(l -> l.material())
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(), median(l.quantity()).as("median"), corr(l.quantity(), l.quantity()).as("self"))))
            .stream().map(r -> r.get("material") + ":" + r.get("median") + ":" + Math.round((Double) r.get("self"))).toList());
    }

    @Test
    void HANA没有INTERSECT_ALL() {
        Query<String> q = from(Lips.class).select(l -> l.material());
        assertThrows(UnsupportedOperationException.class, () -> live.db().list(q.combine("INTERSECT ALL", q)));
    }

    /** 组织表作为层级的来源：编号与上级编号改名为 nodeId、parentId。 */
    private static Query<Map<String, Object>> orgs() {
        return from(Org.class).select(o -> cols(o.id().as("nodeId"), o.parent().as("parentId"), o.name()));
    }

    @Test
    void 层级与它的后代和祖先() {
        assertEquals(List.of("A:1:0", "B:2:1", "C:3:2", "D:2:1"), live.db().list(from(hierarchy(OrgNode.class, orgs()))
                .orderBy(n -> n.col(OrgNode::hierarchyRank))
                .select(n -> cols(n.col(OrgNode::nodeId), n.col(OrgNode::hierarchyLevel), n.col(OrgNode::hierarchyParentRank))))
            .stream().map(r -> r.get("nodeId") + ":" + r.get("hierarchyLevel") + ":" + r.get("hierarchyParentRank")).toList());
        assertEquals(List.of("B:0", "C:1"), live.db().list(from(descendants(OrgNode.class, orgs(), List.of("B")))
                .orderBy(n -> n.col(OrgNode::nodeId)).select(n -> cols(n.col(OrgNode::nodeId), n.col(OrgNode::hierarchyDistance))))
            .stream().map(r -> r.get("nodeId") + ":" + r.get("hierarchyDistance")).toList());
        assertEquals(List.of("A:-2", "B:-1", "C:0"), live.db().list(from(ancestors(OrgNode.class, orgs(), List.of("C")))
                .orderBy(n -> n.col(OrgNode::nodeId)).select(n -> cols(n.col(OrgNode::nodeId), n.col(OrgNode::hierarchyDistance))))
            .stream().map(r -> r.get("nodeId") + ":" + r.get("hierarchyDistance")).toList());
    }

    @Test
    void 表函数按命名实参调用() {
        // 表函数不经过全局过滤器，所以客户端 200 的 D6 也在。
        assertEquals(List.of("D1", "D2", "D3", "D4", "D5", "D6"),
            live.db().list(fromFunction(LipsByUnit.class, Map.of("P_UNIT", "EA")).orderBy(f -> f.delivery()).select(f -> f.delivery())));
    }

    @Test
    void 全文检索与JSON取值() {
        assertEquals(List.of("D1", "D2", "D5"), live.db().list(from(Lips.class).where(l -> contains("M1", l.material())).orderBy(l -> l.delivery()).select(l -> l.delivery())));
        List<Map<String, Object>> fuzzy = live.db().list(from(Lips.class).where(l -> containsFuzzy(0.8, "D1", l.delivery())).select(l -> cols(l.delivery(), score().as("score"))));
        assertEquals("D1", fuzzy.get(0).get("delivery"));
        assertEquals(1.0, (Double) fuzzy.get(0).get("score"), 1e-9);
        assertEquals("x", live.db().one(from(Lips.class).limit(1).select(l -> jsonValue(lit("{\"a\":{\"b\":\"x\"}}"), "$.a.b"))));
    }

    @Test
    void 按TableOption建原生分区表且查询不变() {
        live.recreate(PartLog.class);
        live.db().execute(insertInto(PartLog.class).values(new PartLogRow(1L, LocalDate.of(2025, 6, 1), BigDecimal.ONE), new PartLogRow(2L, LocalDate.of(2026, 6, 1), BigDecimal.TEN)));
        assertEquals(List.of(2L), live.db().list(from(PartLog.class).where(p -> p.day().ge(LocalDate.of(2026, 1, 1))).select(p -> p.id())));
        assertEquals(3L, live.db().sql("SELECT COUNT(*) FROM SYS.TABLE_PARTITIONS WHERE SCHEMA_NAME = 'QUERY_IT' AND TABLE_NAME = 'PART_LOG'").one(Long.class),
            "partitions of PART_LOG");
    }

    /** 小数去掉末尾的零。 */
    private static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }
}

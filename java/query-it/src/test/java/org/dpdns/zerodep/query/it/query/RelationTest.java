package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.deleteFrom;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.row;
import static org.dpdns.zerodep.query.Sql.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 导航关系与对象树：多对一按需 LEFT JOIN、一对多的 EXISTS 和相关子查询、子列表与对象树的批量加载、分组连接，以及写语句的条件经过关系。
 * 关系声明在 {@code testing.Erp}：物料的 {@code deliveries()}（一对多）和 {@code text()}（语言 1 的描述），交货行的 {@code mara()}、
 * {@code salesData()}。数据见 {@link SampleData}：M1 有 D1、D2、D5 和描述 Pump，M2 有 D3、D4 但没有语言 1 的描述，M3、M4 没有交货。
 */
@LiveTest
@DisplayName("导航关系与对象树")
class RelationTest {
    @Parameter Live live;

    /** 物料汇总的一行：物料、交货行数、数量合计、最新的交货、语言 1 的描述。 */
    record MaterialSummary(String material, Long lines, BigDecimal total, String latest, String text) { }

    @Test
    void 导航关系按需连接并给出正确结果() {
        List<MaterialSummary> rows = live.db().list(from(Mara.class)
            .orderBy(m -> m.material())
            .select(m -> row(MaterialSummary::new, m.material(), m.deliveries().count(), m.deliveries().sum(l -> l.quantity()),
                m.deliveries().first(l -> l.createdOn().desc()).delivery(), m.text().text())));
        assertEquals(List.of("M1:3:6:D5:Pump", "M2:2:12:D3:null", "M3:0:null:null:null", "M4:0:null:null:null"), rows.stream()
            .map(r -> r.material() + ":" + r.lines() + ":" + (r.total() == null ? null : plain(r.total())) + ":" + r.latest() + ":" + r.text())
            .toList());
        assertEquals(List.of("D1", "D2", "D3", "D4", "D5"), live.db().list(from(Lips.class)
            .where(l -> l.mara().materialType().eq("ZMAT")).orderBy(l -> l.delivery()).select(l -> l.delivery())));
        assertEquals(List.of("D5"), live.db().list(from(Lips.class).where(l -> l.salesData().none()).select(l -> l.delivery())), "S9 没有销售订单数据");
        assertEquals(List.of("M1"), live.db().list(from(Mara.class)
            .where(m -> {
                m.deliveries().any(l -> l.quantity().gt(new BigDecimal("2.5")));
                m.deliveries().count().ge(3L);
            })
            .select(m -> m.material())));
    }

    /** 子列表：物料和它的交货单号。 */
    record MaterialDeliveries(String material, List<String> deliveries) { }

    /** selectAs 的对象树：语言 1 的描述（多对一）和交货行（一对多）。 */
    record MaterialTree(String material, TreeText text, List<TreeLine> deliveries) { }

    /** 对象树里的描述。 */
    record TreeText(String text) { }

    /** 对象树里的交货行。 */
    record TreeLine(String delivery, BigDecimal quantity) { }

    @Test
    void 子列表与对象树用一条批量查询加载() {
        // 父行一条语句，全部子行再一条（按父行的键批量取），不是每个父行一条。
        var events = live.db().capture(() -> assertEquals(
            List.of("M1:[D1, D2, D5]", "M2:[D3, D4]", "M3:[]", "M4:[]"),
            live.db().list(from(Mara.class).orderBy(m -> m.material()).select(m -> row(MaterialDeliveries::new, m.material(),
                    m.deliveries().orderBy(l -> l.delivery()).list(l -> l.delivery()))))
                .stream().map(r -> r.material() + ":" + r.deliveries()).toList()));
        assertEquals(2, events.size(), "one statement for the parents and one for all children");
        List<MaterialTree> trees = live.db().list(from(Mara.class).orderBy(m -> m.material()).selectAs(MaterialTree.class));
        assertEquals(List.of("M1:Pump:D1/3,D2/2,D5/1", "M2:null:D3/5,D4/7", "M3:null:", "M4:null:"), trees.stream()
            .map(t -> t.material() + ":" + t.text().text() + ":" + t.deliveries().stream()
                .map(l -> l.delivery() + "/" + plain(l.quantity())).sorted().collect(Collectors.joining(",")))
            .toList());
    }

    @Test
    void 子列表可以限定每个父行取最新的几行() {
        assertEquals(List.of("M1:[D5, D2]", "M2:[D3, D4]", "M3:[]", "M4:[]"),
            live.db().list(from(Mara.class).orderBy(m -> m.material()).select(m -> row(MaterialDeliveries::new, m.material(),
                    m.deliveries().orderBy(l -> l.createdOn().desc()).limit(2).list(l -> l.delivery()))))
                .stream().map(r -> r.material() + ":" + r.deliveries()).toList());
    }

    @Test
    void 分组连接与相关子查询的结果相同() {
        // groupJoin 把同一个一对多关系上的存在判断、行数、合计和带筛选的行数合成一个按连接键分组的派生表。
        List<String> rows = live.db().list(from(Mara.class)
                .groupJoin(m -> m.deliveries())
                .where(m -> m.deliveries().any(l -> l.unit().eq("EA")))
                .orderBy(m -> m.material())
                .select(m -> cols(m.material(), m.deliveries().count().as("lines"), m.deliveries().sum(l -> l.quantity()).as("total"),
                    m.deliveries().where(l -> l.createdOn().ge(LocalDate.of(2026, 1, 1))).count().as("recent"))))
            .stream().map(r -> r.get("material") + ":" + r.get("lines") + ":" + plain(r.get("total")) + ":" + r.get("recent")).toList();
        assertEquals(List.of("M1:3:6:3", "M2:2:12:1"), rows);
        assertEquals(List.of("M3", "M4"), live.db().list(from(Mara.class).groupJoin(m -> m.deliveries())
            .where(m -> m.deliveries().none()).orderBy(m -> m.material()).select(m -> m.material())));
    }

    @Test
    void 一对多上的去重计数与按顺序拼接() {
        List<String> rows = live.db().list(from(Mara.class)
                .orderBy(m -> m.material())
                .select(m -> cols(m.material(),
                    m.deliveries().countDistinct(l -> l.salesOrder()).as("orders"),
                    m.deliveries().orderBy(l -> l.delivery()).stringAgg(l -> l.delivery(), ",").as("deliveries"))))
            .stream().map(r -> r.get("material") + ":" + r.get("orders") + ":" + r.get("deliveries")).toList();
        assertEquals(List.of("M1:3:D1,D2,D5", "M2:1:D3,D4", "M3:0:null", "M4:0:null"), rows, "M1 的销售订单 S1、S2、S9；M2 的都是 S1");
    }

    @Test
    void 更新和删除的条件经过导航关系时只改到关联得上的行() {
        // 在事务里执行再回滚，不改共享的样例数据；PostgreSQL 的 UPDATE 即使值不变也写出新的行版本，会打乱别的测试依赖的扫描顺序。
        class Rollback extends RuntimeException { }
        assertThrows(Rollback.class, () -> live.db().transaction(() -> {
            // 影响的行数是匹配的行数：没有语言 1 描述的是 M2 的 D3、D4；制造商 MK1 的是 M1 的三行。
            assertEquals(2, live.db().execute(update(Lips.class).set(l -> l.unit().to("EA")).where(l -> l.text().text().isNull())));
            assertEquals(3, live.db().execute(update(Lips.class).set(l -> l.unit().to("EA")).where(l -> l.mara().manufacturer().eq("MK1"))));
            assertEquals(0, live.db().execute(deleteFrom(Lips.class).where(l -> l.mara().materialType().eq("NONE"))));
            throw new Rollback();
        }));
    }

    /** 小数去掉末尾的零。 */
    private static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }
}

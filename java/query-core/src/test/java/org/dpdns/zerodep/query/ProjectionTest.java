package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.DeliveryVO;
import org.dpdns.zerodep.query.testing.Erp.DeliveryView;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.MaterialScope;
import org.dpdns.zerodep.query.testing.Erp.PartTotal;
import org.dpdns.zerodep.query.testing.Scenarios;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.dpdns.zerodep.query.type.CastType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("投影与映射")
class ProjectionTest {

    @Test
    void 函数式投影按记录的列重放构造record() {
        Query<Delivery> q = Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null);
        assertEquals(List.of("partNumber", "name", "quantity"), q.labels());
        assertEquals(new Delivery("P1", "Pump", new BigDecimal("5.000")), q.mapRow(new Object[] {"P1", "Pump", new BigDecimal("5.000")}));
    }

    @Test
    void 嵌套结果和Java端计算在同一个lambda里() {
        PartTotal t = Scenarios.partTotals().mapRow(new Object[] {new BigDecimal("25000"), "P1"});
        assertEquals("P1", t.partNumber());
        assertEquals(2.5, t.amount().wan());
    }

    @Test
    void 按行值有条件地读列会被拒绝() {
        Query<String> q = from(Lips.class).select((r, l) -> r.get(l, Lips::getUnit).isEmpty()
            ? r.get(l, Lips::getDelivery) : r.get(l, Lips::getMaterial));
        var e = assertThrows(IllegalStateException.class, () -> q.mapRow(new Object[] {"EA", "D1"}));
        assertTrue(e.getMessage().contains("unconditionally"), e.getMessage());
    }

    @Test
    void 按行值在两个函数表达式之间选择也会被拒绝() {
        Query<String> q = from(Lips.class).select((r, l) -> r.get(l, Lips::getUnit).isEmpty()
            ? r.get(l.col(Lips::getDelivery).coalesce("-")) : r.get(l.col(Lips::getMaterial).coalesce("-")));
        var e = assertThrows(IllegalStateException.class, () -> q.mapRow(new Object[] {"EA", "D1"}));
        assertTrue(e.getMessage().contains("unconditionally"), e.getMessage());
    }

    @Test
    void 每行读取相同的组合表达式时正常重放() {
        Query<String> q = from(Lips.class).select((r, l) -> r.get(l.col(Lips::getDelivery).coalesce("-")) + "/"
            + r.get(sum(l.col(Lips::getQuantity)).cast(CastType.decimal(15, 3))) + "/"
            + r.get(caseWhen(l.col(Lips::getUnit).eq("EA"), "each").otherwise("other")));
        assertEquals("D1/3.000/each", q.mapRow(new Object[] {"D1", new BigDecimal("3.000"), "each"}));
    }

    @Test
    void 自动投影按同名属性和MapFrom取列并跳过Ignore字段() {
        Query<LineVO> q = Scenarios.lines();
        assertEquals(List.of("delivery", "materialNo", "createdOn", "text"), q.labels());
        LineVO vo = q.mapRow(new Object[] {"D1", "M1", LocalDate.of(2026, 1, 15), "Pump"});
        assertEquals("M1", vo.getMaterialNo());
        assertEquals(LocalDate.of(2026, 1, 15), vo.getCreatedOn());
    }

    @Test
    void 自动投影找不到或找到多个来源时立刻报错() {
        var missing = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).selectAs(DeliveryVO.class));
        assertTrue(missing.getMessage().contains("DeliveryVO.partNumber has no same-named property"), missing.getMessage());
        var ambiguous = assertThrows(IllegalArgumentException.class, () -> from(Lips.class)
            .join(Mara.class, (l, ma) -> ma.col(Mara::getMaterial).eq(l, Lips::getMaterial)).selectAs(MaterialScope.class));
        assertTrue(ambiguous.getMessage().contains("matches 2 sources"), ambiguous.getMessage());
    }

    @Test
    void 命名投影未列出的属性按同名列补齐并按形状的属性顺序输出() {
        Query<MaterialScope> q = from(Mara.class).selectAs(MaterialScope.class, ma -> keys(upper(ma.col(Mara::getPartNumber)).as(MaterialScope::partNumber)));
        assertEquals(List.of("client", "material", "partNumber"), q.labels());
        assertTrue(q.toSql(new TestDialect()).sql().startsWith("SELECT MARA.MANDT AS \"client\", MARA.MATNR AS \"material\", UPPER(MARA.MFRPN) AS \"partNumber\""));
        var missing = assertThrows(IllegalArgumentException.class, () -> from(Lips.class)
            .selectAs(MaterialScope.class, l -> keys(l.col(Lips::getMaterial).as(MaterialScope::material))));
        assertTrue(missing.getMessage().contains("MaterialScope.partNumber has no same-named property"), missing.getMessage());
        var unknown = assertThrows(IllegalArgumentException.class, () -> from(Mara.class)
            .selectAs(MaterialScope.class, ma -> keys(ma.col(Mara::getManufacturer))));
        assertTrue(unknown.getMessage().contains("has no property 'manufacturer'"), unknown.getMessage());
    }

    @Test
    void 同一个查询可以映射成不同的DTO() {
        Query<Delivery> q = from(Lips.class)
            .groupBy(l -> keys(l.col(Lips::getMaterial)))
            .select((r, l) -> new Delivery(
                r.get(l.col(Lips::getMaterial).as("partNumber")),
                r.get(max(l.col(Lips::getUnit)).as("name")),
                r.get(sum(l.col(Lips::getQuantity)).as(Delivery::quantity))));
        Object[] row = {"M1", "EA", new BigDecimal("6.000")};
        assertEquals(6.0, q.mapTo(DeliveryVO.class).mapRow(row).getQuantity());
        assertEquals(new BigDecimal("6.000"), q.mapTo(DeliveryView.class).mapRow(row).getQuantity());
        assertEquals("M1:EA", q.map(d -> d.partNumber() + ":" + d.name()).mapRow(row));
    }

    @Test
    void 单列查询直接返回值() {
        Query<String> q = from(Lips.class).selectValue(l -> l.col(Lips::getDelivery));
        assertEquals("D1", q.mapRow(new Object[] {"D1"}));
    }

    @Test
    void 只有命名投影能当作CTE() {
        Query<String> functional = from(Lips.class).select((r, l) -> r.get(l, Lips::getDelivery));
        var e = assertThrows(IllegalStateException.class, () -> cte("x", functional));
        assertTrue(e.getMessage().contains("selectAs"), e.getMessage());
    }

    @Test
    void UNION的各段不能自带排序或分页() {
        Query<String> limited = from(Lips.class).limit(1).selectValue(l -> l.col(Lips::getDelivery));
        Query<String> plain = from(Lips.class).selectValue(l -> l.col(Lips::getDelivery));
        assertThrows(IllegalStateException.class, () -> plain.unionAll(limited));
        assertThrows(IllegalStateException.class, () -> limited.unionAll(plain));
    }
}

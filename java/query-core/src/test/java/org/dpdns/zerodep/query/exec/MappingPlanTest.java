package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.DeliveryVO;
import org.dpdns.zerodep.query.testing.Erp.DeliveryView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("映射计划")
class MappingPlanTest {
    private static final List<String> LABELS = List.of("PART_NUMBER", "Name", "quantity", "EXTRA");
    private static final Object[] ROW = {"P1", "Pump", new BigDecimal("5.000"), "ignored"};

    @Test
    void 标签忽略大小写和下划线并忽略多余的列() {
        assertEquals(new Delivery("P1", "Pump", new BigDecimal("5.000")), MappingPlan.of(Delivery.class, LABELS).map(ROW));
    }

    @Test
    void Lombok链式setter的bean并宽松转换数值() {
        DeliveryVO vo = MappingPlan.of(DeliveryVO.class, LABELS).map(ROW);
        assertEquals("P1", vo.getPartNumber());
        assertEquals(5.0, vo.getQuantity());
    }

    @Test
    void Builder构造的不可变类() {
        DeliveryView view = MappingPlan.of(DeliveryView.class, LABELS).map(ROW);
        assertEquals(new BigDecimal("5.000"), view.getQuantity());
    }

    @Test
    void 单列映射为标量() {
        assertEquals(42L, MappingPlan.of(Long.class, List.of("n")).map(new Object[] {42}));
    }

    @Test
    void 目标属性缺少对应列时报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> MappingPlan.of(Delivery.class, List.of("partNumber", "name")));
        assertTrue(e.getMessage().contains("Delivery.quantity has no column"), e.getMessage());
    }

    @Test
    void 基本类型遇到NULL时指出属性() {
        var e = assertThrows(IllegalStateException.class, () -> MappingPlan.of(Primitive.class, List.of("count")).map(new Object[] {null}));
        assertTrue(e.getMessage().contains("count is NULL"), e.getMessage());
    }

    @Test
    void 映射计划按目标类和标签缓存() {
        assertSame(MappingPlan.of(Delivery.class, LABELS), MappingPlan.of(Delivery.class, LABELS));
    }

    record Primitive(int count) { }
}

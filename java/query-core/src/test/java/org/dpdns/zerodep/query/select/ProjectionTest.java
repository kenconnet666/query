package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.DeliveryVO;
import org.dpdns.zerodep.query.testing.Erp.DeliveryView;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.MaterialScope;
import org.dpdns.zerodep.query.testing.Erp.PartTotal;
import org.dpdns.zerodep.query.testing.PrivateTables;
import org.dpdns.zerodep.query.testing.Scenarios;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("投影与映射")
class ProjectionTest {
    private static final TestDialect DIALECT = new TestDialect();

    @Test
    void 按位置投影调用构造器() {
        Query<Delivery> q = Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null);
        assertEquals(List.of("partNumber", "name", "quantity"), q.labels());
        assertEquals(new Delivery("P1", "Pump", new BigDecimal("5.000")), q.mapRow(new Object[] {"P1", "Pump", new BigDecimal("5.000")}));
    }

    @Test
    void row的第一个参数也可以是做Java端计算的lambda() {
        PartTotal t = Scenarios.partTotals().mapRow(new Object[] {new BigDecimal("25000"), "P1"});
        assertEquals("P1", t.partNumber());
        assertEquals(2.5, t.amount().wan());
    }

    @Test
    void row可以嵌套row和cols() {
        record Part(String material, String unit) { }
        record Line(String delivery, Part part, Map<String, Object> extra) { }
        Query<Line> q = from(Lips.class).select(l -> row(Line::new,
            l.delivery(), row(Part::new, l.material(), l.unit()), cols(l.quantity(), l.createdOn())));
        assertEquals(List.of("delivery", "material", "unit", "quantity", "createdOn"), q.labels());
        Line line = q.mapRow(new Object[] {"D1", "M1", "EA", BigDecimal.ONE, LocalDate.of(2026, 1, 15)});
        assertEquals(new Part("M1", "EA"), line.part());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("quantity", BigDecimal.ONE);
        extra.put("createdOn", LocalDate.of(2026, 1, 15));
        assertEquals(extra, line.extra());
    }

    @Test
    void cols的列名取列方法名且函数和聚合沿用所包的列名() {
        Query<Map<String, Object>> q = from(Lips.class).groupBy(l -> l.material())
            .select(l -> cols(l.material(), l.quantity().sum(), l.unit().trim().max(), count().as("lines")));
        assertEquals(List.of("material", "quantity", "unit", "lines"), q.labels());
        assertEquals(Map.of("material", "M1", "quantity", BigDecimal.TEN, "unit", "EA", "lines", 2L),
            q.mapRow(new Object[] {"M1", BigDecimal.TEN, "EA", 2L}));
    }

    @Test
    void cols里没有名字或重名的列立刻报错() {
        var unnamed = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).select(l -> cols(l.material(), count())));
        assertTrue(unnamed.getMessage().contains("Column 2 of cols(...) has no name"), unnamed.getMessage());
        var twice = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).select(l -> cols(l.quantity(), l.quantity().sum())));
        assertTrue(twice.getMessage().contains("Two columns are named 'quantity'"), twice.getMessage());
    }

    @Test
    void into按名映射并把列重排成类的属性顺序() {
        Query<MaterialScope> q = from(Mara.class)
            .select(m -> cols(m.partNumber().upper().as("partNumber"), m.material(), m.client()))
            .into(MaterialScope.class);
        assertEquals(List.of("client", "material", "partNumber"), q.labels());
        assertTrue(q.toSql(DIALECT).sql().startsWith("SELECT MARA.MANDT AS \"client\", MARA.MATNR AS \"material\", UPPER(MARA.MFRPN) AS \"partNumber\""));
        var missing = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).select(l -> cols(l.material())).into(MaterialScope.class));
        assertTrue(missing.getMessage().contains("MaterialScope.client has no column"), missing.getMessage());
    }

    @Test
    void 改名的列写as并跳过Ignore字段() {
        Query<LineVO> q = Scenarios.lines();
        assertEquals(List.of("delivery", "materialNo", "createdOn", "text"), q.labels());
        LineVO vo = q.mapRow(new Object[] {"D1", "M1", LocalDate.of(2026, 1, 15), "Pump"});
        assertEquals("M1", vo.getMaterialNo());
        assertEquals(LocalDate.of(2026, 1, 15), vo.getCreatedOn());
    }

    @Test
    void selectAs按同名列取值找不到或找到多张表时立刻报错() {
        record Part(String material, String partNumber) { }
        assertEquals(List.of("material", "partNumber"), from(Mara.class).selectAs(Part.class).labels());
        var missing = assertThrows(IllegalArgumentException.class, () -> from(Lips.class).selectAs(DeliveryVO.class));
        assertTrue(missing.getMessage().contains("DeliveryVO.partNumber has no same-named column"), missing.getMessage());
        var ambiguous = assertThrows(IllegalArgumentException.class, () -> from(Lips.class)
            .join(Mara.class, (l, ma) -> ma.material().eq(l.material())).selectAs(Part.class));
        assertTrue(ambiguous.getMessage().contains("matches columns of 2 tables"), ambiguous.getMessage());
    }

    @Test
    void 同一个查询可以映射成不同的类() {
        Query<Delivery> q = from(Lips.class)
            .groupBy(l -> l.material())
            .select(l -> cols(l.material().as("partNumber"), l.unit().max().as("name"), l.quantity().sum()))
            .into(Delivery.class);
        Object[] row = {"M1", "EA", new BigDecimal("6.000")};
        assertEquals(6.0, q.into(DeliveryVO.class).mapRow(row).getQuantity());
        Query<DeliveryView> view = q.into(DeliveryView.class);
        assertEquals(List.of("partNumber", "quantity", "name"), view.labels(), "columns the class lacks move to the end");
        assertEquals(new BigDecimal("6.000"), view.mapRow(new Object[] {"M1", new BigDecimal("6.000"), "EA"}).getQuantity());
        assertEquals("M1:EA", q.map(d -> d.partNumber() + ":" + d.name()).mapRow(row));
    }

    @Test
    void 单列查询直接返回值() {
        Query<String> q = from(Lips.class).select(l -> l.delivery());
        assertEquals(List.of("delivery"), q.labels());
        assertEquals("D1", q.mapRow(new Object[] {"D1"}));
    }

    @Test
    void 只有映射到类的查询能当作CTE() {
        Query<String> single = from(Lips.class).select(l -> l.delivery());
        var e = assertThrows(IllegalStateException.class, () -> cte("x", single));
        assertTrue(e.getMessage().contains("into(Row.class)"), e.getMessage());
    }

    @Test
    void UNION的各段不能自带排序或分页() {
        Query<String> limited = from(Lips.class).limit(1).select(l -> l.delivery());
        Query<String> plain = from(Lips.class).select(l -> l.delivery());
        assertThrows(IllegalStateException.class, () -> plain.unionAll(limited));
        assertThrows(IllegalStateException.class, () -> limited.unionAll(plain));
    }

    @Test
    void 按名的查询UNION时按列名对齐() {
        Query<Map<String, Object>> a = from(Mara.class).select(m -> cols(m.partNumber(), m.material()));
        Query<Map<String, Object>> b = from(Mara.class).select(m -> cols(m.material(), m.partNumber()));
        assertTrue(a.unionAll(b).toSql(DIALECT).sql().endsWith("SELECT MARA2.MFRPN, MARA2.MATNR\nFROM QUERY_IT.MARA MARA2"));
        Query<Map<String, Object>> other = from(Mara.class).select(m -> cols(m.material(), m.manufacturer()));
        var e = assertThrows(IllegalArgumentException.class, () -> a.unionAll(other));
        assertTrue(e.getMessage().contains("different columns"), e.getMessage());
    }

    @Test
    void 表接口的default方法可以是计算列且不公开的接口也能用() {
        String sql = from(Lips.class).select(l -> cols(l.columns(), l.quantity().times(lit(2)).as("doubled"))).toSql(DIALECT).sql();
        assertTrue(sql.startsWith("SELECT LIPS.MANDT AS \"client\", LIPS.ERDAT AS \"createdOn\", LIPS.VBELN AS \"delivery\""), sql);
        assertTrue(sql.contains("(LIPS.LFIMG * 2) AS \"doubled\""), sql);
        assertTrue(PrivateTables.render(DIALECT).startsWith("SELECT UPPER(HIDDEN.MATNR) AS \"value\""));
    }

    @org.dpdns.zerodep.query.annotation.Table("BAD")
    interface Bad extends Entity {
        /** 不是 Col 的抽象方法。 */
        String name();
    }

    @org.dpdns.zerodep.query.annotation.Table("BAD")
    record BadRecord(String id) { }

    @Test
    void 表接口的抽象方法必须是列且表不能声明成类() {
        var notColumn = assertThrows(IllegalArgumentException.class, () -> from(Bad.class));
        assertTrue(notColumn.getMessage().contains("Bad.name() is neither a column"), notColumn.getMessage());
        var record = assertThrows(IllegalArgumentException.class, () -> values(BadRecord.class, List.of(new BadRecord("1"))));
        assertTrue(record.getMessage().contains("declare a table as an interface"), record.getMessage());
    }
}

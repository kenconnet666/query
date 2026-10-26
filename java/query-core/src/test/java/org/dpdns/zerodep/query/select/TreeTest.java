package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("对象树与子列表")
class TreeTest {
    private static final TestDialect DIALECT = new TestDialect();

    public record Line(String delivery, BigDecimal quantity) { }

    public record MaterialLines(String material, List<Line> lines) { }

    public record Text(String text) { }

    /** selectAs 的对象树：text 取多对一关系，deliveries 取一对多关系。 */
    public record MaterialTree(String material, Text text, List<Line> deliveries) { }

    /** 记下每条子查询的 SQL，并用 answer 给出的原始行作答。 */
    private static final class ChildRunner implements Function<Query<Object[]>, List<Object[]>> {
        final List<Rendered> statements = new ArrayList<>();
        private final Function<Rendered, List<Object[]>> answer;

        ChildRunner(Function<Rendered, List<Object[]>> answer) { this.answer = answer; }

        @Override public List<Object[]> apply(Query<Object[]> q) {
            Rendered r = q.toSql(DIALECT);
            statements.add(r);
            return q.mapRows(answer.apply(r), this);
        }
    }

    /** 把一组键值存成 "k=v" 文字的转换器：列的值类型是 Map（只为测试） */
    public static final class KeyValue implements org.dpdns.zerodep.query.type.Converter<Map<String, Object>, String> {
        @Override public String toDatabase(Map<String, Object> value) { return value.entrySet().iterator().next().toString(); }
        @Override public Map<String, Object> fromDatabase(String value) { return Map.of(value.split("=")[0], value.split("=")[1]); }
    }

    @org.dpdns.zerodep.query.annotation.Table("TAGGED")
    interface Tagged extends org.dpdns.zerodep.query.table.Entity {
        @org.dpdns.zerodep.query.annotation.Column("CODE") org.dpdns.zerodep.query.table.column.ColText<String> code();
        @org.dpdns.zerodep.query.annotation.Column(value = "TAGS", converter = KeyValue.class)
        org.dpdns.zerodep.query.table.column.ColClob<Map<String, Object>> tags();
    }

    /** 值类型是 Map 的属性：同名的是一列，不是关系 */
    public record TaggedRow(String code, Map<String, Object> tags) { }

    @Test
    void selectAs里Map类型的属性同名的是一列时按列取_不当对象树() {
        Query<TaggedRow> q = from(Tagged.class).selectAs(TaggedRow.class);
        assertEquals("SELECT TAGGED.CODE AS \"code\", TAGGED.TAGS AS \"tags\"\nFROM TAGGED TAGGED", q.toSql(DIALECT).sql());
    }

    private static Query<MaterialLines> materialLines() {
        return from(Mara.class)
            .orderBy(m -> m.material())
            .select(m -> row(MaterialLines::new, m.material(), m.deliveries().orderBy(l -> l.delivery()).list(l -> row(Line::new, l.delivery(), l.quantity()))));
    }

    @Test
    void 父查询只多取连接键子行用一条批量查询按键分给各父行() {
        Query<MaterialLines> q = materialLines();
        assertEquals("""
            SELECT MARA.MATNR AS "material", MARA.MATNR AS "material_2", MARA.MANDT AS "client"
            FROM QUERY_IT.MARA MARA
            ORDER BY MARA.MATNR ASC""", q.toSql(DIALECT).sql());
        ChildRunner children = new ChildRunner(r -> List.of(
            new Object[] {"D1", new BigDecimal("3.000"), "M1", "100"},
            new Object[] {"D2", new BigDecimal("2.000"), "M1", "100"},
            new Object[] {"D3", new BigDecimal("5.000"), "M2", "100"}));
        List<MaterialLines> rows = q.mapRows(List.of(new Object[] {"M1", "M1", "100"}, new Object[] {"M2", "M2", "100"},
            new Object[] {"M3", "M3", "100"}), children);
        assertEquals(1, children.statements.size());
        Rendered child = children.statements.get(0);
        assertEquals("""
            SELECT LIPS.VBELN AS "delivery", LIPS.LFIMG AS "quantity", LIPS.MATNR AS "material", LIPS.MANDT AS "client"
            FROM QUERY_IT.LIPS LIPS
            WHERE ((LIPS.MATNR = ? AND LIPS.MANDT = ?) OR (LIPS.MATNR = ? AND LIPS.MANDT = ?) OR (LIPS.MATNR = ? AND LIPS.MANDT = ?))
            ORDER BY LIPS.VBELN ASC""", child.sql());
        assertEquals(List.of("M1", "100", "M2", "100", "M3", "100"), child.parameters());
        assertEquals(List.of(
            new MaterialLines("M1", List.of(new Line("D1", new BigDecimal("3.000")), new Line("D2", new BigDecimal("2.000")))),
            new MaterialLines("M2", List.of(new Line("D3", new BigDecimal("5.000")))),
            new MaterialLines("M3", List.of())), rows);
    }

    @Test
    void 每个父行限定子行数时批量查询按父键分区取前几行() {
        Query<MaterialLines> q = from(Mara.class).select(m -> row(MaterialLines::new, m.material(),
            m.deliveries().orderBy(l -> l.createdOn().desc()).limit(2).list(l -> row(Line::new, l.delivery(), l.quantity()))));
        ChildRunner children = new ChildRunner(r -> List.of());
        q.mapRows(List.<Object[]>of(new Object[] {"M1", "M1", "100"}), children);
        assertEquals("""
            SELECT Q."delivery" AS "delivery", Q."quantity" AS "quantity", Q."material" AS "material", Q."client" AS "client"
            FROM (
            SELECT LIPS.VBELN AS "delivery", LIPS.LFIMG AS "quantity", LIPS.MATNR AS "material", LIPS.MANDT AS "client", ROW_NUMBER() OVER (PARTITION BY LIPS.MATNR, LIPS.MANDT ORDER BY LIPS.ERDAT DESC) AS "_q5", LIPS.ERDAT AS "_o6"
            FROM QUERY_IT.LIPS LIPS
            WHERE LIPS.MATNR = ? AND LIPS.MANDT = ?
            ) Q
            WHERE Q."_q5" <= ?
            ORDER BY Q."_o6" DESC""", children.statements.get(0).sql());
        assertThrows(IllegalStateException.class, () -> from(Mara.class).select(m -> m.deliveries().limit(2).count()));
    }

    @Test
    void 单列键用IN且重复的键只查一次() {
        record Order(String delivery, List<String> orders) { }
        Query<Order> q = from(Lips.class).select(l -> row(Order::new, l.delivery(), l.salesData().list(v -> v.salesOrder())));
        ChildRunner children = new ChildRunner(r -> List.<Object[]>of(new Object[] {"S1", "S1"}));
        List<Order> rows = q.mapRows(List.of(new Object[] {"D1", "S1"}, new Object[] {"D2", "S1"}, new Object[] {"D3", null}), children);
        Rendered child = children.statements.get(0);
        assertTrue(child.sql().endsWith("WHERE VBKD.VBELN IN (?)"), child.sql());
        assertEquals(List.of(new Order("D1", List.of("S1")), new Order("D2", List.of("S1")), new Order("D3", List.of())), rows);
    }

    @Test
    void 父行很多时按批查询子行() {
        record Order(String delivery, List<String> orders) { }
        Query<Order> q = from(Lips.class).select(l -> row(Order::new, l.delivery(), l.salesData().list(v -> v.salesOrder())));
        List<Object[]> parents = new ArrayList<>();
        for (int i = 0; i < Children.BATCH * 2 + 1; i++) parents.add(new Object[] {"D" + i, "S" + i});
        ChildRunner children = new ChildRunner(r -> List.of());
        assertEquals(parents.size(), q.mapRows(parents, children).size());
        assertEquals(List.of(500, 500, 1), children.statements.stream().map(r -> r.parameters().size()).toList());
    }

    @Test
    void selectAs按同名关系映射嵌套对象和子列表() {
        Query<MaterialTree> q = from(Mara.class).orderBy(m -> m.material()).selectAs(MaterialTree.class);
        assertEquals("""
            SELECT MARA.MATNR AS "material", MAKT.MAKTX AS "text", MARA.MATNR AS "material_2", MARA.MANDT AS "client"
            FROM QUERY_IT.MARA MARA
            LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = MARA.MATNR AND MAKT.MANDT = MARA.MANDT AND MAKT.SPRAS = ?
            ORDER BY MARA.MATNR ASC""", q.toSql(DIALECT).sql());
        ChildRunner children = new ChildRunner(r -> List.<Object[]>of(new Object[] {"D1", BigDecimal.ONE, "M1", "100"}));
        assertEquals(List.of(new MaterialTree("M1", new Text("Pump"), List.of(new Line("D1", BigDecimal.ONE))),
            new MaterialTree("M2", new Text(null), List.of())),
            q.mapRows(List.of(new Object[] {"M1", "Pump", "M1", "100"}, new Object[] {"M2", null, "M2", "100"}), children));
        assertTrue(children.statements.get(0).sql().startsWith("SELECT LIPS.VBELN AS \"delivery\", LIPS.LFIMG AS \"quantity\", LIPS.MATNR"));
    }

    @Test
    void selectAs缺少同名关系时报错() {
        record Wrong(String material, List<Line> lines) { }
        var e = assertThrows(IllegalArgumentException.class, () -> from(Mara.class).selectAs(Wrong.class));
        assertEquals("Wrong.lines has no relation of the same name in the query's tables", e.getMessage());
    }

    @Test
    void 子列表的键不受only影响且不能流式读取或合并() {
        Query<MaterialLines> q = materialLines();
        assertTrue(q.only(List.of("material")).toSql(DIALECT).sql().startsWith("SELECT MARA.MATNR AS \"material\", MARA.MATNR AS \"material_2\", MARA.MANDT"));
        assertThrows(UnsupportedOperationException.class, () -> new TestDatabase(c -> { }).stream(q));
        assertThrows(IllegalStateException.class, () -> from(Mara.class).select(m -> row(MaterialLines::new, m.material(),
            m.deliveries().list(l -> row(Line::new, l.delivery(), l.quantity())))).unionAll(materialLines()));
    }

    @Test
    void 子投影里还可以再有子列表() {
        record Sales(String order) { }
        record Delivery(String delivery, List<Sales> sales) { }
        record Material(String material, List<Delivery> deliveries) { }
        Query<Material> q = from(Mara.class).select(m -> row(Material::new, m.material(),
            m.deliveries().list(l -> row(Delivery::new, l.delivery(), l.salesData().list(v -> row(Sales::new, v.salesOrder()))))));
        ChildRunner children = new ChildRunner(r -> r.sql().contains("FROM QUERY_IT.LIPS")
            ? List.<Object[]>of(new Object[] {"D1", "S1", "M1", "100"})
            : List.<Object[]>of(new Object[] {"S1", "S1"}));
        List<Material> rows = q.mapRows(List.<Object[]>of(new Object[] {"M1", "M1", "100"}), children);
        assertEquals(List.of(new Material("M1", List.of(new Delivery("D1", List.of(new Sales("S1")))))), rows);
        assertEquals(2, children.statements.size());
        assertTrue(children.statements.get(0).sql().startsWith("SELECT LIPS.VBELN AS \"delivery\", LIPS.VGBEL AS \"salesOrder\", LIPS.MATNR"),
            children.statements.get(0).sql());
    }

    @Test
    void 实体之外的Map结果也能放子列表() {
        Query<Map<String, Object>> q = from(Mara.class).select(m -> cols(m.material()));
        assertEquals(List.of(Map.of("material", "M1")), q.mapRows(List.<Object[]>of(new Object[] {"M1"}), x -> List.of()));
    }
}

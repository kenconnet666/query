package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.exec.TableRequest;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 条件树（conditionBy）：与 TypeScript 的 test/src/unit/condition-tree.test.ts 一一对应，SQL 相同。 */
@DisplayName("条件树")
class ConditionTreeTest {
    private static final TestDialect DIALECT = new TestDialect();

    /** 交货单的几列：数量是小数列，单位、交货单是文字列，件号经多对一关系。 */
    private static Query<Map<String, Object>> rows() {
        return from(Lips.class).select(l -> cols(l.delivery(), l.unit(), l.quantity(), l.mara().partNumber()));
    }

    /** 按物料汇总：total 是聚合列。 */
    private static Query<Map<String, Object>> totals() {
        return from(Lips.class).groupBy(l -> keys(l.material())).select(l -> cols(l.material(), l.quantity().sum().as("total")));
    }

    private static Rendered sqlOf(String json) { return rows().conditionBy(ConditionTree.parse(json)).toSql(DIALECT); }

    @Test
    void 最外层的all拆成几条进WHERE且值按列的类型读() {
        Rendered r = sqlOf("""
            {"all": [{"field": "quantity", "op": "gte", "value": "10.5"}, {"field": "partNumber", "op": "startsWith", "value": "50"}]}""");
        assertTrue(r.sql().contains("LEFT JOIN QUERY_IT.MARA MARA"), r.sql());
        assertTrue(r.sql().endsWith("WHERE LIPS.LFIMG >= ? AND MARA.MFRPN LIKE ? ESCAPE '!'"), r.sql());
        assertEquals(List.of(new BigDecimal("10.5"), "50%"), r.parameters());
    }

    @Test
    void 不等于不在其中不包含把空值算作满足() {
        String sql = sqlOf("""
            {"any": [{"field": "unit", "op": "ne", "value": "EA"},
                     {"field": "unit", "op": "notIn", "value": ["KG", "M"]},
                     {"field": "delivery", "op": "notContains", "value": "D"}]}""").sql();
        assertTrue(sql.contains("((LIPS.VRKME <> ?) OR (LIPS.VRKME IS NULL))"), sql);
        assertTrue(sql.contains("((LIPS.VRKME NOT IN (?, ?)) OR (LIPS.VRKME IS NULL))"), sql);
        assertTrue(sql.contains("((NOT (LIPS.VBELN LIKE ? ESCAPE '!')) OR (LIPS.VBELN IS NULL))"), sql);
    }

    @Test
    void 有值为空把空字符串也算作空且not写成NOT() {
        Rendered r = sqlOf("""
            {"all": [{"field": "unit", "op": "exists"}, {"not": {"field": "delivery", "op": "empty"}}]}""");
        assertTrue(r.sql().endsWith("WHERE LIPS.VRKME IS NOT NULL AND LIPS.VRKME <> ? AND NOT (((LIPS.VBELN IS NULL) OR (LIPS.VBELN = ?)))"), r.sql());
        assertEquals(List.of("", ""), r.parameters());
    }

    @Test
    void 介于两端都含空的一端不加条件且是其中之一写成IN() {
        String sql = sqlOf("""
            {"all": [{"field": "quantity", "op": "between", "value": [1, null]}, {"field": "unit", "op": "in", "value": ["EA", " ", "KG"]}]}""").sql();
        assertTrue(sql.endsWith("WHERE LIPS.LFIMG >= ? AND LIPS.VRKME IN (?, ?)"), sql);
    }

    @Test
    void 没填完整的比较和空的组不加条件() {
        Query<Map<String, Object>> q = rows();
        String base = q.toSql(DIALECT).sql();
        for (String json : List.of(
            "{\"all\": []}",
            "{\"any\": [{\"field\": \"unit\", \"op\": \"eq\", \"value\": \" \"}]}",
            "{\"all\": [{\"field\": \"quantity\", \"op\": \"between\", \"value\": [null, \"\"]}, {\"field\": \"unit\", \"op\": \"in\", \"value\": []}]}",
            "{\"not\": {\"all\": []}}")) {
            assertEquals(base, q.conditionBy(ConditionTree.parse(json)).toSql(DIALECT).sql(), json);
        }
        assertSame(q, q.conditionBy(null));
    }

    @Test
    void 聚合列进HAVING且一个分支混着两种列时报错() {
        String sql = totals().conditionBy(ConditionTree.parse("""
            {"all": [{"field": "material", "op": "eq", "value": "M1"}, {"field": "total", "op": "gt", "value": 5}]}""")).toSql(DIALECT).sql();
        assertTrue(sql.contains("\nWHERE LIPS.MATNR = ?\nGROUP BY "), sql);
        // 分组查询里的常量写成字面量（HANA 给不了参数类型，见 0016）
        assertTrue(sql.endsWith("HAVING SUM(LIPS.LFIMG) > 5"), sql);
        Query<Map<String, Object>> mixed = totals().conditionBy(ConditionTree.parse("""
            {"any": [{"field": "material", "op": "eq", "value": "M1"}, {"field": "total", "op": "gt", "value": 5}]}"""));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mixed.toSql(DIALECT));
        assertTrue(e.getMessage().contains("mixes plain and aggregated columns"), e.getMessage());
    }

    @Test
    void 只认结果列且形状或比较方式不对时报错并指出位置() {
        assertThrows(IllegalArgumentException.class, () -> sqlOf("{\"field\": \"plant\", \"op\": \"eq\", \"value\": \"1000\"}"), "unknown column 'plant'");
        IllegalArgumentException text = assertThrows(IllegalArgumentException.class, () -> sqlOf("{\"field\": \"quantity\", \"op\": \"contains\", \"value\": \"1\"}"));
        assertTrue(text.getMessage().contains("needs a text column"), text.getMessage());
        IllegalArgumentException op = assertThrows(IllegalArgumentException.class,
            () -> ConditionTree.parse("{\"all\": [{\"field\": \"unit\", \"op\": \"like\", \"value\": \"%\"}]}"));
        assertTrue(op.getMessage().startsWith("condition.all[0].op: unknown operator \"like\""), op.getMessage());
        IllegalArgumentException shape = assertThrows(IllegalArgumentException.class, () -> ConditionTree.parse("{\"any\": \"x\"}"));
        assertEquals("condition.any: expected an array", shape.getMessage());
        String deep = "{\"not\": ".repeat(40) + "{\"field\": \"unit\", \"op\": \"exists\"}" + "}".repeat(40);
        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class, () -> ConditionTree.parse(deep));
        assertTrue(nested.getMessage().contains("nested more than 32 levels"), nested.getMessage());
    }

    @Test
    void 已经解析好的JSON树也能读且字段按出现的先后() {
        ConditionTree tree = ConditionTree.from(Map.of("any", List.of(
            Map.of("field", "unit", "op", "eq", "value", "EA"),
            Map.of("not", Map.of("field", "delivery", "op", "exists")))));
        assertEquals(List.of("unit", "delivery"), List.copyOf(tree.fields()));
    }

    @Test
    void TableRequest读condition参数() {
        TableRequest request = TableRequest.parse(Map.of("condition", List.of("{\"any\": [{\"field\": \"unit\", \"op\": \"eq\", \"value\": \"EA\"}]}")));
        assertEquals(new ConditionTree.Any(List.of(new ConditionTree.Rule("unit", ConditionTree.Op.EQ, "EA"))), request.condition());
        assertNull(TableRequest.parse(Map.of()).condition());
        assertThrows(IllegalArgumentException.class, () -> TableRequest.parse(Map.of("condition", List.of("{\"field\":\"unit\",\"op\":\"nope\"}"))));
    }
}

package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("表格查询的请求")
class TableRequestTest {
    /** 地址栏参数，同 Spring 的 MultiValueMap。 */
    private static Map<String, List<String>> params(String... pairs) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.computeIfAbsent(pairs[i], k -> new java.util.ArrayList<>()).add(pairs[i + 1]);
        }
        return out;
    }

    @Test
    void 读地址栏参数_其余参数不管() {
        TableRequest r = TableRequest.parse(params("page", "2", "pageSize", "20", "sort", "-total,material", "total", "368",
            "filter.unit", "EA", "filter.unit", "KG", "filter.material", " ", "search.material", " 泵 ", "keyword", "x"));
        assertEquals(new TableRequest(2, 20, "-total,material", 368L, Map.of("unit", List.of("EA", "KG")), Map.of("material", "泵")), r);
        TableRequest defaults = TableRequest.parse(Map.of());
        assertEquals(1, defaults.page());
        assertEquals(TableRequest.DEFAULT_PAGE_SIZE, defaults.pageSize());
        assertNull(defaults.sort());
        assertNull(defaults.total());
    }

    @Test
    void 写错的数字和过大的条数报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> TableRequest.parse(params("page", "x")));
        assertEquals("page must be an integer, got 'x'", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TableRequest.parse(params("page", "0")));
        assertThrows(IllegalArgumentException.class, () -> TableRequest.parse(params("pageSize", "2001")));
        assertEquals(5000, TableRequest.parse(params("pageSize", "5000"), 5000).pageSize());
        assertThrows(IllegalArgumentException.class, () -> TableRequest.parse(params("total", "-1")));
    }

    @Test
    void table依次筛选排序分页_有已知总数时不再COUNT() {
        Database db = TestDatabase.dryRun(c -> { });
        var q = from(Lips.class).select(l -> cols(l.delivery(), l.unit(), l.quantity()));
        var events = db.capture(() -> db.table(q, TableRequest.parse(params("page", "2", "pageSize", "5", "sort", "-quantity", "filter.unit", "EA"))));
        assertEquals(2, events.size());
        String rows = events.get(0).statement().sql();
        assertTrue(rows.contains("WHERE LIPS.VRKME IN (?)") && rows.contains("ORDER BY LIPS.LFIMG DESC"), rows);
        assertTrue(events.get(1).statement().sql().startsWith("SELECT COUNT(*)"), events.get(1).statement().sql());
        assertEquals(1, db.capture(() -> db.table(q, TableRequest.parse(params("total", "12")))).size());
    }

    record Line(String delivery, BigDecimal quantity, LocalDate on) { }

    @Test
    void 写成线上的JSON时total是数字_行里的小数与日期是文字() {
        Page<Line> page = new Page<>(List.of(new Line("D1", new BigDecimal("2.50"), LocalDate.of(2026, 1, 31))), 12, 2, 5);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("rows", List.of(Map.of("delivery", "D1", "quantity", "2.50", "on", "2026-01-31")));
        expected.put("total", 12);
        expected.put("page", 2);
        expected.put("size", 5);
        expected.put("totalPages", 3);
        expected.put("$types", Map.of("rows.quantity", "decimal", "rows.on", "date"));
        assertEquals(expected, page.toWire());
    }
}

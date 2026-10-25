package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.exec.Page;
import org.dpdns.zerodep.query.exec.TableRequest;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.select.ConditionTree;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.Range;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.keys;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 表格查询（0016）：表头筛选、关键字、排序、分页、已知总数，分组后按聚合筛选，区间，直接数行。数据见 {@code support.SampleData}。
 * 与 TypeScript 的 table.live.test.ts 对应。
 */
@LiveTest
@DisplayName("表格查询")
class TableQueryTest {
    @Parameter Live live;

    record Line(String delivery, String material, BigDecimal quantity, String partNumber) { }

    /** 区间的条件对象：交货的创建日期两端都含。 */
    record CreatedSearch(@Where(value = "createdOn", op = Where.Op.RANGE) Range<LocalDate> created) { }

    private static Query<Line> lines() {
        return from(Lips.class).select(l -> cols(l.delivery(), l.material(), l.quantity(), l.mara().partNumber())).into(Line.class);
    }

    /** 地址栏参数，同 Spring 的 MultiValueMap。 */
    private static TableRequest request(String... pairs) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) params.put(pairs[i], List.of(pairs[i + 1]));
        return TableRequest.parse(params);
    }

    @Test
    void table按表头筛选排序分页_带上已知总数时不再数() {
        // 客户端 100 里 M1 有 D1（3）、D2（2）、D5（1）。
        Page<Line> first = live.db().table(lines(), request("filter.material", "M1", "sort", "-quantity", "pageSize", "2"));
        assertEquals(List.of("D1", "D2"), first.rows().stream().map(Line::delivery).toList());
        assertEquals(List.of(3L, 2L), List.of(first.total(), first.totalPages()));
        Page<Line> second = live.db().table(lines(), request("filter.material", "M1", "sort", "-quantity", "pageSize", "2", "page", "2", "total", "3"));
        assertEquals(List.of("D5"), second.rows().stream().map(Line::delivery).toList());
        assertEquals(3L, second.total());
    }

    @Test
    void 关键字按文字包含筛选_经过的多对一关系照常连接() {
        // 零件号里有 2 的只有 M2（P2）：D3、D4。
        Page<Line> page = live.db().table(lines(), request("search.partNumber", "2", "sort", "delivery"));
        assertEquals(List.of("D3", "D4"), page.rows().stream().map(Line::delivery).toList());
        assertEquals(2L, page.total());
    }

    @Test
    void 分组后按聚合的列筛选写进HAVING_数行数时去掉只为投影连接的关系() {
        Query<Map<String, Object>> totals = from(Lips.class)
            .groupBy(l -> keys(l.material()))
            .orderBy(l -> l.material())
            .select(l -> cols(l.material(), l.quantity().sum().as("total")));
        // M1 合计 6，M2 合计 12。
        assertEquals(List.of("M1"), live.db().list(totals.filterBy(Map.of("total", List.of("6")))).stream().map(r -> r.get("material")).toList());
        assertEquals(2L, live.db().count(totals));
        assertEquals(5L, live.db().count(lines()));
    }

    @Test
    void 区间两端都含_空的一端不加条件() {
        java.util.function.Function<Range<LocalDate>, List<String>> deliveries = created -> live.db().list(
            from(Lips.class).where(new CreatedSearch(created)).orderBy(l -> l.delivery()).select(l -> l.delivery()));
        assertEquals(List.of("D1", "D2"), deliveries.apply(new Range<>(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 20))));
        assertEquals(List.of("D3", "D5"), deliveries.apply(new Range<>(LocalDate.of(2026, 2, 1), null)));
    }

    @Test
    void 条件树_any里套all_介于含两端_不等于与不包含_经table的condition参数() {
        String tree = """
            {"any": [{"all": [{"field": "material", "op": "eq", "value": "M1"}, {"field": "quantity", "op": "gte", "value": 3}]},
                     {"field": "delivery", "op": "in", "value": ["D4", "D5"]}]}""";
        Page<Line> page = live.db().table(lines(), request("condition", tree, "sort", "delivery"));
        assertEquals(List.of("D1", "D4", "D5"), page.rows().stream().map(Line::delivery).toList());
        assertEquals(3L, page.total());
        assertEquals(List.of("D1", "D2", "D3"), deliveries("{\"field\": \"quantity\", \"op\": \"between\", \"value\": [2, 5]}"));
        assertEquals(List.of("D1", "D2", "D5"), deliveries("""
            {"all": [{"field": "partNumber", "op": "ne", "value": "P2"}, {"field": "partNumber", "op": "notContains", "value": "2"}]}"""));
    }

    private List<String> deliveries(String tree) {
        return live.db().list(lines().conditionBy(ConditionTree.parse(tree)).sortBy("delivery")).stream().map(Line::delivery).toList();
    }
}

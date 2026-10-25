package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.annotation.Where;
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

import static org.dpdns.zerodep.query.Sql.from;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 条件对象：请求参数对象的每个非空字段是一个条件，字段名或 {@code @Where} 指向列或关系上的列。
 * 数据见 {@link SampleData}。
 */
@LiveTest
@DisplayName("条件对象")
class FilterObjectTest {
    @Parameter Live live;

    /** 交货查询的请求参数：关键字同时在零件号和描述里找。 */
    record DeliverySearch(
        List<String> material,
        @Where(value = "createdOn", op = Where.Op.GE) LocalDate from,
        @Where(value = "createdOn", op = Where.Op.LT) LocalDate to,
        @Where(value = {"mara.partNumber", "text.text"}, op = Where.Op.CONTAINS) String keyword,
        @Where(op = Where.Op.NE) String unit) { }

    /** 按条件对象筛选交货单。 */
    private List<String> deliveries(DeliverySearch search) {
        return live.db().list(from(Lips.class).where(search).orderBy(l -> l.delivery()).select(l -> l.delivery()));
    }

    @Test
    void 非空字段才产生条件且通配符按普通字符匹配() {
        // 关键字 P 出现在 M1、M2 的零件号里；D4 在 2025 年被 from 挡掉。
        assertEquals(List.of("D1", "D2", "D3", "D5"), deliveries(new DeliverySearch(null, LocalDate.of(2026, 1, 1), null, "P", null)));
        assertEquals(List.of("D5"), deliveries(new DeliverySearch(List.of("M1"), LocalDate.of(2026, 3, 1), null, null, null)));
        for (String wildcard : List.of("%", "_", "P%", "!")) {
            assertEquals(List.of(), deliveries(new DeliverySearch(null, null, null, wildcard, null)), "LIKE 的通配符和转义符照字面找：" + wildcard);
        }
    }

    /** 物料查询的请求参数：两个字段走同一个一对多关系。 */
    record MaterialSearch(
        @Where("deliveries.unit") String unit,
        @Where(value = "deliveries.quantity", op = Where.Op.GE) BigDecimal minQuantity,
        String materialType) { }

    @Test
    void 经过一对多关系的几个字段由同一个子行满足() {
        // 两个字段合并进同一个 EXISTS：要有一张交货行既是 EA、数量又不少于 5，只有 M2（D3、D4）。
        assertEquals(List.of("M2"), materials(new MaterialSearch("EA", new BigDecimal("5"), null)));
        assertEquals(List.of("M1", "M2"), materials(new MaterialSearch("EA", null, null)));
        assertEquals(List.of("M1", "M2", "M3", "M4"), materials(new MaterialSearch(null, null, " ")), "只有空白的文本当作没填");
    }

    /** 按条件对象筛选物料。 */
    private List<String> materials(MaterialSearch search) {
        return live.db().list(from(Mara.class).where(search).orderBy(m -> m.material()).select(m -> m.material()));
    }
}

package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.expr.typed.ExprDecimal;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Mardh;
import org.dpdns.zerodep.query.testing.Erp.Mchbh;
import org.dpdns.zerodep.query.testing.Erp.PeriodStock;
import org.dpdns.zerodep.query.testing.Erp.StockRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.dpdns.zerodep.query.Sql.allOf;
import static org.dpdns.zerodep.query.Sql.anyOf;
import static org.dpdns.zerodep.query.Sql.bool;
import static org.dpdns.zerodep.query.Sql.caseWhen;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.cte;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.keys;
import static org.dpdns.zerodep.query.Sql.nullOf;
import static org.dpdns.zerodep.query.Sql.row;
import static org.dpdns.zerodep.query.Sql.values;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 连接、子查询与集合运算：RIGHT JOIN 和各种子查询比较、INTERSECT / EXCEPT / UNION 的求值顺序、泛型模板拼成的 UNION、
 * 常量行做 CTE。数据见 {@link SampleData}；共享实例只看客户端 100。
 */
@LiveTest
@DisplayName("连接、子查询与集合运算")
class JoinAndSetTest {
    @Parameter Live live;

    @Test
    void RIGHT_JOIN保留右侧的行且子查询比较与NULL安全比较正确() {
        // ON 里多了一个永远不成立的条件，所以右侧每一行都保留、左侧全是 NULL；再用 WHERE 里的五种比较筛掉一部分。
        Query<BigDecimal> d2 = from(Lips.class).where(x -> x.delivery().eq("D2")).select(x -> x.quantity());
        Query<BigDecimal> m2 = from(Lips.class).where(x -> x.material().eq("M2")).select(x -> x.quantity());
        List<String> rows = live.db().list(from(Mara.class)
                .rightJoin(Lips.class, (m, l) -> {
                    m.material().eq(l.material());
                    m.materialType().eq("NONE");
                })
                .where((m, l) -> {
                    keys(l.delivery(), l.material()).in(from(Lips.class).where(x -> x.unit().eq("EA")).select(x -> cols(x.delivery(), x.material())));
                    l.quantity().ge(anyOf(d2));
                    // 不少于 2
                        l.quantity().lt(allOf(m2));
                    // 少于 M2 的每一行（5 和 7）
                        m.partNumber().isNotDistinctFrom(nullOf(String.class));
                    // NULL 也算相等
                        l.unit().isDistinctFrom(m.partNumber());
                })          // EA 与 NULL 算不同
                .orderBy((m, l) -> l.delivery())
                .select((m, l) -> cols(l.delivery(), m.partNumber(), bool(() -> l.quantity().gt(new BigDecimal("2"))).as("big"))))
            .stream().map(r -> r.get("delivery") + ":" + r.get("partNumber") + ":" + r.get("big")).toList();
        assertEquals(List.of("D1:null:true", "D2:null:false"), rows);
    }

    @Test
    void 交集与差集按调用顺序求值() {
        Query<String> other = from(Mara.class).where(m -> m.materialType().eq("ZOTH")).select(m -> m.material());       // M3、M4
        Query<String> delivered = from(Lips.class).select(l -> l.material());                                           // M1、M2
        Query<String> zmat = from(Mara.class).where(m -> m.materialType().eq("ZMAT")).select(m -> m.material());        // M1、M2
        Query<String> mk1 = from(Mara.class).where(m -> m.manufacturer().eq("MK1")).select(m -> m.material());          // M1、M3
        Query<String> mk2 = from(Mara.class).where(m -> m.manufacturer().eq("MK2")).select(m -> m.material());          // M2
        // ({M3, M4} ∪ {M1, M2}) ∩ {M1, M2} − ({M1, M3} − {M2})：按调用顺序加括号；照 PostgreSQL 的优先级不加括号会得到别的结果。
        assertEquals(List.of("M2"), live.db().list(other.union(delivered).intersect(zmat).except(mk1.except(mk2))));
    }

    @Test
    void 右侧的UNION保持原有分组() {
        // D1 UNION ALL (D3 UNION D2)：右侧先去重成 {M2, M1}，再与 D1 的 M1 合起来，所以 M1 出现两次。
        Query<String> union = materialOf("D1").unionAll(materialOf("D3").union(materialOf("D2")));
        assertEquals(List.of("M1", "M1", "M2"), live.db().list(union).stream().sorted().toList());
    }

    /** 某张交货单的物料。 */
    private static Query<String> materialOf(String delivery) { return from(Lips.class).where(l -> l.delivery().eq(delivery)).select(l -> l.material()); }

    @Test
    void 泛型模板生成的UNION合并两张库存表() {
        // MARDH 与 MCHBH 形状相同、数量列的列名不同：同一个模板方法套用在两张表上，再 UNION ALL。
        Set<String> rows = live.db().list(stock(Mardh.class).unionAll(stock(Mchbh.class))).stream()
            .map(r -> r.material() + "/" + r.period() + "/" + r.quantity().stripTrailingZeros().toPlainString())
            .collect(Collectors.toSet());
        assertEquals(Set.of("M1/01/10", "M2/01/0", "M1/02/6"), rows, "2026 年的三行；M2 的数量是 NULL，按 0 读");
    }

    /** 一张月度库存表在 2026 年的行。 */
    private static <E extends PeriodStock> Query<StockRow> stock(Class<E> table) {
        return from(table)
            .where(h -> h.year().eq("2026"))
            .select(h -> row(StockRow::new, h.material(), h.year(), h.period(), h.quantity().coalesce(BigDecimal.ZERO).as("quantity")));
    }

    /** 尺寸码表的一行。 */
    record Size(String name, Integer ord) { }

    /** 按尺寸合计的一行。 */
    record SizeTotal(String bucket, BigDecimal total) { }

    @Test
    void 常量行做CTE并按CASE表达式分组() {
        // sizes 是写在查询里的码表；totals 按数量分档后合计。码表 LEFT JOIN 合计，没有交货的档位得 0。
        Cte<Size> sizes = cte("sizes", values(Size.class, List.of(new Size("small", 1), new Size("medium", 2), new Size("large", 3))));
        Cte<SizeTotal> totals = cte("totals", from(Lips.class)
            .groupBy(l -> size(l))
            .select(l -> cols(size(l).as("bucket"), l.quantity().sum().as("total")))
            .into(SizeTotal.class));
        List<String> rows = live.db().list(from(sizes)
            .leftJoin(totals, (s, t) -> t.col(SizeTotal::bucket).eq(s.col(Size::name)))
            .orderBy((s, t) -> s.col(Size::ord))
            .select((s, t) -> row((name, total) -> name + "=" + total.stripTrailingZeros().toPlainString(),
                s.col(Size::name), t.col(SizeTotal::total).coalesce(BigDecimal.ZERO))));
        assertEquals(List.of("small=3", "medium=8", "large=7"), rows, "small：2、1；medium：3、5；large：7");
    }

    /** 数量分档：不超过 2 是 small，不超过 5 是 medium，其余 large。 */
    private static ExprText<String> size(Lips l) {
        ExprDecimal<BigDecimal> quantity = l.quantity();
        return caseWhen(() -> quantity.le(new BigDecimal("2")), "small").when(() -> quantity.le(new BigDecimal("5")), "medium").otherwise("large");
    }
}

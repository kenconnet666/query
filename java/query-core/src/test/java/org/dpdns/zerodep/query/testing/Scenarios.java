package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Makt;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Mardh;
import org.dpdns.zerodep.query.testing.Erp.MaterialScope;
import org.dpdns.zerodep.query.testing.Erp.Mchbh;
import org.dpdns.zerodep.query.testing.Erp.PartTotal;
import org.dpdns.zerodep.query.testing.Erp.PeriodStock;
import org.dpdns.zerodep.query.testing.Erp.StockRow;
import org.dpdns.zerodep.query.testing.Erp.Vbkd;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.dpdns.zerodep.query.Sql.*;

/** 按使用方的写法写成的查询，渲染测试和金样例用它们；实库测试把查询直接写在 query-it 的各个测试里。 */
public final class Scenarios {
    private Scenarios() { }

    // ---------- 业务口径就是普通方法 ----------

    /** 有效物料：物料类型为 ZMAT；在条件块里调用，条件写进调用处的块。 */
    public static void activeMaterial(Mara ma) { ma.materialType().eq("ZMAT"); }

    /** 零件号去掉首尾空格后与原值不同时取去空格的值。 */
    public static ExprText<String> normalizedPart(ExprText<String> part) {
        return caseWhen(() -> part.ne(part.trim()), part.trim()).otherwise(part);
    }

    /** CTE、EXISTS、LEFT JOIN、可选条件和分组，按位置投影；列名与 TypeScript 的键一致。 */
    public static Query<Delivery> deliveries(LocalDate start, LocalDate end, String maker) {
        Cte<MaterialScope> scope = cte("material_scope", from(Mara.class)
            .skipEmpty()
            .where(ma -> {
                activeMaterial(ma);
                ma.manufacturer().eq(maker);
            })
            .distinct()
            .select(ma -> cols(ma.client(), ma.material(), normalizedPart(ma.partNumber()).as("partNumber")))
            .into(MaterialScope.class));
        return from(scope)
            .join(Lips.class, (ms, l) -> {
                l.client().eq(ms.col(MaterialScope::client));
                l.material().eq(ms.col(MaterialScope::material));
            })
            .leftJoin(Makt.class, (ms, l, mk) -> {
                mk.material().eq(ms.col(MaterialScope::material));
                mk.language().eq("1");
            })
            .where((ms, l, mk) -> {
                l.createdOn().between(start, end);
                exists(from(Vbkd.class).where(v -> v.salesOrder().eq(l.salesOrder())));
            })
            .groupBy((ms, l, mk) -> ms.col(MaterialScope::partNumber))
            .orderBy((ms, l, mk) -> ms.col(MaterialScope::partNumber))
            .select((ms, l, mk) -> row(Delivery::new,
                ms.col(MaterialScope::partNumber),
                mk.text().coalesce(ms.col(MaterialScope::partNumber)).max().as("name"),
                l.quantity().sum().as("quantity")));
    }

    /** 一个模板套用到所有 PeriodStock 形状的表；数量列由各表自己声明。 */
    public static <E extends PeriodStock> Query<StockRow> stockArm(Class<E> table, String year) {
        return from(table)
            .where(h -> h.year().eq(year))
            .select(h -> row(StockRow::new, h.material(), h.year(), h.period(), h.quantity().coalesce(BigDecimal.ZERO).as("quantity")));
    }

    /** 两张库存表的 UNION ALL。 */
    public static Query<StockRow> stock(String year) {
        return stockArm(Mardh.class, year).unionAll(stockArm(Mchbh.class, year));
    }

    /** ROW_NUMBER 的结果行。 */
    public record RankedDelivery(String material, String delivery, Long rank) { }

    /** 每个物料最新的交货：CTE 里算 ROW_NUMBER，再取 rank = 1。 */
    public static Query<String> latestDeliveries() {
        Cte<RankedDelivery> ranked = cte("ranked_delivery", from(Lips.class).select(l -> cols(
            l.material(),
            l.delivery(),
            rowNumber().over(w -> w.partitionBy(l.material()).orderBy(l.createdOn().desc(), l.delivery().desc())).as("rank")))
            .into(RankedDelivery.class));
        return from(ranked)
            .where(x -> x.col(RankedDelivery::rank).eq(1L))
            .orderBy(x -> x.col(RankedDelivery::material))
            .select(x -> x.col(RankedDelivery::delivery));
    }

    /** 按名映射：改名的列写 as。 */
    public static Query<LineVO> lines() {
        return from(Lips.class)
            .leftJoin(Makt.class, (l, mk) -> {
                mk.material().eq(l.material());
                mk.language().eq("1");
            })
            .orderBy((l, mk) -> l.delivery())
            .select((l, mk) -> cols(l.delivery(), l.material().as("materialNo"), l.createdOn(), mk.text()))
            .into(LineVO.class);
    }

    /** 嵌套结果与 Java 端计算：row 的第一个参数也可以是普通 lambda。 */
    public static Query<PartTotal> partTotals() {
        return from(Lips.class)
            .join(Mara.class, (l, ma) -> ma.material().eq(l.material()))
            .groupBy((l, ma) -> ma.partNumber())
            .orderBy((l, ma) -> ma.partNumber())
            .select((l, ma) -> row((quantity, part) -> new PartTotal(part,
                    new PartTotal.Amount(quantity, quantity == null ? 0 : quantity.doubleValue() / 10_000)),
                l.quantity().sum(), ma.partNumber()));
    }
}

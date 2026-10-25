package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.cte;
import static org.dpdns.zerodep.query.Sql.cumeDist;
import static org.dpdns.zerodep.query.Sql.firstValue;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.lag;
import static org.dpdns.zerodep.query.Sql.lastValue;
import static org.dpdns.zerodep.query.Sql.nthValue;
import static org.dpdns.zerodep.query.Sql.ntile;
import static org.dpdns.zerodep.query.Sql.orders;
import static org.dpdns.zerodep.query.Sql.percentRank;
import static org.dpdns.zerodep.query.Sql.rowNumber;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 窗口函数：每组取最新的行（CTE 加 ROW_NUMBER，或 qualify）、首行末行上一行与分组号、相对排名与累计分布。
 * 数据见 {@link SampleData}：M1 的交货 D1、D2、D5（3、2、1，D5 最新），M2 的 D3、D4（5、7，D3 最新）。
 */
@LiveTest
@DisplayName("窗口函数")
class WindowTest {
    @Parameter Live live;

    /** ROW_NUMBER 的结果行。 */
    record RankedDelivery(String material, String delivery, Long rank) { }

    @Test
    void 在CTE里排名后取每个物料最新的交货() {
        Cte<RankedDelivery> ranked = cte("ranked_delivery", from(Lips.class).select(l -> cols(
                l.material(),
                l.delivery(),
                rowNumber().over(w -> w.partitionBy(l.material()).orderBy(l.createdOn().desc(), l.delivery().desc())).as("rank")))
            .into(RankedDelivery.class));
        assertEquals(List.of("D5", "D3"), live.db().list(from(ranked)
            .where(x -> x.col(RankedDelivery::rank).eq(1L))
            .orderBy(x -> x.col(RankedDelivery::material))
            .select(x -> x.col(RankedDelivery::delivery))));
    }

    @Test
    void 窗口条件取每组最新的两行() {
        // qualify 把窗口条件包成派生表；排序用的创建日期不在结果里，作为额外的列带出来。
        List<String> rows = live.db().list(from(Lips.class)
                .qualify(l -> rowNumber().over(w -> w.partitionBy(l.material()).orderBy(l.createdOn().desc())).le(2L))
                .orderBy(l -> orders(l.material(), l.createdOn().desc()))
                .select(l -> cols(l.material(), l.delivery())))
            .stream().map(r -> r.get("material") + ":" + r.get("delivery")).toList();
        assertEquals(List.of("M1:D5", "M1:D2", "M2:D3", "M2:D4"), rows);
    }

    @Test
    void 窗口里的首行末行上一行与分组号() {
        // 末行要整个分区作为窗口帧；上一行没有时取默认值 0；ntile(2) 把五行按交货单分成两组（3 + 2）。
        List<String> rows = live.db().list(from(Lips.class)
                .orderBy(l -> l.delivery())
                .select(l -> cols(l.delivery(),
                    firstValue(l.quantity()).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())).as("first"),
                    lastValue(l.quantity()).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())
                        .rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())).as("last"),
                    lag(l.quantity(), 1, BigDecimal.ZERO).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())).as("previous"),
                    ntile(2).over(w -> w.orderBy(l.delivery())).as("half"))))
            .stream().map(r -> r.get("delivery") + ":" + plain(r.get("first")) + ":" + plain(r.get("last")) + ":" + plain(r.get("previous")) + ":" + r.get("half"))
            .toList();
        assertEquals(List.of("D1:3:1:0:1", "D2:3:1:3:1", "D3:5:7:0:1", "D4:5:7:5:2", "D5:3:1:2:2"), rows);
    }

    @Test
    void 相对排名累计分布与第n行() {
        List<String> rows = live.db().list(from(Lips.class)
                .orderBy(l -> l.delivery())
                .select(l -> cols(l.delivery(),
                    percentRank().over(w -> w.orderBy(l.quantity())).as("percentRank"),
                    cumeDist().over(w -> w.partitionBy(l.material()).orderBy(l.quantity())).as("cumeDist"),
                    nthValue(l.delivery(), 2).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())
                        .rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())).as("second"))))
            .stream().map(r -> r.get("delivery") + ":" + String.format(Locale.ROOT, "%.2f:%.2f", r.get("percentRank"), r.get("cumeDist")) + ":" + r.get("second"))
            .toList();
        assertEquals(List.of("D1:0.50:1.00:D2", "D2:0.25:0.67:D2", "D3:0.75:0.50:D4", "D4:1.00:1.00:D4", "D5:0.00:0.33:D2"), rows);
    }

    /** 小数去掉末尾的零。 */
    private static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }
}

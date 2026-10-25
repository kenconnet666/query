package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.avgDistinct;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.count;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.grouping;
import static org.dpdns.zerodep.query.Sql.keys;
import static org.dpdns.zerodep.query.Sql.orders;
import static org.dpdns.zerodep.query.Sql.stddevPop;
import static org.dpdns.zerodep.query.Sql.stddevSamp;
import static org.dpdns.zerodep.query.Sql.stringAgg;
import static org.dpdns.zerodep.query.Sql.sum;
import static org.dpdns.zerodep.query.Sql.sumDistinct;
import static org.dpdns.zerodep.query.Sql.varPop;
import static org.dpdns.zerodep.query.Sql.varSamp;
import static org.dpdns.zerodep.query.it.support.Db.SQLITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 分组与聚合：ROLLUP 的小计与总计、带条件的聚合、去重聚合与统计、按用户的选择去掉分组维度。
 * 数据见 {@link SampleData}：客户端 100 的交货里 M1 有 D1、D2、D5（3、2、1），M2 有 D3、D4（5、7），单位都是 EA。
 */
@LiveTest
@DisplayName("分组与聚合")
class AggregateTest {
    @Parameter Live live;

    @Test
    void ROLLUP给出小计与总计() {
        Query<Map<String, Object>> rollup = from(Lips.class)
            .groupByRollup(l -> keys(l.material(), l.unit()))
            .orderBy(l -> orders(grouping(l.material()), l.material(), grouping(l.unit())))
            .select(l -> cols(l.material(), l.unit(), sum(l.quantity()).as("total"), grouping(l.material()).as("level")));
        if (live.is(SQLITE)) {
            assertThrows(UnsupportedOperationException.class, () -> live.db().list(rollup), "SQLite 没有 ROLLUP，渲染时报错");
            return;
        }
        List<String> rows = live.db().list(rollup).stream()
            .map(r -> r.get("material") + "/" + r.get("unit") + "/" + plain(r.get("total")) + "/" + r.get("level"))
            .toList();
        assertEquals(List.of("M1/EA/6/0", "M1/null/6/0", "M2/EA/12/0", "M2/null/12/0", "null/null/18/1"), rows);
    }

    @Test
    void 条件聚合只聚合满足条件的行且文本按指定顺序拼接() {
        // recent：2026 年以后的数量合计（M2 的 D4 在 2025 年）；big：数量大于 2 的行数；交货单倒序拼接。
        List<String> rows = live.db().list(from(Lips.class)
                .groupBy(l -> l.material())
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(),
                    sum(l.quantity()).filter(l.createdOn().ge(LocalDate.of(2026, 1, 1))).as("recent"),
                    count().filter(l.quantity().gt(new BigDecimal("2"))).as("big"),
                    stringAgg(l.delivery(), ",", l.delivery().desc()).as("deliveries"))))
            .stream().map(r -> r.get("material") + ":" + plain(r.get("recent")) + ":" + r.get("big") + ":" + r.get("deliveries")).toList();
        assertEquals(List.of("M1:6:1:D5,D2,D1", "M2:5:2:D4,D3"), rows);
    }

    @Test
    void 去重聚合与标准差方差() {
        List<String> rows = live.db().list(from(Lips.class)
                .groupBy(l -> l.material())
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(),
                    sumDistinct(l.quantity()).as("distinctTotal"),
                    avgDistinct(l.quantity()).as("distinctAverage"),
                    stddevSamp(l.quantity()).as("sampleDeviation"),
                    stddevPop(l.quantity()).as("populationDeviation"),
                    varSamp(l.quantity()).as("sampleVariance"),
                    varPop(l.quantity()).as("populationVariance"))))
            .stream().map(r -> r.get("material") + ":" + plain(r.get("distinctTotal")) + ":" + plain(r.get("distinctAverage")) + ":"
                + String.format(Locale.ROOT, "%.4f:%.4f:%.4f:%.4f", r.get("sampleDeviation"), r.get("populationDeviation"),
                    r.get("sampleVariance"), r.get("populationVariance")))
            .toList();
        assertEquals(List.of("M1:6:2:1.0000:0.8165:1.0000:0.6667", "M2:12:6:1.4142:1.0000:2.0000:1.0000"), rows);
    }

    @Test
    void 去掉分组维度后按用户的排序串汇总() {
        // 报表允许用户隐藏列：exclude 去掉单位和零件号，它们的分组键、排序和导航带来的连接都随之去掉；sortBy 接用户的排序串。
        List<String> rows = live.db().list(from(Lips.class)
                .groupBy(l -> keys(l.material(), l.unit(), l.mara().partNumber()))
                .orderBy(l -> orders(l.material().asc(), l.unit().asc()))
                .select(l -> cols(l.material(), l.unit(), l.mara().partNumber(), sum(l.quantity()).as("total")))
                .exclude("unit", "partNumber")
                .sortBy("total desc, material"))
            .stream().map(r -> r.get("material") + ":" + r.get("unit") + ":" + r.get("partNumber") + ":" + plain(r.get("total")))
            .toList();
        assertEquals(List.of("M2:null:null:12", "M1:null:null:6"), rows);
    }

    /** 小数去掉末尾的零。 */
    private static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }
}

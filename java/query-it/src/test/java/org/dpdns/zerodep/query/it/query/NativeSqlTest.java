package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.cte;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.identifier;
import static org.dpdns.zerodep.query.Sql.sql;
import static org.dpdns.zerodep.query.Sql.sqlCond;
import static org.dpdns.zerodep.query.Sql.sqlTable;
import static org.dpdns.zerodep.query.Sql.val;
import static org.dpdns.zerodep.query.Sql.values;
import static org.dpdns.zerodep.query.it.support.Db.SQLITE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 原生 SQL、字面量与参数：整条的原生 SQL 带命名参数、查询里嵌入的原生片段，以及会把字面量提前结束的文本
 * 写成字面量和绑成参数时都原样往返。数据见 {@link SampleData}。
 */
@LiveTest
@DisplayName("原生 SQL、字面量与参数")
class NativeSqlTest {
    @Parameter Live live;

    @Test
    void 整条原生SQL的命名参数和集合展开() {
        // 手写 SQL 里的表名、列名按本库的方言加引号；:ids 是集合，展开成两个参数。
        List<String> rows = live.db().sql("SELECT " + live.quote("VBELN") + " AS delivery FROM " + live.table("LIPS")
                + " WHERE " + live.quote("MANDT") + " = :client AND " + live.quote("VBELN") + " IN (:ids) ORDER BY " + live.quote("VBELN"))
            .bind("client", "100").bind("ids", List.of("D1", "D3")).list(String.class);
        assertEquals(List.of("D1", "D3"), rows);
    }

    /** 原生 SQL 数据源的一行：物料和数量合计。 */
    record MaterialTotal(String material, BigDecimal total) { }

    @Test
    void 查询里嵌入原生的数据源条件和表达式() {
        Assumptions.assumeFalse(live.is(SQLITE), "the hand-written fragment calls LPAD, which SQLite lacks");
        // sqlTable 里的 {n} 是标识符（按方言加引号）或集合参数；sql(...)、sqlCond(...) 混用表达式和值。原生 SQL 不经过全局过滤器，所以合计含客户端 200 的 D6。
        var totals = sqlTable(MaterialTotal.class, "SELECT {0} AS {1}, SUM({2}) AS {3} FROM {4}.{5} WHERE {6} IN ({7}) GROUP BY {0}",
            identifier("MATNR"), identifier("material"), identifier("LFIMG"), identifier("total"), identifier("QUERY_IT"), identifier("LIPS"),
            identifier("VRKME"), List.of("EA", "KG"));
        List<String> rows = live.db().list(from(totals)
                .join(Mara.class, (t, m) -> m.material().eq(t.col(MaterialTotal::material)))
                .where((t, m) -> sqlCond("{0} <> {1}", m.materialType(), "ZOTH"))
                .orderBy((t, m) -> t.col(MaterialTotal::material))
                .select((t, m) -> cols(t.col(MaterialTotal::material), sql(String.class, "LPAD({0}, {1}, '0')", m.material(), 10).as("padded"),
                    t.col(MaterialTotal::total))))
            .stream().map(r -> r.get("material") + ":" + r.get("padded") + ":" + ((BigDecimal) r.get("total")).stripTrailingZeros().toPlainString()).toList();
        assertEquals(List.of("M1:00000000M1:106", "M2:00000000M2:12"), rows);
    }

    /** 会把字面量提前结束、开启注释或者像参数的文本。 */
    private static final List<String> HOSTILE = List.of("O'Brien", "back\\slash", "\\'; DROP TABLE x; --", "ends with \\", "-- not a comment",
        "/* nor this */", "? :name $1 $$", "中文 Ünïcødé");

    /** 常量行的一行。 */
    record Text(String value, Integer ord) { }

    @Test
    void 带引号反斜杠和注释符的文本作为字面量和参数都原样往返() {
        List<Text> rows = new ArrayList<>();
        for (int i = 0; i < HOSTILE.size(); i++) rows.add(new Text(HOSTILE.get(i), i));
        Cte<Text> texts = cte("texts", values(Text.class, rows));
        assertEquals(HOSTILE, live.db().list(from(texts).orderBy(t -> t.col(Text::ord)).select(t -> t.col(Text::value))),
            "values(...) writes every row as literals");
        for (String text : HOSTILE) {
            assertEquals(text, live.db().one(from(Lips.class).limit(1).select(l -> val(text))), "a SELECT-list constant is written as a literal");
            assertEquals(List.of(text), live.db().list(from(texts).where(t -> t.col(Text::value).eq(text)).select(t -> t.col(Text::value))),
                "a WHERE value is bound as a parameter");
        }
    }
}

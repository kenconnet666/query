package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.dpdns.zerodep.query.testing.Writes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.hana.Hana.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Needs QUERY_HANA_URL, QUERY_HANA_USER and QUERY_HANA_PASSWORD; skipped otherwise. */
@DisplayName("HANA 实库场景")
class HanaLiveTest extends LiveScenarios {

    @Override protected LiveDatabase connect() { return LiveDatabase.fromEnvironment("HANA"); }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Hana.database(connections, configure);
    }

    @Override protected String text(int length) { return "NVARCHAR(" + length + ")"; }

    /** HANA SQL has no sleep function; SQLScript's built-in SQLSCRIPT_SYNC library does. */
    @BeforeAll
    void createSlowProcedure() throws SQLException {
        live.execute(List.of("-DROP PROCEDURE QUERY_IT.SLOW_ROW", "CREATE PROCEDURE QUERY_IT.SLOW_ROW(IN SECONDS INTEGER) READS SQL DATA AS BEGIN "
            + "USING SQLSCRIPT_SYNC AS SYNCLIB; CALL SYNCLIB:SLEEP_SECONDS(:SECONDS); SELECT 1 AS X FROM DUMMY; END"));
    }

    @Override protected String slowStatement() { return "CALL QUERY_IT.SLOW_ROW(3)"; }

    @Test
    void HANA日期函数在实库上可用() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        int days = db.one(from(Lips.class).limit(1).select(l -> daysBetween(lit(day), addMonths(lit(day), 1))));
        assertEquals(31, days);
        assertEquals("20260105", db.one(from(Lips.class).limit(1).select(l -> toVarchar(addDays(lit(day), 4), "YYYYMMDD"))));
    }

    @Test
    void 带排序的STRING_AGG与提示子句() {
        List<String> rows = db.list(from(Lips.class)
            .with(hint("NO_CS_JOIN"))
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> row((m, d) -> m + "=" + d, l.material(), stringAgg(l.delivery(), ",", l.delivery().asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }

    @Test
    void 分组集合与CUBE() {
        List<String> sets = db.list(from(Lips.class)
            .groupBy(l -> groupingSets(keys(l.material()), keys()))
            .orderBy(l -> orders(grouping(l.material()), l.material()))
            .select(l -> row((m, total) -> m + "=" + total.stripTrailingZeros().toPlainString(), l.material(), sum(l.quantity()))));
        assertEquals(List.of("M1=6", "M2=12", "null=18"), sets);
        List<Long> cells = db.list(from(Lips.class)
            .groupBy(l -> cube(l.material(), l.unit()))
            .select(l -> count()));
        assertEquals(6, cells.size(), "material x unit, material, unit and the grand total");
    }

    @Test
    void GREATEST与LEAST遇到NULL时返回NULL() {
        List<String> rows = db.list(from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> row((d, high, low) -> d + ":" + plain(high) + ":" + plain(low),
                l.delivery(), greatest(l.quantity(), val(new BigDecimal("4"))), least(l.quantity(), val(new BigDecimal("4"))))));
        assertEquals(List.of("D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"), rows);
        assertNull(db.one(from(Lips.class).where(l -> l.delivery().eq("D1"))
            .select(l -> greatest(l.quantity(), nullOf(BigDecimal.class)))));
    }

    @Test
    void TO_INTEGER把文本转成整数() {
        assertEquals(List.of(1, 2, 3, 4, 5), db.list(from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> toInteger(substring(l.delivery(), 2)))));
    }

    @Test
    void 联表更新一行匹配多行时整条语句报错且没有行被改() {
        db.execute(deleteFrom(Writes.Note.class).all());
        db.execute(deleteFrom(Writes.Doc.class).all());
        db.execute(Writes.insertNotes());
        db.execute(insertInto(Writes.Doc.class).values(new Writes.DocRow("D1", "first", 1), new Writes.DocRow("D2", "first", 1)));
        // N1 的标题 first 对上两份文档；PostgreSQL、MySQL 会任取一行，HANA 的 MERGE 报 689
        assertThrows(QueryExecutionException.class, () -> db.execute(updateJoin(Writes.Note.class, Writes.Doc.class, (n, d) -> n.title().eq(d.title()))
            .set((n, d) -> n.status().to(d.id())).all()));
        assertEquals(List.of("open", "open"), db.list(from(Writes.Note.class).orderBy(n -> n.id()).select(n -> n.status())));
    }

    @Test
    void FULL_JOIN保留两侧没有匹配的行且有全局过滤条件时报错() {
        List<Map<String, Object>> rows = db.list(from(Lips.class).unfiltered()
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material()))
            .select((l, m) -> cols(l.delivery(), m.material())));
        assertEquals(12, rows.size());
        assertEquals(2, rows.stream().filter(r -> r.get("delivery") == null).count());
        assertThrows(IllegalStateException.class, () -> db.list(from(Lips.class)
            .fullJoin(Mara.class, (l, m) -> m.material().eq(l.material())).select((l, m) -> l.delivery())));
    }

    @Test
    void 中位数与窗口式的百分位数() {
        assertEquals(List.of("M1:2.0:2:1", "M2:6.0:5:1"), db.list(from(Lips.class)
                .distinct()
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(),
                    percentileCont(0.5, l.quantity()).over(w -> w.partitionBy(l.material())).as("median"),
                    percentileDisc(0.5, l.quantity()).over(w -> w.partitionBy(l.material())).as("lower"))))
            .stream().map(r -> r.get("material") + ":" + r.get("median") + ":" + ((BigDecimal) r.get("lower")).stripTrailingZeros().toPlainString() + ":1").toList());
        assertEquals(List.of("M1:2.0:1", "M2:6.0:1"), db.list(from(Lips.class)
                .groupBy(l -> l.material())
                .orderBy(l -> l.material())
                .select(l -> cols(l.material(), median(l.quantity()).as("median"), corr(l.quantity(), l.quantity()).as("self"))))
            .stream().map(r -> r.get("material") + ":" + r.get("median") + ":" + Math.round((Double) r.get("self"))).toList());
    }

    @Test
    void HANA没有INTERSECT_ALL() {
        Query<String> q = from(Lips.class).select(l -> l.material());
        assertThrows(UnsupportedOperationException.class, () -> db.list(q.combine("INTERSECT ALL", q)));
    }
}

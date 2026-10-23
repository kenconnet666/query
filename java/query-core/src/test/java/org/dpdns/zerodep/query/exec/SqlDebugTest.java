package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.count;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("打印与测试 SQL")
class SqlDebugTest {
    private static final TestDialect DIALECT = new TestDialect();

    @Test
    void 调试SQL把参数写成字面量且不碰字符串里的问号() {
        Rendered r = from(Lips.class)
            .where(l -> l.material().eq("O'Neil").and(l.createdOn().eq(LocalDate.of(2026, 1, 15))))
            .select(l -> lit("a?b").as("mark"))
            .toSql(DIALECT);
        assertEquals("SELECT 'a?b' AS \"mark\"\nFROM QUERY_IT.LIPS LIPS\nWHERE LIPS.MATNR = 'O''Neil' AND LIPS.ERDAT = '20260115'", r.debugSql());
        assertEquals("O'Neil(String), 20260115(String)", r.parameterText(), "parameters as sent to the database, after the converter");
        assertTrue(r.toString().endsWith("\n-- parameters: O'Neil(String), 20260115(String)"), r.toString());
    }

    @Test
    void 打印每条语句的SQL参数行数和耗时() {
        List<String> printed = new ArrayList<>();
        Database db = TestDatabase.dryRun(c -> c.printSql(printed::add));
        db.list(from(Mara.class).where(m -> m.material().eq("M1")).select(m -> m.partNumber()));
        assertEquals(1, printed.size());
        String[] lines = printed.get(0).split("\n");
        assertEquals("==> Preparing: SELECT MARA.MFRPN AS \"partNumber\"", lines[0]);
        assertEquals("==> Parameters: M1(String)", lines[3]);
        assertTrue(lines[4].matches("<== Total: 0, Time: \\d+ ms"), lines[4]);
    }

    @Test
    void 捕获一段代码执行的全部语句且空跑不连数据库() {
        Database db = TestDatabase.dryRun(c -> c.cache(QueryCache.inMemory()));
        Query<Long> total = from(Lips.class).select(l -> count()).cached(Duration.ofMinutes(1));
        List<QueryListener.Event> events = db.capture(() -> {
            db.list(from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> l.material()));
            db.page(from(Mara.class).orderBy(m -> m.material()).select(m -> m.material()), 1, 10);
            db.first(total);
            db.first(total);
        });
        assertEquals(5, events.size(), "list, page (rows and count), and a cached query twice");
        assertEquals(List.of("D1"), events.get(0).statement().parameters());
        assertTrue(events.get(2).statement().sql().startsWith("SELECT COUNT(*) FROM ("), events.get(2).statement().sql());
        assertEquals(List.of(false, true), events.subList(3, 5).stream().map(QueryListener.Event::cached).toList());
        assertEquals(0, db.count(from(Mara.class).select(m -> m.material())), "a dry run counts no rows");
        assertTrue(db.capture(() -> { }).isEmpty());
    }

    @Test
    void 捕获可以嵌套且内层的语句不计入外层() {
        Database db = TestDatabase.dryRun(null);
        List<List<QueryListener.Event>> inner = new ArrayList<>();
        List<QueryListener.Event> outer = db.capture(() -> {
            db.list(from(Mara.class).select(m -> m.material()));
            inner.add(db.capture(() -> db.list(from(Lips.class).select(l -> l.delivery()))));
        });
        assertEquals(1, outer.size());
        assertEquals(1, inner.get(0).size());
        assertTrue(inner.get(0).get(0).statement().sql().contains("LIPS"));
    }
}

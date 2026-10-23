package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.Page;
import org.dpdns.zerodep.query.exec.QueryCache;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.exec.QueryListener;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Client;
import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.EventRow;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.MaraView;
import org.dpdns.zerodep.query.testing.Erp.PartTotal;
import org.dpdns.zerodep.query.testing.Erp.StockRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.dpdns.zerodep.query.Sql.caseWhen;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.count;
import static org.dpdns.zerodep.query.Sql.countDistinct;
import static org.dpdns.zerodep.query.Sql.cte;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.left;
import static org.dpdns.zerodep.query.Sql.negate;
import static org.dpdns.zerodep.query.Sql.plus;
import static org.dpdns.zerodep.query.Sql.replace;
import static org.dpdns.zerodep.query.Sql.right;
import static org.dpdns.zerodep.query.Sql.row;
import static org.dpdns.zerodep.query.Sql.substring;
import static org.dpdns.zerodep.query.Sql.sum;
import static org.dpdns.zerodep.query.Sql.val;
import static org.dpdns.zerodep.query.Sql.values;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same scenarios on every dialect's real database. A dialect module extends this class and
 * supplies the connection, the database instance and its column type names.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class LiveScenarios {
    protected LiveDatabase live;
    protected Database db;

    protected abstract LiveDatabase connect();

    protected abstract Database database(ConnectionProvider connections, Consumer<Database.Config> configure);

    /** The dialect's type for bounded Unicode text. */
    protected abstract String text(int length);

    /** How this dialect quotes an identifier in hand-written DDL and native SQL. */
    protected String q(String identifier) { return '"' + identifier + '"'; }

    /** The column type for a LocalDateTime without time zone conversion. */
    protected String timestamp() { return "TIMESTAMP"; }

    /** A hand-written statement that runs for several seconds before returning a row. */
    protected abstract String slowStatement();

    @BeforeAll
    void createSchema() throws Exception {
        live = connect();
        List<String> sql = new ArrayList<>();
        sql.add("-CREATE SCHEMA " + q("QUERY_IT"));
        for (String t : List.of("MARA", "LIPS", "MAKT", "VBKD", "MARDH", "MCHBH", "EVENTS")) sql.add("-DROP TABLE " + t(t));
        sql.add(table("MARA", c("MANDT", 3), c("MATNR", 40), c("MFRPN", 40), c("MFRNR", 10), c("MTART", 4)));
        sql.add(table("LIPS", c("MANDT", 3), c("VBELN", 10), c("MATNR", 40), c("ERDAT", 8), q("LFIMG") + " DECIMAL(13,3)",
            c("VRKME", 3), c("VGBEL", 10)));
        sql.add(table("MAKT", c("MANDT", 3), c("MATNR", 40), c("SPRAS", 1), c("MAKTX", 40)));
        sql.add(table("VBKD", c("MANDT", 3), c("VBELN", 10)));
        sql.add(table("MARDH", c("MANDT", 3), c("MATNR", 40), c("LFGJA", 4), c("LFMON", 2), q("LABST") + " DECIMAL(13,3)"));
        sql.add(table("MCHBH", c("MANDT", 3), c("MATNR", 40), c("LFGJA", 4), c("LFMON", 2), q("CLABS") + " DECIMAL(13,3)"));
        sql.add(table("EVENTS", q("ID") + " INTEGER", q("ON_DAY") + " DATE", q("AT_TIME") + " " + timestamp()));
        sql.addAll(List.of(
            insert("MARA", "'100', 'M1', 'P1 ', 'MK1', 'ZMAT'"),
            insert("MARA", "'100', 'M2', 'P2', 'MK2', 'ZMAT'"),
            insert("MARA", "'100', 'M3', 'P3', 'MK1', 'ZOTH'"),
            insert("MARA", "'200', 'M1', 'X1', 'MK1', 'ZMAT'"),
            insert("MARA", "'100', 'M4', 'P1', '', 'ZOTH'"),
            insert("MAKT", "'100', 'M1', '1', 'Pump'"),
            insert("MAKT", "'100', 'M2', '2', 'Valve'"),
            insert("MAKT", "'200', 'M1', '1', 'Other'"),
            insert("LIPS", "'100', 'D1', 'M1', '20260115', 3.000, 'EA', 'S1'"),
            insert("LIPS", "'100', 'D2', 'M1', '20260120', 2.000, 'EA', 'S2'"),
            insert("LIPS", "'100', 'D3', 'M2', '20260210', 5.000, 'EA', 'S1'"),
            insert("LIPS", "'100', 'D4', 'M2', '20251231', 7.000, 'EA', 'S1'"),
            insert("LIPS", "'100', 'D5', 'M1', '20260301', 1.000, 'EA', 'S9'"),
            insert("LIPS", "'200', 'D6', 'M1', '20260115', 100.000, 'EA', 'S1'"),
            insert("VBKD", "'100', 'S1'"),
            insert("VBKD", "'100', 'S2'"),
            insert("VBKD", "'200', 'S1'"),
            insert("MARDH", "'100', 'M1', '2026', '01', 10.000"),
            insert("MARDH", "'100', 'M2', '2026', '01', NULL"),
            insert("MARDH", "'100', 'M1', '2025', '12', 4.000"),
            insert("MCHBH", "'100', 'M1', '2026', '02', 6.000"),
            insert("EVENTS", "1, DATE '2024-03-31', TIMESTAMP '2024-03-31 02:30:00'"),
            insert("EVENTS", "2, DATE '2011-12-30', TIMESTAMP '2011-12-30 00:00:00'")));
        live.execute(sql);
        db = database(live.connections(), c -> c.filter(Client.class, r -> r.client().eq("100")));
    }

    private String t(String table) { return q("QUERY_IT") + "." + q(table); }

    private String c(String column, int length) { return q(column) + " " + text(length); }

    private String table(String name, String... columns) { return "CREATE TABLE " + t(name) + " (" + String.join(", ", columns) + ")"; }

    private String insert(String table, String values) { return "INSERT INTO " + t(table) + " VALUES (" + values + ")"; }

    @Test
    void 带CTE和EXISTS的交货汇总在实库上返回正确结果() {
        List<Delivery> rows = db.list(Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null));
        assertEquals(2, rows.size());
        assertDelivery(rows.get(0), "P1", "Pump", "5");
        assertDelivery(rows.get(1), "P2", "P2", "5");
        List<Delivery> mk2 = db.list(Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "MK2"));
        assertEquals(List.of("P2"), mk2.stream().map(Delivery::partNumber).toList());
    }

    @Test
    void 全局过滤器只读当前客户端且可以关闭() {
        long filtered = db.one(from(Lips.class).select(l -> count()));
        long all = db.one(from(Lips.class).unfiltered().select(l -> count()));
        assertEquals(5, filtered);
        assertEquals(6, all);
    }

    @Test
    void 泛型模板生成的UNION合并两张库存表() {
        Set<String> rows = db.list(Scenarios.stock("2026")).stream()
            .map(r -> r.material() + "/" + r.period() + "/" + r.quantity().stripTrailingZeros().toPlainString())
            .collect(Collectors.toSet());
        assertEquals(Set.of("M1/01/10", "M2/01/0", "M1/02/6"), rows);
    }

    @Test
    void 窗口函数取每个物料最新的交货() {
        assertEquals(List.of("D5", "D3"), db.list(Scenarios.latestDeliveries()));
    }

    @Test
    void 按名映射并转换SAP日期() {
        List<LineVO> rows = db.list(Scenarios.lines());
        assertEquals(List.of("D1", "D2", "D3", "D4", "D5"), rows.stream().map(LineVO::getDelivery).toList());
        LineVO first = rows.get(0);
        assertEquals("M1", first.getMaterialNo());
        assertEquals(LocalDate.of(2026, 1, 15), first.getCreatedOn());
        assertEquals("Pump", first.getText());
        assertEquals(null, rows.get(2).getText(), "LEFT JOIN keeps rows without text even with a filter on the joined table");
    }

    @Test
    void SAP日期参数按转换器绑定() {
        assertEquals(List.of("D1"), db.list(from(Lips.class)
            .where(l -> l.createdOn().eq(LocalDate.of(2026, 1, 15)))
            .select(l -> l.delivery())));
    }

    @Test
    void 嵌套结果和Java端计算() {
        List<PartTotal> rows = db.list(Scenarios.partTotals());
        assertEquals(2, rows.size());
        assertEquals("P1", rows.get(0).partNumber().strip());
        assertEquals(0, new BigDecimal("6").compareTo(rows.get(0).amount().quantity()));
        assertEquals(0.0006, rows.get(0).amount().wan(), 1e-12);
    }

    @Test
    void 分页返回当前页和总数() {
        Page<LineVO> page = db.page(Scenarios.lines(), 2, 2);
        assertEquals(5, page.total());
        assertEquals(3, page.totalPages());
        assertEquals(List.of("D3", "D4"), page.rows().stream().map(LineVO::getDelivery).toList());
    }

    @Test
    void 流式读取逐行处理() {
        try (Stream<LineVO> rows = db.stream(Scenarios.lines())) {
            assertEquals(5, rows.count());
        }
    }

    @Test
    void 原生SQL的命名参数和集合展开() {
        List<String> rows = db.sql("SELECT " + q("VBELN") + " AS delivery FROM " + t("LIPS") + " WHERE " + q("MANDT") + " = :client AND "
                + q("VBELN") + " IN (:ids) ORDER BY " + q("VBELN"))
            .bind("client", "100").bind("ids", List.of("D1", "D3")).list(String.class);
        assertEquals(List.of("D1", "D3"), rows);
    }

    @Test
    void 结果缓存命中时不再查询数据库() {
        List<QueryListener.Event> events = new ArrayList<>();
        Database cached = database(live.connections(), c -> c.cache(QueryCache.inMemory()).listener(events::add));
        Query<Long> q = from(Lips.class).select(l -> count()).cached(Duration.ofMinutes(1));
        long first = cached.one(q);
        long second = cached.one(q);
        assertEquals(6, first);
        assertEquals(6, second);
        assertEquals(List.of(false, true), events.stream().map(QueryListener.Event::cached).toList());
        cached.clearCache();
        assertEquals(6, cached.one(q));
        assertEquals(List.of(false, true, false), events.stream().map(QueryListener.Event::cached).toList());
    }

    @Test
    void 查询出错映射出错或提前关闭流时都归还连接() {
        int[] open = {0};
        ConnectionProvider counting = new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException {
                Connection c = live.open();
                open[0]++;
                return c;
            }

            @Override public void release(Connection connection) throws SQLException {
                open[0]--;
                connection.close();
            }
        };
        Database counted = database(counting, null);
        Query<String> deliveries = from(Lips.class).orderBy(l -> l.delivery()).select(l -> l.delivery());
        Query<String> failsOnD3 = from(Lips.class).orderBy(l -> l.delivery()).select(l -> row(d -> {
            if ("D3".equals(d)) throw new IllegalStateException("mapping failed");
            return d;
        }, l.delivery()));
        assertThrows(QueryExecutionException.class, () -> counted.sql("SELECT * FROM NO_SUCH_TABLE").list(String.class));
        assertThrows(IllegalStateException.class, () -> counted.list(failsOnD3));
        try (Stream<String> rows = counted.stream(deliveries)) {
            assertEquals("D1", rows.findFirst().orElseThrow());
            assertEquals(1, open[0], "the stream holds its connection until closed");
        }
        assertThrows(IllegalStateException.class, () -> {
            try (Stream<String> rows = counted.stream(failsOnD3)) {
                rows.forEach(d -> { });
            }
        });
        assertThrows(IllegalStateException.class, () -> counted.forEach(deliveries, d -> {
            throw new IllegalStateException("consumer failed");
        }));
        assertEquals(6, counted.page(deliveries, 1, 2).total());
        assertEquals(0, open[0]);
    }

    @Test
    void 类名是保留字的实体也能查询() {
        assertEquals(List.of("D1", "D2", "D5", "D6"), db.list(SpecCases.reservedAlias()));
    }

    @Test
    void first取第一行或在没有行时为空() {
        assertEquals(Optional.of("D1"), db.first(from(Lips.class).orderBy(l -> l.delivery()).select(l -> l.delivery())));
        assertEquals(Optional.empty(), db.first(from(Lips.class).where(l -> l.delivery().eq("none")).select(l -> l.delivery())));
    }

    @Test
    void 日期和时间戳不受JVM时区影响() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("Europe/Berlin", "Pacific/Apia", "Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                List<EventRow> events = db.list(from(Event.class).orderBy(e -> e.id()).selectAs(EventRow.class));
                assertEquals(LocalDate.of(2024, 3, 31), events.get(0).onDay(), zone);
                assertEquals(LocalDateTime.of(2024, 3, 31, 2, 30), events.get(0).atTime(), zone);
                assertEquals(LocalDate.of(2011, 12, 30), events.get(1).onDay(), zone);
                assertEquals(List.of(2), db.list(from(Event.class)
                    .where(e -> e.onDay().eq(LocalDate.of(2011, 12, 30)))
                    .select(e -> e.id())), zone);
                assertEquals(List.of(1), db.list(from(Event.class)
                    .where(e -> e.atTime().eq(LocalDateTime.of(2024, 3, 31, 2, 30)))
                    .select(e -> e.id())), zone);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    public record Size(String name, Integer ord) { }

    public record SizeTotal(String bucket, BigDecimal total) { }

    private static Expr<String> size(Lips l) {
        Expr<BigDecimal> quantity = l.quantity();
        return caseWhen(quantity.le(new BigDecimal("2")), "small").when(quantity.le(new BigDecimal("5")), "medium").otherwise("large");
    }

    @Test
    void 常量行与带常量的分组在实库上可执行() {
        Cte<Size> sizes = cte("sizes", values(Size.class, List.of(new Size("small", 1), new Size("medium", 2), new Size("large", 3))));
        Cte<SizeTotal> totals = cte("totals", from(Lips.class)
            .groupBy(l -> size(l))
            .select(l -> cols(size(l).as("bucket"), sum(l.quantity()).as("total")))
            .into(SizeTotal.class));
        List<String> rows = db.list(from(sizes)
            .leftJoin(totals, (s, t) -> t.col(SizeTotal::bucket).eq(s.col(Size::name)))
            .orderBy((s, t) -> s.col(Size::ord))
            .select((s, t) -> row((name, total) -> name + "=" + total.stripTrailingZeros().toPlainString(),
                s.col(Size::name), t.col(SizeTotal::total).coalesce(BigDecimal.ZERO))));
        assertEquals(List.of("small=3", "medium=8", "large=7"), rows);
    }

    @Test
    void 文本函数取负与多项求和以及按子串分组() {
        List<String> pieces = db.list(from(Lips.class)
            .where(l -> l.delivery().eq("D1"))
            .select(l -> row((a, b, c, d, e, negative, doubled) -> String.join("|", a, b, c, d, e,
                    negative.stripTrailingZeros().toPlainString(), doubled.stripTrailingZeros().toPlainString()),
                left(l.delivery(), 1),
                substring(l.material(), 2),
                substring(l.unit(), 1, 1),
                right(l.salesOrder(), 1),
                replace(l.unit(), "A", "-"),
                negate(l.quantity()),
                plus(List.of(l.quantity(), l.quantity())))));
        assertEquals(List.of("D|1|E|1|E-|-3|6"), pieces);
        List<String> prefixes = db.list(from(Lips.class)
            .groupBy(l -> substring(l.delivery(), 1, 1))
            .select(l -> row((prefix, lines) -> prefix + "=" + lines, substring(l.delivery(), 1, 1), count())));
        assertEquals(List.of("D=5"), prefixes);
    }

    @Test
    void 列转换按转换后的值读取且可与原值并存() {
        List<String> rows = db.list(from(MaraView.class)
            .groupBy(m -> m.partNumber())
            .orderBy(m -> m.partNumber())
            .select(m -> row((part, makers, stored) -> part + ":" + makers + ":" + stored,
                m.partNumber(), countDistinct(m.maker()), countDistinct(m.storedMaker()))));
        assertEquals(List.of("P1:1:2", "P2:1:1", "P3:1:1"), rows);
        assertEquals(List.of("M4"), db.list(from(MaraView.class).where(m -> m.maker().isNull()).select(m -> m.material())));
    }

    @Test
    void ROLLUP给出小计与总计() {
        List<String> rows = db.list(SpecCases.rollup()).stream()
            .map(r -> r.get("material") + "/" + r.get("unit") + "/" + plain(r.get("total")) + "/" + r.get("level"))
            .toList();
        assertEquals(List.of("M1/EA/6/0", "M1/null/6/0", "M2/EA/12/0", "M2/null/12/0", "null/null/18/1"), rows);
    }

    @Test
    void 按LOCATE找到的位置截取文本() {
        List<String> rows = db.list(SpecCases.textPositions()).stream().map(r -> r.get("text") + ":" + r.get("comma") + ":" + r.get("head") + ":" + r.get("tail")).toList();
        assertEquals(List.of("05:0:05:null", "21,32:3:21:32"), rows);
    }

    @Test
    void 简单CASE与多参数COALESCE() {
        List<String> rows = db.list(SpecCases.simpleCase()).stream().map(r -> r.get("material") + ":" + r.get("kind") + ":" + r.get("maker")).toList();
        assertEquals(List.of("M1:material:MK1", "M2:material:MK2", "M3:other:MK1", "M4:other:P1"), rows);
    }

    @Test
    void FLOOR与CEIL() {
        List<String> rows = db.list(SpecCases.floorCeil()).stream()
            .map(r -> r.get("delivery") + ":" + plain(r.get("down")) + ":" + plain(r.get("up"))).toList();
        assertEquals(List.of("D1:1:2", "D2:1:1", "D3:2:3", "D4:3:4", "D5:0:1"), rows);
    }

    protected static String plain(Object decimal) { return ((BigDecimal) decimal).stripTrailingZeros().toPlainString(); }

    @Test
    void 右侧的UNION保持原有分组() {
        assertEquals(List.of("M1", "M1", "M2"), db.list(SpecCases.unionGrouping()).stream().sorted().toList());
    }

    protected static void assertDelivery(Delivery d, String part, String name, String quantity) {
        assertEquals(part, d.partNumber());
        assertEquals(name, d.name());
        assertTrue(new BigDecimal(quantity).compareTo(d.quantity()) == 0, () -> "quantity " + d.quantity());
    }

    /** Text that would end a string literal early, open a comment or look like a parameter. */
    private static final List<String> HOSTILE = List.of("O'Brien", "back\\slash", "\\'; DROP TABLE x; --", "ends with \\", "-- not a comment",
        "/* nor this */", "? :name $1 $$", "中文 Ünïcødé");

    public record Text(String value, Integer ord) { }

    @Test
    void 带引号反斜杠和注释符的文本作为字面量和参数都原样往返() {
        List<Text> rows = new ArrayList<>();
        for (int i = 0; i < HOSTILE.size(); i++) rows.add(new Text(HOSTILE.get(i), i));
        Cte<Text> texts = cte("texts", values(Text.class, rows));
        assertEquals(HOSTILE, db.list(from(texts).orderBy(t -> t.col(Text::ord)).select(t -> t.col(Text::value))),
            "values(...) writes every row as literals");
        for (String text : HOSTILE) {
            assertEquals(text, db.one(from(Lips.class).limit(1).select(l -> val(text))), "a SELECT-list constant is written as a literal");
            assertEquals(List.of(text), db.list(from(texts).where(t -> t.col(Text::value).eq(text)).select(t -> t.col(Text::value))),
                "a WHERE value is bound as a parameter");
        }
    }

    @Test
    void 超过超时的语句在数据库端被取消且连接仍可用() {
        long start = System.nanoTime();
        var own = assertThrows(QueryExecutionException.class, () -> db.sql(slowStatement()).timeout(Duration.ofSeconds(1)).list(String.class));
        Database limited = database(live.connections(), c -> c.queryTimeout(Duration.ofSeconds(1)));
        var byDefault = assertThrows(QueryExecutionException.class, () -> limited.sql(slowStatement()).list(String.class));
        assertTrue(own.isTimeout() && byDefault.isTimeout(), "the dialect recognises its database's timeout error: " + own.getCause());
        assertFalse(assertThrows(QueryExecutionException.class, () -> db.sql("SELECT * FROM " + t("NO_SUCH_TABLE")).list(String.class)).isTimeout());
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(elapsed < 5000, "both statements were cancelled after about a second instead of running to the end: " + elapsed + " ms");
        assertEquals(5, limited.count(from(Lips.class).where(l -> l.client().eq("100")).select(l -> l.delivery())));
        try (Stream<String> streamed = limited.stream(from(Lips.class).where(l -> l.client().eq("100")).select(l -> l.delivery())
            .timeout(Duration.ofSeconds(5)))) {
            assertEquals(5, streamed.count(), "a stream applies its timeout and still reads every row");
        }
    }
}

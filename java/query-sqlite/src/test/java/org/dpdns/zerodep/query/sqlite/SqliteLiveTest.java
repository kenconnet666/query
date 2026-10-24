package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.sqlite.Sqlite.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** SQLite 不需要服务：库是 target 下的两个文件，每个连接把 QUERY_IT 库 ATTACH 进来，随单元测试一起跑。 */
@DisplayName("SQLite 实库场景")
class SqliteLiveTest extends LiveScenarios {

    @Override protected LiveDatabase connect() {
        Path dir = Path.of("target", "sqlite-it");
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(dir.resolve("main.db"));
            Files.deleteIfExists(dir.resolve("query_it.db"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String attached = dir.resolve("query_it.db").toAbsolutePath().toString().replace("'", "''");
        return new LiveDatabase("jdbc:sqlite:" + dir.resolve("main.db").toAbsolutePath(), null, null,
            List.of("ATTACH DATABASE '" + attached + "' AS QUERY_IT", "PRAGMA foreign_keys = ON"));
    }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Sqlite.database(connections, configure);
    }

    @Override protected String text(int length) { return "VARCHAR(" + length + ")"; }

    @Override protected String identity() { return "INTEGER PRIMARY KEY AUTOINCREMENT"; }

    /** SQLite 没有 sleep，数一亿行要几秒。 */
    @Override protected String slowStatement() {
        return "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 20000000) SELECT COUNT(*) FROM r";
    }

    /** SQLite 没有 ROLLUP、加锁读和 LPAD 函数。 */
    @Override protected boolean supports(String feature) { return !List.of("rollup", "for-update", "lpad").contains(feature); }

    /** 日期和时间戳按文本存，去掉 DATE、TIMESTAMP 前缀。 */
    @Override protected String literals(String values) { return values.replace("TIMESTAMP '", "'").replace("DATE '", "'"); }

    @Test
    void GLOB区分大小写而LIKE不区分() {
        long like = db.one(from(Lips.class).unfiltered().where(l -> like(l.unit(), "ea")).select(l -> count()));
        long lower = db.one(from(Lips.class).unfiltered().where(l -> glob(l.unit(), "ea")).select(l -> count()));
        long pattern = db.one(from(Lips.class).unfiltered().where(l -> glob(l.unit(), "E?")).select(l -> count()));
        assertEquals(List.of(6L, 0L, 6L), List.of(like, lower, pattern), "LIKE ignores ASCII case on SQLite, GLOB does not");
    }

    @Test
    void 中位数与百分位数() {
        Map<String, Object> r = db.one(from(Lips.class).where(l -> l.material().eq("M1"))
            .select(l -> cols(median(l.quantity()).as("middle"), percentileCont(0.5, l.quantity()).as("p50"), percentileDisc(1, l.quantity()).as("top"))));
        assertEquals(2.0, ((Number) r.get("middle")).doubleValue());
        assertEquals(2.0, ((Number) r.get("p50")).doubleValue());
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) r.get("top")));
    }

    @Test
    void 小数按数值比较且有效数字在十五位以内时原样读回() {
        assertEquals(List.of("D6"), db.list(from(Lips.class).unfiltered().where(l -> l.quantity().gt(new BigDecimal("9.5"))).select(l -> l.delivery())),
            "NUMERIC affinity compares as numbers, not text");
        assertEquals(0, new BigDecimal("100.000").compareTo(db.one(from(Lips.class).unfiltered().where(l -> l.delivery().eq("D6")).select(l -> l.quantity()))));
    }
}

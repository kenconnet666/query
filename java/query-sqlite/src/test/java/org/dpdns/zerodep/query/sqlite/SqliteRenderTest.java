package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Schemas;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.dpdns.zerodep.query.sqlite.Sqlite.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SQLite 渲染")
class SqliteRenderTest {

    @Test
    void 表别名写AS且标识符不加引号() {
        String sql = from(Lips.class).select(l -> l.delivery()).toSql(SqliteDialect.INSTANCE).sql();
        assertEquals("SELECT lips.VBELN AS delivery\nFROM QUERY_IT.LIPS AS lips", sql);
    }

    @Test
    void 日期字面量写成文本且除法先转成浮点() {
        String sql = from(Lips.class)
            .select(l -> cols(lit(LocalDate.of(2026, 1, 31)).as("day"), l.quantity().divide(val(new BigDecimal("2"))).as("half"), l.createdOn().cast(org.dpdns.zerodep.query.type.CastType.date()).as("d")))
            .toSql(SqliteDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT '2026-01-31' AS day, (CAST(lips.LFIMG AS REAL) / 2) AS half, date(lips.ERDAT) AS d"), sql);
    }

    @Test
    void 只有offset时写LIMIT负一() {
        String sql = from(Lips.class).orderBy(l -> l.delivery()).select(l -> l.delivery()).offset(5).toSql(SqliteDialect.INSTANCE).sql();
        assertTrue(sql.endsWith("\nLIMIT -1 OFFSET 5"), sql);
    }

    @Test
    void SQLite不支持的能力渲染时报错() {
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class).with(forUpdate(Lock.WAIT)).select(l -> l.delivery()).toSql(SqliteDialect.INSTANCE));
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class).groupByRollup(l -> keys(l.material())).select(l -> l.quantity().sum()).toSql(SqliteDialect.INSTANCE));
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class).select(l -> grouping(l.material())).toSql(SqliteDialect.INSTANCE));
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class).select(l -> l.quantity().stddevSamp().over(w -> w.partitionBy(l.material()))).toSql(SqliteDialect.INSTANCE));
    }

    @Test
    void SQLite专属操作不能在别的方言上渲染() {
        Query<String> q = from(Lips.class).where(l -> sqlite(l.unit()).glob("E*")).select(l -> l.delivery());
        assertThrows(UnsupportedOperationException.class, () -> q.toSql(new TestDialect()));
        Query<Double> m = from(Lips.class).select(l -> sqlite(l.quantity()).median());
        assertThrows(UnsupportedOperationException.class, () -> m.toSql(new TestDialect()));
    }

    @Test
    void 需要扩展的函数第一次渲染时警告一次() {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }

            @Override public void flush() { }

            @Override public void close() { }
        };
        Logger logger = Logger.getLogger("org.dpdns.zerodep.query.sqlite");
        logger.addHandler(handler);
        try {
            SqliteDialect dialect = new SqliteDialect();
            for (int i = 0; i < 2; i++) from(Lips.class).select(l -> l.quantity().sqrt()).toSql(dialect);
            assertEquals(1, records.stream().filter(r -> r.getMessage().startsWith("SQRT()")).count(), () -> records.toString());
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void 按声明建表的语句() {
        List<String> statements = Sqlite.database(ConnectionProvider.dryRun(), null).schema().createTable(Schemas.SItem.class);
        assertEquals(List.of(
            "CREATE TABLE QUERY_IT.S_ITEM (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, CODE VARCHAR(20) NOT NULL, CREATED_ON DATE, OWNER_ID INTEGER, PRICE DECIMAL(13,3), STATUS VARCHAR(10) DEFAULT 'open' NOT NULL, TITLE VARCHAR(40), CONSTRAINT FK_S_ITEM_OWNER FOREIGN KEY (OWNER_ID) REFERENCES S_OWNER (ID))",
            "CREATE UNIQUE INDEX QUERY_IT.IX_S_ITEM_CODE ON S_ITEM (CODE)"), statements);
    }
}

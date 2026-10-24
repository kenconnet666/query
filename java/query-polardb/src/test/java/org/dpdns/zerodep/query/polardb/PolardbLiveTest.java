package org.dpdns.zerodep.query.polardb;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.mysql.MysqlLiveTest;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.polardb.Polardb.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 在 PolarDB 上跑 MySQL 方言的全部实库场景（继承 {@link MysqlLiveTest}），再加 PolarDB 专属的闪回查询。
 * Needs QUERY_POLARDB_URL, QUERY_POLARDB_USER and QUERY_POLARDB_PASSWORD; skipped otherwise.
 * 闪回查询要求服务器开着 undo 保留（infra/polardb 的 my.cnf 设了 innodb_undo_retention）。
 */
@DisplayName("PolarDB 实库场景")
class PolardbLiveTest extends MysqlLiveTest {
    @Override protected LiveDatabase connect() { return LiveDatabase.fromEnvironment("POLARDB"); }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Polardb.database(connections, configure);
    }

    /** 闪回查询用的表。 */
    @Table(schema = "QUERY_IT", value = "P_STOCK")
    public interface Stock extends Entity {
        /** 编号。 */
        @Column("ID") Col<Long> id();

        /** 数量。 */
        @Column("QTY") Col<Integer> qty();
    }

    private String table(String name) { return q("QUERY_IT") + "." + q(name); }

    @Test
    void 闪回查询读到改动之前的行且全局过滤器照常生效() throws SQLException, InterruptedException {
        live.execute(List.of("-DROP TABLE " + table("P_STOCK"), "CREATE TABLE " + table("P_STOCK") + " (ID BIGINT PRIMARY KEY, QTY INT NOT NULL)",
            "INSERT INTO " + table("P_STOCK") + " VALUES (1, 10), (2, 20)"));
        // 引擎按 innodb_scn_history_interval（测试环境 1 秒）记录时刻到快照的对应：闪回的时刻要晚于建表、早于改动，各隔开一个多间隔
        Thread.sleep(1500);
        LocalDateTime before = db.sql("SELECT NOW(6)").one(LocalDateTime.class);
        Thread.sleep(1500);
        live.execute(List.of("UPDATE " + table("P_STOCK") + " SET QTY = 9 WHERE ID = 1", "DELETE FROM " + table("P_STOCK") + " WHERE ID = 2"));

        assertEquals(List.of(Map.of("id", 1L, "qty", 10), Map.of("id", 2L, "qty", 20)),
            db.list(fromAsOf(Stock.class, before).orderBy(s -> s.id()).select(s -> cols(s.id(), s.qty()))));
        assertEquals(List.of(9), db.list(from(Stock.class).select(s -> s.qty())));
        Long large = db.one(fromAsOf(Stock.class, before).where(s -> s.qty().gt(15)).select(s -> count()));
        assertEquals(1L, large);
    }

    @Test
    void 闪回到建表之前时数据库报错() throws SQLException {
        live.execute(List.of("-DROP TABLE " + table("P_STOCK"), "CREATE TABLE " + table("P_STOCK") + " (ID BIGINT PRIMARY KEY, QTY INT NOT NULL)"));
        assertThrows(RuntimeException.class, () -> db.list(fromAsOf(Stock.class, LocalDateTime.of(2000, 1, 1, 0, 0)).select(s -> s.id())));
    }

    @Test
    void 专属写法在别的方言上报错() {
        Database mysql = org.dpdns.zerodep.query.mysql.Mysql.database(ConnectionProvider.dryRun(), null);
        assertThrows(UnsupportedOperationException.class, () -> mysql.render(fromAsOf(Stock.class, LocalDateTime.of(2026, 1, 1, 0, 0)).select(s -> s.id())));
    }
}

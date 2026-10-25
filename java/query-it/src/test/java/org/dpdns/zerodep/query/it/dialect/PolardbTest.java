package org.dpdns.zerodep.query.it.dialect;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.mysql.Mysql;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.column.ColBigint;
import org.dpdns.zerodep.query.table.column.ColInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.it.support.Db.POLARDB;
import static org.dpdns.zerodep.query.polardb.Polardb.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PolarDB 专属的写法（{@code Polardb} 门面）：闪回查询和库存提示；MySQL 的写法在 {@link MysqlTest} 里，也在 PolarDB 上跑。
 * 闪回查询要求服务器开着 undo 保留（infra/polardb 的 my.cnf 设了 innodb_undo_retention），每个测试先手写重建 P_STOCK。
 */
@LiveTest(only = POLARDB)
@DisplayName("PolarDB 专属写法")
class PolardbTest {
    @Parameter Live live;

    /** 库存：闪回查询和库存提示用。 */
    @Table(schema = "QUERY_IT", value = "P_STOCK")
    interface Stock extends Entity {
        /** 编号。 */
        @Column("ID") ColBigint<Long> id();

        /** 数量。 */
        @Column("QTY") ColInteger<Integer> qty();
    }

    /** 删掉并重建 P_STOCK，写入 rows（形如 {@code (1, 10), (2, 20)}，为空时不写）。 */
    private void stock(String rows) {
        String table = live.table("P_STOCK");
        live.execute("-DROP TABLE " + table, "CREATE TABLE " + table + " (ID BIGINT PRIMARY KEY, QTY INT NOT NULL)");
        if (!rows.isEmpty()) live.execute("INSERT INTO " + table + " VALUES " + rows);
    }

    @Test
    void 闪回查询读到改动之前的行且全局过滤器照常生效() throws InterruptedException {
        stock("(1, 10), (2, 20)");
        // 引擎按 innodb_scn_history_interval（测试环境 1 秒）记录时刻到快照的对应：闪回的时刻要晚于建表、早于改动，各隔开一个多间隔
        Thread.sleep(1500);
        LocalDateTime before = live.db().sql("SELECT NOW(6)").one(LocalDateTime.class);
        Thread.sleep(1500);
        live.execute("UPDATE " + live.table("P_STOCK") + " SET QTY = 9 WHERE ID = 1", "DELETE FROM " + live.table("P_STOCK") + " WHERE ID = 2");
        assertEquals(List.of(Map.of("id", 1L, "qty", 10), Map.of("id", 2L, "qty", 20)),
            live.db().list(fromAsOf(Stock.class, before).orderBy(s -> s.id()).select(s -> cols(s.id(), s.qty()))));
        assertEquals(List.of(9), live.db().list(from(Stock.class).select(s -> s.qty())), "now");
        assertEquals(1L, (long) live.db().one(fromAsOf(Stock.class, before).where(s -> s.qty().gt(15)).select(s -> count())));
    }

    @Test
    void 闪回到建表之前时数据库报错() {
        stock("");
        assertThrows(RuntimeException.class, () -> live.db().list(fromAsOf(Stock.class, LocalDateTime.of(2000, 1, 1, 0, 0)).select(s -> s.id())));
    }

    @Test
    void 库存提示改到预期行数时提交整个事务否则整个事务回滚() {
        stock("(1, 1), (2, 5)");
        // 扣到 0 为止：第一次改到一行，提交；第二次条件不满足，改到 0 行，数据库报错并回滚同一事务里之前的改动
        live.db().transaction(() -> live.db().execute(update(Stock.class).with(s -> inventory(1)).set(s -> s.qty().to(s.qty().minus(val(1))))
            .where(s -> { s.id().eq(1L); s.qty().gt(0); })));
        assertThrows(RuntimeException.class, () -> live.db().transaction(() -> {
            live.db().execute(update(Stock.class).set(s -> s.qty().to(0)).where(s -> s.id().eq(2L)));
            live.db().execute(update(Stock.class).with(s -> inventory(1)).set(s -> s.qty().to(s.qty().minus(val(1))))
                .where(s -> { s.id().eq(1L); s.qty().gt(0); }));
        }));
        assertEquals(List.of(0, 5), live.db().list(from(Stock.class).orderBy(s -> s.id()).select(s -> s.qty())));
        assertThrows(RuntimeException.class, () -> live.db().execute(update(Stock.class).with(s -> inventory(1)).set(s -> s.qty().to(9)).where(s -> s.id().eq(2L))),
            "自动提交时不能用");
    }

    @Test
    void 专属写法在MySQL方言上报错() {
        Database mysql = Mysql.database(ConnectionProvider.dryRun(), null);
        assertThrows(UnsupportedOperationException.class, () -> mysql.render(fromAsOf(Stock.class, LocalDateTime.of(2026, 1, 1, 0, 0)).select(s -> s.id())));
        assertThrows(UnsupportedOperationException.class, () -> mysql.render(update(Stock.class).with(s -> inventory(1)).set(s -> s.qty().to(1)).where(s -> s.id().eq(1L))));
    }
}

package org.dpdns.zerodep.query.it;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.hana.HanaDialect;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.mysql.MysqlDialect;
import org.dpdns.zerodep.query.postgres.PostgresDialect;
import org.dpdns.zerodep.query.sqlite.SqliteDialect;
import org.dpdns.zerodep.query.testing.Erp.Client;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.it.support.Db.HANA;
import static org.dpdns.zerodep.query.it.support.Db.POSTGRES;
import static org.dpdns.zerodep.query.it.support.Db.SQLITE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 通用门面 {@code Sql.database(...)}：只给连接，由连接报告的产品名选方言（各方言包登记的 {@code DialectProvider}）。
 * PolarDB 报告的产品名是 MySQL，所以得到的是 MySQL 方言。
 */
@LiveTest
@DisplayName("通用门面")
class PortableFacadeTest {
    @Parameter Live live;

    @Test
    void 按连接识别出本库的方言后可以直接查询() {
        Database db = Sql.database(live.connections(), c -> c.filter(Client.class, r -> r.client().eq("100")));
        Class<?> expected = live.is(HANA) ? HanaDialect.class : live.is(POSTGRES) ? PostgresDialect.class : live.is(SQLITE) ? SqliteDialect.class
            : MysqlDialect.class;
        assertEquals(expected, db.dialect().getClass());
        assertEquals(5L, db.count(from(Lips.class).select(l -> l.delivery())));
    }
}

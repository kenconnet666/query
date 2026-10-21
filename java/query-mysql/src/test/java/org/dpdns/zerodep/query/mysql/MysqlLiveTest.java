package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.LiveDatabase;
import org.dpdns.zerodep.query.testing.LiveScenarios;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.mysql.Mysql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Needs QUERY_MYSQL_URL, QUERY_MYSQL_USER and QUERY_MYSQL_PASSWORD; skipped otherwise. */
@DisplayName("MySQL 实库场景")
class MysqlLiveTest extends LiveScenarios {

    @Override protected LiveDatabase connect() { return LiveDatabase.fromEnvironment("MYSQL"); }

    @Override protected Database database(LiveDatabase live, Consumer<Database.Config> configure) {
        return Mysql.database(live.connections(), configure);
    }

    @Override protected String text(int length) { return "VARCHAR(" + length + ")"; }

    @Override protected String q(String identifier) { return "`" + identifier + "`"; }

    /** TIMESTAMP converts through the session time zone and ends in 2038; DATETIME is a plain local value. */
    @Override protected String timestamp() { return "DATETIME(6)"; }

    @Test
    void MySQL日期函数与GROUP_CONCAT() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        int days = db.one(from(Lips.class).limit(1).selectValue(l -> dateDiff(lit(LocalDate.of(2026, 2, 1)), lit(day))));
        assertEquals(31, days);
        List<String> rows = db.list(from(Lips.class)
            .with(maxExecutionTime(5000))
            .groupBy(l -> keys(l.col(Lips::getMaterial)))
            .orderBy(l -> orders(l.col(Lips::getMaterial).asc()))
            .select((r, l) -> r.get(l, Lips::getMaterial) + "=" + r.get(groupConcat(l.col(Lips::getDelivery), ",", l.col(Lips::getDelivery).asc()))));
        assertEquals(List.of("M1=D1,D2,D5", "M2=D3,D4"), rows);
    }

    @Test
    void 原生SQL不在反引号和反斜杠转义的字符串里找参数() {
        List<String> rows = db.sql("SELECT CONCAT('it\\'s :no', :x) AS `v:w`").bind("x", "!").list(String.class);
        assertEquals(List.of("it's :no!"), rows);
    }
}

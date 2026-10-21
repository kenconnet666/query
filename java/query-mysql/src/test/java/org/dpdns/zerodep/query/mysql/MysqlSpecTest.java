package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.mysql.Mysql.*;

@DisplayName("MySQL 渲染金样例")
class MysqlSpecTest extends RenderSpec {
    @Override protected String dialect() { return "mysql"; }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Mysql.database(connections, configure);
    }

    @Override protected Map<String, SpecCase> dialectCases() {
        return Map.of(
            "mysql-functions", SpecCase.plain(() -> from(Lips.class)
                .with(maxExecutionTime(2000))
                .groupBy(l -> keys(l.col(Lips::getMaterial)))
                .select((r, l) -> Arrays.asList(
                    r.get(l, Lips::getMaterial),
                    r.get(dateDiff(lit(LocalDate.of(2026, 2, 1)), lit(LocalDate.of(2026, 1, 1))).as("days")),
                    r.get(dateFormat(lit(LocalDate.of(2026, 1, 31)), "%Y%m%d").as("formatted")),
                    r.get(groupConcat(l.col(Lips::getDelivery), "|", l.col(Lips::getDelivery).asc()).as("deliveries"))))));
    }
}

package org.dpdns.zerodep.query.polardb;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.mysql.MysqlSpecTest;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.junit.jupiter.api.DisplayName;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.polardb.Polardb.*;

/**
 * PolarDB 的金样例：写法沿用 MySQL，共享用例和 MySQL 方言的用例都和 MySQL 的金样例比对（没有自己的文件）；
 * 只有 PolarDB 专属的写法有 {@code *.polardb.sql}。
 */
@DisplayName("PolarDB 渲染金样例")
class PolardbSpecTest extends MysqlSpecTest {
    @Override protected String dialect() { return "polardb"; }

    @Override protected String fallbackDialect() { return "mysql"; }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Polardb.database(connections, configure);
    }

    @Override protected Map<String, SpecCase> dialectCases() {
        Map<String, SpecCase> cases = new LinkedHashMap<>(super.dialectCases());
        cases.put("polardb-flashback", SpecCase.filtered(() -> fromAsOf(Lips.class, LocalDateTime.of(2026, 1, 15, 8, 30))
            .where(l -> l.material().eq("M1"))
            .orderBy(l -> l.delivery())
            .select(l -> cols(l.delivery(), l.quantity()))));
        cases.put("polardb-inventory", SpecCase.plain(() -> update(Lips.class)
            .with(l -> inventory(1))
            .with(l -> noIndex(l))
            .set(l -> l.quantity().to(minus(l.quantity(), val(java.math.BigDecimal.ONE))))
            .where(l -> all(l.delivery().eq("D1"), l.quantity().gt(java.math.BigDecimal.ZERO)))));
        return cases;
    }
}

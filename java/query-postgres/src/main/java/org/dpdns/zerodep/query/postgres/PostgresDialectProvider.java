package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.dialect.DialectProvider;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.util.function.Consumer;

/** 向通用门面 {@code Sql.database(...)} 登记 PostgreSQL 方言。 */
public final class PostgresDialectProvider implements DialectProvider {
    /** 驱动报告的产品名是 {@code PostgreSQL}。 */
    @Override public boolean accepts(String productName) { return "PostgreSQL".equals(productName); }

    /** 与 {@link Postgres#database(ConnectionProvider, Consumer)} 相同。 */
    @Override public Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Postgres.database(connections, configure);
    }
}

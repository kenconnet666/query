package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.dialect.DialectProvider;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.util.function.Consumer;

/** 向通用门面 {@code Sql.database(...)} 登记 SQLite 方言（sqlite-jdbc 驱动）。 */
public final class SqliteDialectProvider implements DialectProvider {
    /** 驱动报告的产品名是 {@code SQLite}。 */
    @Override public boolean accepts(String productName) { return "SQLite".equals(productName); }

    /** 与 {@link Sqlite#database(ConnectionProvider, Consumer)} 相同。 */
    @Override public Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Sqlite.database(connections, configure);
    }
}

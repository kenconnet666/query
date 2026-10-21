package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/** A database instance that only renders; any attempt to connect fails the test. */
public final class TestDatabase extends Database {
    private static final ConnectionProvider NO_CONNECTIONS = new ConnectionProvider() {
        @Override public Connection acquire() throws SQLException { throw new SQLException("TestDatabase never connects"); }

        @Override public void release(Connection connection) { }
    };

    public TestDatabase(Consumer<Config> configure) {
        super(new TestDialect(), NO_CONNECTIONS, configure);
    }
}

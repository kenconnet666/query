package org.dpdns.zerodep.query.exec;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Where statements get their connection. The core never opens or commits transactions itself; a
 * Spring adapter, for example, acquires through {@code DataSourceUtils} to join the current one.
 */
public interface ConnectionProvider {
    Connection acquire() throws SQLException;

    void release(Connection connection) throws SQLException;

    /**
     * Called for each statement after the database instance has set its query timeout and before
     * it runs; a Spring adapter applies the remaining transaction timeout here.
     */
    default void prepared(PreparedStatement statement) throws SQLException { }

    /** Plain DataSource: a pooled connection per statement, closed (returned) afterwards. */
    static ConnectionProvider of(DataSource dataSource) {
        return new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException { return dataSource.getConnection(); }

            @Override public void release(Connection connection) throws SQLException { connection.close(); }
        };
    }
}

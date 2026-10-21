package org.dpdns.zerodep.query.spring;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Connections through Spring's {@link DataSourceUtils}. Inside a {@code @Transactional} method a
 * query uses the transaction's connection, so it sees uncommitted writes of MyBatis, JdbcTemplate or
 * JPA in the same transaction and rolls back with them; outside one, each statement borrows a
 * connection and returns it. A routing DataSource (dynamic data sources) works unchanged.
 * A transaction timeout ({@code @Transactional(timeout = 30)}) caps each statement with the time
 * the transaction has left, as JdbcTemplate does, and wins over the database instance's queryTimeout.
 *
 * <pre>{@code
 * @Bean HanaDatabase hana(DataSource dataSource) {
 *     return Hana.database(SpringConnections.of(dataSource), c -> c.filter(...));
 * }
 * }</pre>
 */
public final class SpringConnections implements ConnectionProvider {
    private final DataSource dataSource;

    private SpringConnections(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public static ConnectionProvider of(DataSource dataSource) { return new SpringConnections(dataSource); }

    @Override public Connection acquire() { return DataSourceUtils.getConnection(dataSource); }

    /** A no-op inside a transaction: the transaction manager returns the connection when it ends. */
    @Override public void release(Connection connection) { DataSourceUtils.releaseConnection(connection, dataSource); }

    @Override public void prepared(PreparedStatement statement) throws SQLException { DataSourceUtils.applyTransactionTimeout(statement, dataSource); }
}

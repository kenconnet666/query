package org.dpdns.zerodep.query.spring;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * 通过 Spring 的 {@link DataSourceUtils} 取连接。在 {@code @Transactional} 方法内，查询使用事务的连接，
 * 因此能看到同一事务中 MyBatis、JdbcTemplate 或 JPA 尚未提交的写入，也随它们一起回滚；
 * 在事务之外，每条语句借一个连接，用完归还。路由 DataSource（动态数据源）无需改动即可使用。
 * 事务超时（{@code @Transactional(timeout = 30)}）会把每条语句的超时限制为事务剩余的时间，
 * 与 JdbcTemplate 的做法相同，并且优先于数据库实例的 queryTimeout。
 *
 * <pre>{@code
 * @Bean HanaDatabase hana(DataSource dataSource) {
 *     return Hana.database(SpringConnections.of(dataSource), c -> c.filter(...));
 * }
 * }</pre>
 */
public final class SpringConnections implements ConnectionProvider {
    private final DataSource dataSource;

    /** @param dataSource 取连接的数据源，不能为 null */
    private SpringConnections(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 创建基于 {@code dataSource} 的连接提供者。 */
    public static ConnectionProvider of(DataSource dataSource) { return new SpringConnections(dataSource); }

    /** 取当前事务绑定的连接；没有事务时从数据源借一个。 */
    @Override public Connection acquire() { return DataSourceUtils.getConnection(dataSource); }

    /** 事务内什么也不做：事务结束时由事务管理器归还连接。 */
    @Override public void release(Connection connection) { DataSourceUtils.releaseConnection(connection, dataSource); }

    /** 若处于带超时的事务中，把事务剩余的时间套用为语句的查询超时。 */
    @Override public void prepared(PreparedStatement statement) throws SQLException { DataSourceUtils.applyTransactionTimeout(statement, dataSource); }

    /** 事务由 Spring 管理：用 {@code @Transactional} 或 {@code TransactionTemplate}，不用 {@code Database.transaction(...)}。 */
    @Override public boolean managesTransactions() { return true; }
}

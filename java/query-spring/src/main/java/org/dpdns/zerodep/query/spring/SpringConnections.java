package org.dpdns.zerodep.query.spring;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * 通过 Spring 的 {@link DataSourceUtils} 取连接。在 {@code @Transactional} 方法内，查询使用事务的连接，
 * 因此能看到同一事务中 MyBatis、JdbcTemplate 或 JPA 尚未提交的写入，也随它们一起回滚；
 * 在事务之外，每条语句借一个连接，用完归还。路由 DataSource（动态数据源）无需改动即可使用。
 * 事务超时（{@code @Transactional(timeout = 30)}）会把每条语句的超时限制为事务剩余的时间，
 * 与 JdbcTemplate 的做法相同，并且优先于数据库实例的 queryTimeout。
 *
 * <pre>{@code
 * @Bean HanaDatabase hana(DataSource dataSource, PlatformTransactionManager transactionManager) {
 *     return Hana.database(SpringConnections.of(dataSource, transactionManager), c -> c.filter(...));
 * }
 * }</pre>
 *
 * 给了事务管理器时，{@code db.transaction(...)} 和多行写入的自动事务交给它执行（传播行为 REQUIRED：已有
 * {@code @Transactional} 时加入，没有时开启），建在本库上的库因此能自己划定事务边界；事务管理器必须管理同一个 DataSource。
 * 只给 DataSource 时 {@code db.transaction(...)} 报错，事务只能用 {@code @Transactional}。
 */
public final class SpringConnections implements ConnectionProvider {
    private final DataSource dataSource;
    /** 执行 {@link #transaction} 的模板；没有事务管理器时为 null。 */
    private final TransactionTemplate transactions;

    /**
     * @param dataSource         取连接的数据源，不能为 null
     * @param transactionManager 管理该数据源的事务管理器；为 null 时不能开启事务
     */
    private SpringConnections(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    /** 创建基于 {@code dataSource} 的连接提供者；事务只能用 {@code @Transactional}。 */
    public static ConnectionProvider of(DataSource dataSource) { return new SpringConnections(dataSource, null); }

    /** 创建基于 {@code dataSource} 的连接提供者，{@code db.transaction(...)} 由 {@code transactionManager} 执行。 */
    public static ConnectionProvider of(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new SpringConnections(dataSource, Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** 取当前事务绑定的连接；没有事务时从数据源借一个。 */
    @Override public Connection acquire() { return DataSourceUtils.getConnection(dataSource); }

    /** 事务内什么也不做：事务结束时由事务管理器归还连接。 */
    @Override public void release(Connection connection) { DataSourceUtils.releaseConnection(connection, dataSource); }

    /** 若处于带超时的事务中，把事务剩余的时间套用为语句的查询超时。 */
    @Override public void prepared(PreparedStatement statement) throws SQLException { DataSourceUtils.applyTransactionTimeout(statement, dataSource); }

    /** 事务由 Spring 管理：本库从不自己开启或提交事务。 */
    @Override public boolean managesTransactions() { return true; }

    /** 给了事务管理器时可以开启事务。 */
    @Override public boolean startsTransactions() { return transactions != null; }

    /** 用事务管理器执行 work（传播行为 REQUIRED）：已有事务时加入，没有时开启；抛出异常时回滚。 */
    @Override public <T> T transaction(Supplier<T> work) {
        if (transactions == null) return ConnectionProvider.super.transaction(work);
        return transactions.execute(status -> work.get());
    }
}

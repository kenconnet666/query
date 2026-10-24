package org.dpdns.zerodep.query.exec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * 语句从哪里取得连接。核心自己从不开启或提交事务；例如 Spring 适配器通过 {@code DataSourceUtils}
 * 取连接，从而加入当前事务。
 */
public interface ConnectionProvider {
    /** 取出一个连接，供一条语句使用。 */
    Connection acquire() throws SQLException;

    /** 归还 {@link #acquire()} 取出的连接；语句无论成败都会调用。 */
    void release(Connection connection) throws SQLException;

    /**
     * 每条语句在数据库实例设置好查询超时之后、执行之前调用；Spring 适配器在这里套用事务剩余的超时。
     */
    default void prepared(PreparedStatement statement) throws SQLException { }

    /**
     * 连接来源是否自己管理事务（例如 Spring 适配器加入外层的 {@code @Transactional}）；为 true 时本库从不自己开启、
     * 提交事务：{@link #startsTransactions()} 为 true 时 {@code Database.transaction(...)} 交给 {@link #transaction}，否则报错。
     */
    default boolean managesTransactions() { return false; }

    /**
     * 自己管理事务的连接来源能否按需开启事务（Spring 适配器给了事务管理器时为 true）。为 true 时
     * {@code Database.transaction(...)} 和多行写入的自动事务都交给 {@link #transaction}。
     */
    default boolean startsTransactions() { return false; }

    /**
     * 在连接来源自己的事务里执行 work：已有事务时加入，没有时开启，正常结束时提交、抛出异常时回滚。
     * 只在 {@link #managesTransactions()} 与 {@link #startsTransactions()} 都为 true 时调用。
     */
    default <T> T transaction(Supplier<T> work) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot start transactions");
    }

    /**
     * 空跑：不连接任何数据库，每条查询都得到空结果（计数为 0），但照常渲染、打印和触发监听器。
     * 配合 {@code Database.capture(...)}，不用数据库也能测试代码发出的 SQL：
     * {@code Hana.database(ConnectionProvider.dryRun(), c -> c.printSql())}。
     */
    static ConnectionProvider dryRun() { return DryRun.provider(); }

    /** 普通 DataSource：每条语句取一个池化连接，用完关闭（即归还）。 */
    static ConnectionProvider of(DataSource dataSource) {
        return new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException { return dataSource.getConnection(); }

            @Override public void release(Connection connection) throws SQLException { connection.close(); }
        };
    }
}

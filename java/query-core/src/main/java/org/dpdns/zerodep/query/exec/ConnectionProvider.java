package org.dpdns.zerodep.query.exec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

    /** 普通 DataSource：每条语句取一个池化连接，用完关闭（即归还）。 */
    static ConnectionProvider of(DataSource dataSource) {
        return new ConnectionProvider() {
            @Override public Connection acquire() throws SQLException { return dataSource.getConnection(); }

            @Override public void release(Connection connection) throws SQLException { connection.close(); }
        };
    }
}

package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/** 只渲染、不连接的数据库实例；一旦试图连接，测试就失败。 */
public final class TestDatabase extends Database {
    /** 一取连接就抛出异常的连接提供者。 */
    private static final ConnectionProvider NO_CONNECTIONS = new ConnectionProvider() {
        @Override public Connection acquire() throws SQLException { throw new SQLException("TestDatabase never connects"); }

        @Override public void release(Connection connection) { }
    };

    /** 只渲染的实例。 */
    public TestDatabase(Consumer<Config> configure) { this(NO_CONNECTIONS, configure); }

    private TestDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(new TestDialect(), connections, configure);
    }

    /** 空跑的实例：执行查询但不连接数据库，每条查询得到空结果。 */
    public static TestDatabase dryRun(Consumer<Config> configure) { return new TestDatabase(ConnectionProvider.dryRun(), configure); }
}

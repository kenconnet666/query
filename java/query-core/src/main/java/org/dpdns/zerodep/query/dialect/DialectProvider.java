package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * 方言包向通用门面登记自己：{@code Sql.database(...)} 读出连接报告的产品名，在类路径上登记过的方言里找到认识它的那个，
 * 由它创建数据库实例。方言包在 {@code META-INF/services/org.dpdns.zerodep.query.dialect.DialectProvider} 里写上实现类，
 * 实现类要有公开的无参构造器。
 */
public interface DialectProvider {
    /** 认识 JDBC 驱动报告的产品名（{@link java.sql.DatabaseMetaData#getDatabaseProductName()}）时返回 true。 */
    boolean accepts(String productName);

    /** 用本方言创建数据库实例，与方言门面的 {@code database(connections, configure)} 相同；{@code configure} 可为 null。 */
    Database database(ConnectionProvider connections, Consumer<Database.Config> configure);

    /**
     * 取一个连接读出产品名，交给第一个认识它的方言创建实例。没有方言认识时报错，错误里列出登记过的方言，
     * 通常是忘了引入对应的方言包。
     */
    static Database detect(ConnectionProvider connections, Consumer<Database.Config> configure) {
        String product = productName(connections);
        List<String> registered = new ArrayList<>();
        for (DialectProvider provider : ServiceLoader.load(DialectProvider.class)) {
            if (provider.accepts(product)) return provider.database(connections, configure);
            registered.add(provider.getClass().getName());
        }
        throw new IllegalStateException("No dialect on the classpath accepts the database product '" + product + "' (registered: " + registered
            + "); add its dialect module, or create the database with the dialect facade");
    }

    /** 借一个连接读出产品名，读完立即归还。 */
    private static String productName(ConnectionProvider connections) {
        try {
            Connection connection = connections.acquire();
            try {
                return connection.getMetaData().getDatabaseProductName();
            } finally {
                connections.release(connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the database product name to pick a dialect", e);
        }
    }
}

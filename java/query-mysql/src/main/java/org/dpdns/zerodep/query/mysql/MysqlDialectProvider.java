package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.dialect.DialectProvider;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.util.function.Consumer;

/**
 * 向通用门面 {@code Sql.database(...)} 登记 MySQL 方言。PolarDB 报告的产品名也是 MySQL，所以通用门面在 PolarDB 上
 * 同样得到 MySQL 方言；PolarDB 专属的写法要用 {@code Polardb.database(...)}。
 */
public final class MysqlDialectProvider implements DialectProvider {
    /** 驱动报告的产品名是 {@code MySQL}。 */
    @Override public boolean accepts(String productName) { return "MySQL".equals(productName); }

    /** 与 {@link Mysql#database(ConnectionProvider, Consumer)} 相同。 */
    @Override public Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Mysql.database(connections, configure);
    }
}

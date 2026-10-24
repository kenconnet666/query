package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.util.function.Consumer;

/** MySQL 数据库实例；通过 {@link Mysql#database} 创建。兼容 MySQL 的方言（PolarDB）的实例继承它。 */
public class MysqlDatabase extends Database {
    /** 仅由 {@link Mysql#database} 调用；{@code configure} 可为 null。 */
    MysqlDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        this(MysqlDialect.INSTANCE, connections, configure);
    }

    /** 兼容 MySQL 的方言用自己的方言对象创建实例；{@code configure} 可为 null。 */
    protected MysqlDatabase(MysqlDialect dialect, ConnectionProvider connections, Consumer<Config> configure) {
        super(dialect, connections, configure);
    }
}

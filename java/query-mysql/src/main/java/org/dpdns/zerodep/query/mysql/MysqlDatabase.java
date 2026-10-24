package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.util.function.Consumer;

/** MySQL 数据库实例；通过 {@link Mysql#database} 创建。 */
public final class MysqlDatabase extends Database {
    /** 仅由 {@link Mysql#database} 调用；{@code configure} 可为 null。 */
    MysqlDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(MysqlDialect.INSTANCE, connections, configure);
    }
}

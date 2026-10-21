package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;

import java.util.function.Consumer;

/** A MySQL database; create it with {@link Mysql#database}. */
public final class MysqlDatabase extends Database {
    MysqlDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(MysqlDialect.INSTANCE, connections, configure);
    }
}

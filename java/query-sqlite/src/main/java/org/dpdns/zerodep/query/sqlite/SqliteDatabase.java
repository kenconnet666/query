package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.util.function.Consumer;

/** SQLite 数据库实例；通过 {@link Sqlite#database} 创建。 */
public final class SqliteDatabase extends Database {
    /** 仅由 {@link Sqlite#database} 调用；{@code configure} 可为 null。 */
    SqliteDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(SqliteDialect.INSTANCE, connections, configure);
    }
}

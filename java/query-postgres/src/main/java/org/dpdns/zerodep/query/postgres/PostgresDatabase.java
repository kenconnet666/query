package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;

import java.util.function.Consumer;

/** PostgreSQL 数据库实例；通过 {@link Postgres#database} 创建。 */
public final class PostgresDatabase extends Database {
    /** 仅由 {@link Postgres#database} 调用；{@code configure} 可为 null。 */
    PostgresDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(PostgresDialect.INSTANCE, connections, configure);
    }
}

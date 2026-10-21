package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;

import java.util.function.Consumer;

/** A PostgreSQL database; create it with {@link Postgres#database}. */
public final class PostgresDatabase extends Database {
    PostgresDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(PostgresDialect.INSTANCE, connections, configure);
    }
}

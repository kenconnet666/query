package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;

import java.util.function.Consumer;

/** A HANA database; create it with {@link Hana#database}. */
public final class HanaDatabase extends Database {
    HanaDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(HanaDialect.INSTANCE, connections, configure);
    }
}

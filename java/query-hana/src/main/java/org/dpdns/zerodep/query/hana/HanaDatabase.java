package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Database;
import org.dpdns.zerodep.query.exec.ConnectionProvider;

import java.util.function.Consumer;

/** HANA 数据库实例；通过 {@link Hana#database} 创建。 */
public final class HanaDatabase extends Database {
    /** 仅由 {@link Hana#database} 调用；{@code configure} 可为 null。 */
    HanaDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(HanaDialect.INSTANCE, connections, configure);
    }
}

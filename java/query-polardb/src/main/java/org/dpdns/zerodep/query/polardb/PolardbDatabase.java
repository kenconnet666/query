package org.dpdns.zerodep.query.polardb;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.mysql.MysqlDatabase;

import java.util.function.Consumer;

/** PolarDB 数据库实例；通过 {@link Polardb#database} 创建。它也是 {@link MysqlDatabase}，接受 MySQL 方言的全部写法。 */
public final class PolardbDatabase extends MysqlDatabase {
    /** 仅由 {@link Polardb#database} 调用；{@code configure} 可为 null。 */
    PolardbDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(PolardbDialect.INSTANCE, connections, configure);
    }
}

package org.dpdns.zerodep.query.sqlite;

import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.sqlite.Sqlite.*;

@DisplayName("SQLite 渲染金样例")
class SqliteSpecTest extends RenderSpec {
    @Override protected String dialect() { return "sqlite"; }

    @Override protected Database database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return Sqlite.database(connections, configure);
    }

    /** SQLite 专属的函数，以及补齐、左右截取这些在 SQLite 上要改写的核心函数。 */
    @Override protected Map<String, SpecCase> dialectCases() {
        return Map.of("sqlite-functions", SpecCase.plain(() -> from(Lips.class)
            .where(l -> sqlite(l.unit()).glob("E*"))
            .groupBy(l -> l.material())
            .select(l -> cols(l.material(), sqlite(l.quantity()).median().as("middle"), percentileCont(0.9, l.quantity()).as("p90"),
                percentileDisc(0.5, l.quantity()).as("p50"), l.material().lpad(5, "0").as("padded"), l.material().left(1).as("head")))));
    }

    /** SQLite 没有 ROLLUP，也没有加锁读。 */
    @Override protected Set<String> unsupportedCases() { return Set.of("rollup", "workflow-claim"); }
}

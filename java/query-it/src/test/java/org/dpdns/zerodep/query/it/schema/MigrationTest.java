package org.dpdns.zerodep.query.it.schema;

import org.dpdns.zerodep.query.ddl.Migration;
import org.dpdns.zerodep.query.ddl.Migrator;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.testing.Schemas.MTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.util.List;

import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.testing.Schemas.ADD_PRIORITY;
import static org.dpdns.zerodep.query.testing.Schemas.ADD_PRIORITY_CHANGED;
import static org.dpdns.zerodep.query.testing.Schemas.BROKEN;
import static org.dpdns.zerodep.query.testing.Schemas.CREATE_TASK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移：按版本执行一次、记进历史表，历史与迁移不符时报错，失败的迁移可以修复或跳过，历史表可以改名。
 * 迁移定义在 {@code testing.Schemas}：1 建 M_TASK；2 加 PRIORITY 列并写入一行种子数据（ADD_PRIORITY_CHANGED 是改过内容的 2）；
 * 3 是会失败的迁移（BROKEN）。每个测试先删掉 M_TASK 和两套历史表、锁表。
 */
@LiveTest
@DisplayName("迁移")
class MigrationTest {
    @Parameter Live live;

    @BeforeEach
    void dropTables() {
        live.execute("-DROP TABLE " + live.table("M_TASK"), "-DROP TABLE " + live.table(Migrator.HISTORY), "-DROP TABLE " + live.table(Migrator.LOCK),
            "-DROP TABLE " + live.table("M_HISTORY"), "-DROP TABLE " + live.table("M_HISTORY_LOCK"));
    }

    /** 历史表放在 QUERY_IT 里的迁移器。 */
    private Migrator migrator(Migration... migrations) { return live.db().migrator(List.of(migrations)).historySchema("QUERY_IT"); }

    @Test
    void 迁移按版本执行一次且历史与迁移不符时报错() {
        Migrator migrator = migrator(CREATE_TASK, ADD_PRIORITY);
        assertEquals(List.of("1", "2"), List.copyOf(migrator.plan().keySet()));
        assertEquals(List.of("1", "2"), migrator.migrateToLatest());
        assertEquals(List.of(), migrator.migrateToLatest(), "already applied");
        migrator.verify();
        assertEquals(List.of("success", "success"), migrator.history().stream().map(Migrator.Applied::status).toList());
        assertEquals(List.of("T1:seed:5"), live.db().list(from(MTask.class).select(m -> cols(m.id(), m.title(), m.priority())))
            .stream().map(r -> r.get("id") + ":" + r.get("title") + ":" + r.get("priority")).toList());
        // 已执行的 2 被改了内容：校验和对不上。
        var changed = assertThrows(IllegalStateException.class, () -> migrator(CREATE_TASK, ADD_PRIORITY_CHANGED).verify());
        assertTrue(changed.getMessage().contains("checksum differs"), changed.getMessage());
        // 3 执行失败后记为 failed；repair 清掉失败记录让它重新待执行，baseline 把它标为已执行。
        Migrator withBroken = migrator(CREATE_TASK, ADD_PRIORITY, BROKEN);
        assertThrows(IllegalStateException.class, withBroken::migrateToLatest);
        var failed = assertThrows(IllegalStateException.class, withBroken::verify);
        assertTrue(failed.getMessage().contains("3 is failed"), failed.getMessage());
        withBroken.repair("3");
        assertEquals(List.of("3"), withBroken.pending().stream().map(Migration::version).toList());
        withBroken.baseline("3");
        assertEquals(List.of(), withBroken.pending());
        withBroken.verify();
    }

    @Test
    void 迁移历史表改名后两套迁移各记各的() {
        Migrator own = migrator(CREATE_TASK).historyTable("M_HISTORY");
        assertEquals(List.of("1"), own.migrateToLatest());
        assertEquals(List.of("1"), own.history().stream().map(Migrator.Applied::version).toList());
        assertTrue(live.db().schema().read("QUERY_IT", "M_HISTORY_LOCK") != null, "the lock table follows the history table's name");
        assertEquals(List.of(), migrator(CREATE_TASK).history(), "the default history table stays untouched");
    }
}

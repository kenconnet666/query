import assert from "node:assert/strict";
import { beforeEach, it } from "node:test";
import { from, MIGRATION_HISTORY, MIGRATION_LOCK, type Migration } from "@zerodep-dev/query-core";
import { ADD_PRIORITY, ADD_PRIORITY_CHANGED, BROKEN, CREATE_TASK, MTask } from "../../fixtures/schemas.js";
import { liveSuite } from "../support/live.js";

/**
 * 迁移：按版本执行一次、记进历史表，历史与迁移不符时报错，失败的迁移可以修复或跳过，历史表可以改名。
 * 迁移定义在 fixtures/schemas.ts：1 建 M_TASK；2 加 PRIORITY 列并写入一行种子数据（ADD_PRIORITY_CHANGED 是改过内容的 2）；
 * 3 是会失败的迁移（BROKEN）。每个测试先删掉 M_TASK 和两套历史表、锁表。与 Java 的 MigrationTest 对应。
 */
liveSuite("迁移", (live) => {
  beforeEach(() => live.execute(...["M_TASK", MIGRATION_HISTORY, MIGRATION_LOCK, "M_HISTORY", "M_HISTORY_LOCK"].map((t) => `-DROP TABLE ${live.table(t)}`)));

  /** 历史表放在 QUERY_IT 里的迁移器。 */
  const migrator = (...migrations: Migration[]) => live.db.migrator(migrations).historySchema("QUERY_IT");

  it("迁移按版本执行一次且历史与迁移不符时报错", async () => {
    const m = migrator(CREATE_TASK, ADD_PRIORITY);
    assert.deepEqual([...(await m.plan()).keys()], ["1", "2"]);
    assert.deepEqual(await m.migrateToLatest(), ["1", "2"]);
    assert.deepEqual(await m.migrateToLatest(), [], "already applied");
    await m.verify();
    assert.deepEqual((await m.history()).map((a) => a.status), ["success", "success"]);
    assert.deepEqual((await live.db.list(from(MTask).select((t) => ({ id: t.id, title: t.title, priority: t.priority })))).map((r) => `${r.id}:${r.title}:${r.priority}`), ["T1:seed:5"]);
    // 已执行的 2 被改了内容：校验和对不上。
    await assert.rejects(migrator(CREATE_TASK, ADD_PRIORITY_CHANGED).verify(), /checksum differs/);
    // 3 执行失败后记为 failed；repair 清掉失败记录让它重新待执行，baseline 把它标为已执行。
    const withBroken = migrator(CREATE_TASK, ADD_PRIORITY, BROKEN);
    await assert.rejects(withBroken.migrateToLatest());
    await assert.rejects(withBroken.verify(), /3 is failed/);
    await withBroken.repair("3");
    assert.deepEqual((await withBroken.pending()).map((p) => p.version), ["3"]);
    await withBroken.baseline("3");
    assert.deepEqual(await withBroken.pending(), []);
    await withBroken.verify();
  });

  it("迁移历史表改名后两套迁移各记各的", async () => {
    const own = migrator(CREATE_TASK).historyTable("M_HISTORY");
    assert.deepEqual(await own.migrateToLatest(), ["1"]);
    assert.deepEqual((await own.history()).map((a) => a.version), ["1"]);
    assert.ok((await live.db.schema().read("QUERY_IT", "M_HISTORY_LOCK")) !== undefined, "the lock table follows the history table's name");
    assert.deepEqual(await migrator(CREATE_TASK).history(), [], "the default history table stays untouched");
  });
});

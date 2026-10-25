import assert from "node:assert/strict";
import { before, it } from "node:test";
import { all, any, count, forUpdate, from, insertInto, rowNumber, secondsBetween, sum, type Cond, type Ref } from "@zerodep-dev/query-core";
import { HiTask, Link, ProcDef, ProcInst, Task, TodoSearch, Variable } from "../../fixtures/workflow.js";
import { liveSuite } from "../support/live.js";

/**
 * 流程引擎常见的读查询（按 Flowable、Camunda 7、Activiti 的表结构简化，表声明在 fixtures/workflow.ts）：待办、按变量筛选实例、
 * 每个流程的最新版本、已办与统计、加锁领取任务。每个库按声明建一次表，写入下面的数据（与 Java 的 WorkflowQueryTest 相同）：
 *
 * ```
 * WF_PROCDEF 流程定义             WF_PROCINST 流程实例
 *   D1  leave    版本 1             P1  D2  BK-1  alice  2026-01-10 09:00  运行中
 *   D2  leave    版本 2             P2  D2  BK-2  bob    2026-01-11 09:00  运行中
 *   D3  expense  版本 1             P3  D3  BK-3  alice  2026-01-05 09:00  2026-01-12 10:00 结束，耗时 7200000 毫秒
 *                                   P4  D1  BK-4  alice  2025-12-01 09:00  2025-12-01 10:00 结束，耗时 3600000 毫秒
 * WF_VARIABLE 流程变量             WF_TASK 运行中的任务
 *   P1  amount 1500  dept X          T1  approve  无办理人  P1  2026-01-10 09:00
 *   P2  amount  500  dept X          T2  approve  alice     P2  2026-01-11 09:00
 *   P3  amount 2000  dept Y          T3  review   无办理人  P2  2026-01-11 10:00
 *                                    T4  pay      carol     P1  2026-01-10 11:00
 * WF_LINK 候选（T3 另有 alice 的 participant 链接，不算候选）
 *   T1 候选人 bob、候选组 managers；T3 候选组 finance；T4 候选人 alice（但 T4 已有办理人 carol）
 * WF_HI_TASK 历史任务
 *   H1  approve  alice  P3  2026-01-05 09:00 到 2026-01-11 09:00  耗时 3600000
 *   H2  pay      alice  P3  2026-01-11 09:00 到 2026-01-12 10:00  耗时 5400000
 *   H3  approve  bob    P4  2025-12-01 09:00 到 2025-12-01 09:10  耗时  600000
 *   H4  approve  alice  P1  2026-01-10 09:00 起，未办完
 * ```
 */
liveSuite("流程引擎的读查询", (live) => {
  before(async () => {
    await live.recreate(ProcDef, ProcInst, Variable, Task, Link, HiTask);
    const db = live.database();
    await db.execute(insertInto(ProcDef).values({ id: "D1", defKey: "leave", name: "Leave", version: 1 }, { id: "D2", defKey: "leave", name: "Leave", version: 2 }, { id: "D3", defKey: "expense", name: "Expense", version: 1 }));
    await db.execute(
      insertInto(ProcInst).values(
        { id: "P1", defId: "D2", businessKey: "BK-1", startUser: "alice", startTime: "2026-01-10 09:00:00", endTime: null, duration: null },
        { id: "P2", defId: "D2", businessKey: "BK-2", startUser: "bob", startTime: "2026-01-11 09:00:00", endTime: null, duration: null },
        { id: "P3", defId: "D3", businessKey: "BK-3", startUser: "alice", startTime: "2026-01-05 09:00:00", endTime: "2026-01-12 10:00:00", duration: 7_200_000 },
        { id: "P4", defId: "D1", businessKey: "BK-4", startUser: "alice", startTime: "2025-12-01 09:00:00", endTime: "2025-12-01 10:00:00", duration: 3_600_000 },
      ),
    );
    await db.execute(
      insertInto(Variable).values(
        { id: "V1", procInstId: "P1", name: "amount", textValue: null, longValue: 1500 },
        { id: "V2", procInstId: "P1", name: "dept", textValue: "X", longValue: null },
        { id: "V3", procInstId: "P2", name: "amount", textValue: null, longValue: 500 },
        { id: "V4", procInstId: "P2", name: "dept", textValue: "X", longValue: null },
        { id: "V5", procInstId: "P3", name: "amount", textValue: null, longValue: 2000 },
        { id: "V6", procInstId: "P3", name: "dept", textValue: "Y", longValue: null },
      ),
    );
    await db.execute(
      insertInto(Task).values(
        { id: "T1", name: "approve", assignee: null, procInstId: "P1", defId: "D2", createTime: "2026-01-10 09:00:00" },
        { id: "T2", name: "approve", assignee: "alice", procInstId: "P2", defId: "D2", createTime: "2026-01-11 09:00:00" },
        { id: "T3", name: "review", assignee: null, procInstId: "P2", defId: "D2", createTime: "2026-01-11 10:00:00" },
        { id: "T4", name: "pay", assignee: "carol", procInstId: "P1", defId: "D2", createTime: "2026-01-10 11:00:00" },
      ),
    );
    await db.execute(
      insertInto(Link).values(
        { id: "L1", taskId: "T1", kind: "candidate", userId: "bob", groupId: null },
        { id: "L2", taskId: "T1", kind: "candidate", userId: null, groupId: "managers" },
        { id: "L3", taskId: "T3", kind: "candidate", userId: null, groupId: "finance" },
        { id: "L4", taskId: "T4", kind: "candidate", userId: "alice", groupId: null },
        { id: "L5", taskId: "T3", kind: "participant", userId: "alice", groupId: null },
      ),
    );
    await db.execute(
      insertInto(HiTask).values(
        { id: "H1", name: "approve", assignee: "alice", procInstId: "P3", startTime: "2026-01-05 09:00:00", endTime: "2026-01-11 09:00:00", duration: 3_600_000 },
        { id: "H2", name: "pay", assignee: "alice", procInstId: "P3", startTime: "2026-01-11 09:00:00", endTime: "2026-01-12 10:00:00", duration: 5_400_000 },
        { id: "H3", name: "approve", assignee: "bob", procInstId: "P4", startTime: "2025-12-01 09:00:00", endTime: "2025-12-01 09:10:00", duration: 600_000 },
        { id: "H4", name: "approve", assignee: "alice", procInstId: "P1", startTime: "2026-01-10 09:00:00", endTime: null, duration: null },
      ),
    );
  });

  /**
   * 待办（Flowable 的 taskCandidateOrAssigned）：办理人是自己，或者没有办理人、自己或自己所在的组是候选。
   * 用户和组放在同一个 EXISTS 里；筛选条件为空的字段不产生条件。
   */
  const todo = (user: string, groups: readonly string[], search: TodoSearch) =>
    from(Task)
      .where((t) => any(t.assignee.eq(user), all(t.assignee.isNull(), t.candidates.any((l) => any(l.userId.eq(user), l.groupId.in(groups))))))
      .where(search)
      .orderBy((t) => [t.createTime.desc(), t.id])
      .select((t) => ({ id: t.id, name: t.name, assignee: t.assignee, process: t.definition.defKey, businessKey: t.instance.businessKey }));

  it("待办包括自己办理的和自己或所在组是候选的任务", async () => {
    const ids = (rows: readonly { id: string; process: string | null; businessKey: string | null }[]) => rows.map((r) => `${r.id}/${r.process}/${r.businessKey}`);
    assert.deepEqual(ids(await live.db.list(todo("alice", ["managers"], new TodoSearch()))), ["T2/leave/BK-2", "T1/leave/BK-1"], "T2 由 alice 办理；T1 没有办理人、候选组 managers；T4 虽然候选人有 alice，但已经由 carol 办理");
    assert.deepEqual(ids(await live.db.list(todo("bob", ["finance"], new TodoSearch()))), ["T3/leave/BK-2", "T1/leave/BK-1"]);
    const search = Object.assign(new TodoSearch(), { process: "leave", name: "app", createdFrom: "2026-01-11 00:00:00" });
    assert.deepEqual(ids(await live.db.list(todo("alice", ["managers"], search))), ["T2/leave/BK-2"], "流程 leave、名称含 app、1 月 11 日以后创建");
    const page = await live.db.page(todo("bob", ["finance"], new TodoSearch()), 1, 1);
    assert.deepEqual([page.total, page.rows.length], [2, 1]);
  });

  /** 某个变量满足条件：变量名相等，值的条件写在 value 里。 */
  const variable = (p: Ref<ProcInst>, name: string, value: (v: Ref<Variable>) => Cond): Cond => p.variables.any((v) => all(v.name.eq(name), value(v)));

  it("按变量筛选实例的三种写法结果相同", async () => {
    // 运行中、金额大于 1000 且部门是 X 的实例只有 P1（P2 金额 500，P3 已结束）。
    const exists = from(ProcInst) // 每个变量条件一个 EXISTS（Flowable 的写法）
      .where((p) => all(p.endTime.isNull(), variable(p, "amount", (v) => v.longValue.gt(1000)), variable(p, "dept", (v) => v.textValue.eq("X"))))
      .orderBy((p) => p.id)
      .select((p) => p.id);
    const grouped = from(ProcInst) // 变量条件合成一个按实例分组的派生表
      .groupJoin((p) => p.variables)
      .where((p) => all(p.endTime.isNull(), variable(p, "amount", (v) => v.longValue.gt(1000)), variable(p, "dept", (v) => v.textValue.eq("X"))))
      .orderBy((p) => p.id)
      .select((p) => p.id);
    const joined = from(ProcInst) // 每个变量条件连接一次变量表（Activiti 的多别名 JOIN 写法）
      .join(Variable, (p, a) => all(a.procInstId.eq(p.id), a.name.eq("amount"), a.longValue.gt(1000)))
      .join(Variable, (p, _a, d) => all(d.procInstId.eq(p.id), d.name.eq("dept"), d.textValue.eq("X")))
      .where((p) => p.endTime.isNull())
      .orderBy((p) => p.id)
      .select((p) => p.id);
    for (const q of [exists, grouped, joined]) assert.deepEqual(await live.db.list(q), ["P1"]);
  });

  it("每个流程的最新版本两种写法结果相同", async () => {
    const correlated = from(ProcDef) // 版本号等于同一个 key 的最大版本（Flowable 的 latest()）
      .where((d) => d.version.eq(d.versions.max((x) => x.version)))
      .orderBy((d) => d.defKey)
      .select((d) => d.id);
    const ranked = from(ProcDef) // 按 key 分区取版本最大的一行
      .qualify((d) => rowNumber().over((w) => w.partitionBy(d.defKey).orderBy(d.version.desc())).eq(1))
      .orderBy((d) => d.defKey)
      .select((d) => d.id);
    assert.deepEqual(await live.db.list(correlated), ["D3", "D2"]);
    assert.deepEqual(await live.db.list(ranked), ["D3", "D2"]);
  });

  it("已办与统计", async () => {
    // 已办：alice 办完的历史任务，按结束时间倒序。
    const done = await live.db.list(
      from(HiTask)
        .where((h) => all(h.assignee.eq("alice"), h.endTime.isNotNull()))
        .orderBy((h) => [h.endTime.desc(), h.id])
        .select((h) => ({ id: h.id, name: h.name, endTime: h.endTime, duration: h.duration })),
    );
    assert.deepEqual(done.map((r) => `${r.id}:${r.name}:${r.duration}`), ["H2:pay:5400000", "H1:approve:3600000"]);
    // 每个流程定义的运行中、已结束实例数和平均耗时（Camunda 的定义统计）：关系上的几个聚合合成一个分组派生表。
    const statistics = await live.db.list(
      from(ProcDef)
        .groupJoin((d) => d.instances)
        .orderBy((d) => d.id)
        .select((d) => ({
          id: d.id,
          defKey: d.defKey,
          version: d.version,
          running: d.instances.where((p) => p.endTime.isNull()).count(),
          finished: d.instances.where((p) => p.endTime.isNotNull()).count(),
          avgDuration: d.instances.avg((p) => p.duration),
        })),
    );
    assert.deepEqual(statistics.map((r) => `${r.id}:${r.defKey}:${r.version}:${r.running}:${r.finished}:${r.avgDuration === null ? null : Number(r.avgDuration)}`), [
      "D1:leave:1:0:1:3600000",
      "D2:leave:2:2:0:null",
      "D3:expense:1:0:1:7200000",
    ]);
    // 耗时在读取时计算（Warm-Flow、SnakerFlow 不存耗时），单位秒；未办完的为空。
    const elapsed = await live.db.list(from(HiTask).orderBy((h) => h.id).select((h) => ({ id: h.id, seconds: secondsBetween(h.startTime, h.endTime) })));
    assert.deepEqual(elapsed.map((r) => `${r.id}:${r.seconds}`), ["H1:518400", "H2:90000", "H3:600", "H4:null"]);
    // 每个办理人办完的任务数和总耗时，以及其中超过一小时的任务数。
    const workload = await live.db.list(
      from(HiTask)
        .where((h) => h.endTime.isNotNull())
        .groupBy((h) => h.assignee)
        .orderBy((h) => h.assignee)
        .select((h) => ({ assignee: h.assignee, tasks: count(), totalDuration: sum(h.duration), slow: count().filter(h.duration.gt(3_600_000)) })),
    );
    assert.deepEqual(workload.map((r) => `${r.assignee}:${r.tasks}:${r.totalDuration}:${r.slow}`), ["alice:2:9000000:1", "bob:1:600000:0"]);
  });

  it("在事务里加锁领取最早的待认领任务", async () => {
    // 最早创建的、没有办理人的任务，锁住它，被别的事务锁住的跳过，多个工作进程同时领取时不会拿到同一个。
    const claimable = from(Task)
      .where((t) => t.assignee.isNull())
      .orderBy((t) => [t.createTime, t.id])
      .limit(1)
      .with(forUpdate("skipLocked"))
      .select((t) => t.id);
    if (live.is("sqlite", "sqlite-wasm")) {
      await assert.rejects(live.db.list(claimable), /FOR UPDATE/, "SQLite 没有加锁读，渲染时报错");
      return;
    }
    assert.deepEqual(await live.db.transaction(() => live.db.list(claimable)), ["T1"]);
    assert.deepEqual(await live.db.transaction(() => live.db.list(from(Task).where((t) => t.assignee.isNull()).orderBy((t) => t.id).with(forUpdate("nowait")).select((t) => t.id))), ["T1", "T3"]);
  });
});

import { parseDateTime, type CalendarDateTime } from "@internationalized/date";
import assert from "node:assert/strict";
import { beforeEach, it } from "node:test";
import { all, any, bigint, column, from, id, insertInto, integer, many, table, text, timestamp, UnexpectedRowCountError, update, val, version, type Many, type Ref } from "@zerodep-dev/query-core";
import { liveSuite } from "../support/live.js";

const SCHEMA = "QUERY_IT";

/** 流程实例：变量整份存成 JSON 文本；推进前先把版本加一，等于锁住这一行。 */
@table("E_INSTANCE", { schema: SCHEMA })
class Instance {
  @id @column("ID", bigint) id!: bigint;
  @column("STATUS", text, { length: 20, nullable: false }) status!: string;
  @column("VARIABLES", text, { lob: true }) variables!: string | null;
  @version @column("REV", integer, { nullable: false }) rev!: number;
  @column("STARTED_AT", timestamp) startedAt!: CalendarDateTime | null;
}

/** 待办：state 为 open、done、canceled；在岗位收件箱里等认领时 assignee 为空。 */
@table("E_WORK_ITEM", { schema: SCHEMA })
class WorkItem {
  @id @column("ID", bigint) id!: bigint;
  @column("INSTANCE_ID", bigint, { nullable: false }) instanceId!: bigint;
  @column("NODE", text, { length: 40, nullable: false }) node!: string;
  @column("POOL", text, { length: 40 }) pool!: string | null;
  @column("ASSIGNEE", text, { length: 40 }) assignee!: string | null;
  @column("STATE", text, { length: 10, nullable: false }) state!: string;
  @column("CREATED_AT", timestamp) createdAt!: CalendarDateTime | null;

  /** 候选人、候选组与被排除的人。 */
  @many(() => Candidate, (w, c) => c.workItemId.eq(w.id)) candidates!: Many<Candidate>;
}

/** 谁能处理（user、group）和谁被排除（excluded：退回过收件箱，不能再认领）。 */
@table("E_CANDIDATE", { schema: SCHEMA })
class Candidate {
  @id @column("ID", bigint) id!: bigint;
  @column("WORK_ITEM_ID", bigint, { nullable: false }) workItemId!: bigint;
  @column("KIND", text, { length: 10, nullable: false }) kind!: string;
  @column("VAL", text, { length: 40, nullable: false }) value!: string;
}

/** 流转记录：只追加。 */
@table("E_EVENT", { schema: SCHEMA })
class FlowEvent {
  @id @column("ID", bigint) id!: bigint;
  @column("INSTANCE_ID", bigint, { nullable: false }) instanceId!: bigint;
  @column("TYPE", text, { length: 20, nullable: false }) type!: string;
  @column("AT", timestamp, { nullable: false }) at!: CalendarDateTime;
}

/** 作业：到期后由工作进程领取。 */
@table("E_JOB", { schema: SCHEMA })
class Job {
  @id @column("ID", bigint) id!: bigint;
  @column("DUE_AT", timestamp, { nullable: false }) dueAt!: CalendarDateTime;
  @column("LOCKED_BY", text, { length: 40 }) lockedBy!: string | null;
}

/** 测试数据的时间基准（UTC 的本地时间值）。 */
const AT = parseDateTime("2026-10-24T08:00:00");

/** AT 之后若干分钟，可带毫秒。 */
const minutesLater = (minutes: number, millis = 0) => AT.add({ minutes, milliseconds: millis });

/** 停 ms 毫秒。 */
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** user 的待办，写进调用处的条件块：办理人是自己；或者没有办理人、自己或自己的组是候选，而且自己没有被排除。 */
function todo(w: Ref<WorkItem>, user: string, groups: readonly string[]): void {
  w.state.eq("open");
  any(() => {
    w.assignee.eq(user);
    all(() => {
      w.assignee.isNull();
      w.candidates.any((c) =>
        any(() => {
          all(() => {
            c.kind.eq("user");
            c.value.eq(user);
          });
          all(() => {
            c.kind.eq("group");
            c.value.in(groups);
          });
        }),
      );
      w.candidates.none((c) => {
        c.kind.eq("excluded");
        c.value.eq(user);
      });
    });
  });
}

/**
 * 流程引擎写入一侧的写法（flow 仓库的自研引擎打算这样用）：先锁实例行再推进，所以并发的会签只推进一次；带状态条件的更新挡住重复提交；
 * 不加锁读出候选再用条件更新抢占，两个工作进程领取作业互不重复；候选与排除；实例上的长 JSON 变量；只追加的流转记录。
 * 表就声明在上面，主键是应用生成的长整数；每个测试前按声明删掉重建（建表本身也在验证这些列类型的写法）。与 Java 的 EngineTest 对应。
 */
liveSuite("流程引擎的并发写法", (live) => {
  beforeEach(() => live.recreate(Instance, WorkItem, Candidate, FlowEvent, Job));

  /** 一个会签实例：两个待处理的待办，分别给 alice 和 bob。 */
  const signInstance = async () => {
    await live.db.execute(insertInto(Instance).values({ id: 1n, status: "active", variables: "{}", rev: 0, startedAt: AT }));
    await live.db.execute(
      insertInto(WorkItem).values(
        { id: 11n, instanceId: 1n, node: "sign", pool: null, assignee: "alice", state: "open", createdAt: AT },
        { id: 12n, instanceId: 1n, node: "sign", pool: null, assignee: "bob", state: "open", createdAt: AT }),
    );
  };

  /**
   * 引擎处理一个会签待办：先把实例的版本加一（各库都会锁住这一行，后来的推进在这里排队），再把待办从 open 改成 done
   * （改到 0 行就是重复提交），同一节点没有待处理的了就生成下一个节点的待办。中间停一下，让并发的另一次提交确实撞上。
   */
  const completeSign = (item: bigint, next: bigint) =>
    live.db.transaction(async (tx) => {
      await tx.execute(update(Instance).set((i) => ({ rev: i.rev.plus(val(1)) })).where((i) => i.id.eq(1n)).expect(1));
      await tx.execute(update(WorkItem).set(() => ({ state: "done" })).where((w) => {
        w.id.eq(item);
        w.state.eq("open");
      }).expect(1));
      await pause(300);
      const open = await tx.count(from(WorkItem).where((w) => {
        w.instanceId.eq(1n);
        w.node.eq("sign");
        w.state.eq("open");
      }).select((w) => w.id));
      if (open === 0) await tx.execute(insertInto(WorkItem).values({ id: next, instanceId: 1n, node: "after", pool: "review", assignee: null, state: "open", createdAt: AT }));
    });

  it("会签的两个待办同时提交时先锁实例行所以只推进一次", async () => {
    await signInstance();
    await Promise.all([completeSign(11n, 21n), completeSign(12n, 22n)]);
    assert.deepEqual(await live.db.list(from(WorkItem).where((w) => w.node.eq("sign")).orderBy((w) => w.id).select((w) => w.state)), ["done", "done"]);
    assert.equal(await live.db.count(from(WorkItem).where((w) => w.node.eq("after")).select((w) => w.id)), 1, "exactly one of the two completions moves the instance on");
    assert.equal(await live.db.one(from(Instance).select((i) => i.rev)), 2);
  });

  it("同一个待办提交两次时第二次改到 0 行就报错", async () => {
    await signInstance();
    const complete = update(WorkItem).set(() => ({ state: "done" })).where((w) => {
      w.id.eq(11n);
      w.state.eq("open");
    }).expect(1);
    await live.db.execute(complete);
    await assert.rejects(live.db.execute(complete), UnexpectedRowCountError);
  });

  it("两个工作进程同时领取作业时互不重复", async () => {
    // 领取不用 FOR UPDATE SKIP LOCKED：MySQL（InnoDB）的加锁读会锁住扫描到的每一行，第二个进程什么也领不到（见 Java 的同名测试）；
    // 所以先不加锁地读出候选，再逐个用带条件的 UPDATE 抢占，改到 1 行才算领到。
    await live.db.execute(
      insertInto(Job).values(
        { id: 1n, dueAt: minutesLater(0), lockedBy: null },
        { id: 2n, dueAt: minutesLater(1), lockedBy: null },
        { id: 3n, dueAt: minutesLater(2), lockedBy: null },
        { id: 4n, dueAt: minutesLater(3), lockedBy: null },
        { id: 5n, dueAt: parseDateTime("2026-10-25T08:00:00"), lockedBy: null }),
    );
    const worker = async (name: string) => {
      const due = await live.db.list(from(Job).where((j) => {
        j.lockedBy.isNull();
        j.dueAt.le(minutesLater(60));
      }).orderBy((j) => [j.dueAt, j.id]).limit(10).select((j) => j.id));
      const mine: bigint[] = [];
      for (const job of due) {
        if (mine.length === 2) break;
        if ((await live.db.execute(update(Job).set(() => ({ lockedBy: name })).where((j) => {
          j.id.eq(job);
          j.lockedBy.isNull();
        }))) === 1) mine.push(job);
        await pause(50);
      }
      return mine;
    };
    const claimed = await Promise.all([worker("w1"), worker("w2")]);
    assert.deepEqual([...claimed[0]!, ...claimed[1]!].sort((a, b) => Number(a - b)), [1n, 2n, 3n, 4n], `each due job is claimed once: ${claimed}`);
    assert.equal(await live.db.count(from(Job).where((j) => j.lockedBy.isNull()).select((j) => j.id)), 1, "the job due tomorrow stays");
  });

  it("待办包括自己的和候选的但不包括退回过收件箱的", async () => {
    // 31 在 cost 组的收件箱里，但 alice 退回过它（excluded）；32 直接给 alice；33 的候选人是 alice；34 已经办完。
    await live.db.execute(
      insertInto(WorkItem).values(
        { id: 31n, instanceId: 1n, node: "check", pool: "cost", assignee: null, state: "open", createdAt: AT },
        { id: 32n, instanceId: 1n, node: "fix", pool: null, assignee: "alice", state: "open", createdAt: AT },
        { id: 33n, instanceId: 2n, node: "check", pool: null, assignee: null, state: "open", createdAt: AT },
        { id: 34n, instanceId: 2n, node: "fix", pool: null, assignee: "alice", state: "done", createdAt: AT }),
    );
    await live.db.execute(
      insertInto(Candidate).values(
        { id: 1n, workItemId: 31n, kind: "group", value: "cost" },
        { id: 2n, workItemId: 31n, kind: "excluded", value: "alice" },
        { id: 3n, workItemId: 33n, kind: "user", value: "alice" }),
    );
    assert.deepEqual(await live.db.list(from(WorkItem).where((w) => todo(w, "alice", ["cost"])).orderBy((w) => w.id).select((w) => w.id)), [32n, 33n]);
    assert.deepEqual(await live.db.list(from(WorkItem).where((w) => todo(w, "bob", ["cost"])).orderBy((w) => w.id).select((w) => w.id)), [31n]);
  });

  it("实例上的长 JSON 变量原样往返", async () => {
    // 一万多个字符、含中文：要用 LOB 列，按主键整行更新时版本加一。
    const big = `{"amount":"1234.50","note":"${"中文".repeat(3000)}${"x".repeat(6000)}"}`;
    await live.db.execute(insertInto(Instance).values({ id: 2n, status: "active", variables: big, rev: 0, startedAt: AT }));
    assert.equal(await live.db.one(from(Instance).where((i) => i.id.eq(2n)).select((i) => i.variables)), big);
    const changed = big.replace("1234.50", "99.00");
    await live.db.execute(update(Instance).byId({ id: 2n, status: "active", variables: changed, rev: 0, startedAt: AT }));
    assert.equal(await live.db.one(from(Instance).where((i) => i.id.eq(2n)).select((i) => i.variables)), changed);
    assert.equal(await live.db.one(from(Instance).where((i) => i.id.eq(2n)).select((i) => i.rev)), 1);
  });

  it("流转记录批量写入后按时间倒序分页且毫秒不丢", async () => {
    const rows = Array.from({ length: 30 }, (_, k) => ({ id: BigInt(k + 1), instanceId: 1n, type: (k + 1) % 2 === 0 ? "completed" : "arrived", at: minutesLater(k + 1, 123) }));
    await live.db.execute(insertInto(FlowEvent).values(...rows));
    const second = await live.db.page(from(FlowEvent).where((e) => e.instanceId.eq(1n)).orderBy((e) => [e.at.desc(), e.id]).select((e) => e.id), 2, 10);
    assert.equal(second.total, 30);
    assert.deepEqual(second.rows, [20n, 19n, 18n, 17n, 16n, 15n, 14n, 13n, 12n, 11n]);
    assert.equal((await live.db.one(from(FlowEvent).where((e) => e.id.eq(7n)).select((e) => e.at))).toString(), "2026-10-24T08:07:00.123");
  });
});

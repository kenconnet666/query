package org.dpdns.zerodep.query.it.workflow;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.Version;
import org.dpdns.zerodep.query.exec.Page;
import org.dpdns.zerodep.query.exec.UnexpectedRowCountException;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.table.column.ColBigint;
import org.dpdns.zerodep.query.table.column.ColClob;
import org.dpdns.zerodep.query.table.column.ColInteger;
import org.dpdns.zerodep.query.table.column.ColText;
import org.dpdns.zerodep.query.table.column.ColTimestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.dpdns.zerodep.query.Sql.all;
import static org.dpdns.zerodep.query.Sql.any;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.insertInto;
import static org.dpdns.zerodep.query.Sql.orders;
import static org.dpdns.zerodep.query.Sql.update;
import static org.dpdns.zerodep.query.Sql.val;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 流程引擎写入一侧的写法（flow 仓库的自研引擎打算这样用）：先锁实例行再推进，所以并发的会签只推进一次；带状态条件的更新挡住重复提交；
 * 不加锁读出候选再用条件更新抢占，两个工作进程领取作业互不重复；候选与排除；实例上的长 JSON 变量；只追加的流转记录。
 * 表就声明在下面，主键是应用生成的长整数，时间是 UTC 的本地时间值；每个测试前按声明删掉重建（建表本身也在验证这些列类型的写法）。
 */
@LiveTest
@DisplayName("流程引擎的并发写法")
class EngineTest {
    @Parameter Live live;

    /** 流程实例：变量整份存成 JSON 文本；推进前先把版本加一，等于锁住这一行。 */
    @Table(schema = "QUERY_IT", value = "E_INSTANCE")
    interface Instance extends Entity {
        /** 编号。 */
        @Id @Column("ID") ColBigint<Long> id();

        /** 状态：active、completed。 */
        @Column(value = "STATUS", length = 20, nullable = false) ColText<String> status();

        /** 流程变量（JSON）。 */
        @Column("VARIABLES") ColClob<String> variables();

        /** 版本：乐观锁，推进时加一。 */
        @Version @Column(value = "REV", nullable = false) ColInteger<Integer> rev();

        /** 发起时间（UTC）。 */
        @Column("STARTED_AT") ColTimestamp<LocalDateTime> startedAt();
    }

    record InstanceRow(Long id, String status, String variables, Integer rev, LocalDateTime startedAt) { }

    /** 待办：state 为 open、done、canceled；在岗位收件箱里等认领时 assignee 为空。 */
    @Table(schema = "QUERY_IT", value = "E_WORK_ITEM")
    interface WorkItem extends Entity {
        /** 编号。 */
        @Id @Column("ID") ColBigint<Long> id();

        /** 实例。 */
        @Column(value = "INSTANCE_ID", nullable = false) ColBigint<Long> instanceId();

        /** 节点。 */
        @Column(value = "NODE", length = 40, nullable = false) ColText<String> node();

        /** 岗位收件箱；直接落到人时为空。 */
        @Column(value = "POOL", length = 40) ColText<String> pool();

        /** 处理人；在收件箱里等认领时为空。 */
        @Column(value = "ASSIGNEE", length = 40) ColText<String> assignee();

        /** 状态。 */
        @Column(value = "STATE", length = 10, nullable = false) ColText<String> state();

        /** 到达时间（UTC）。 */
        @Column("CREATED_AT") ColTimestamp<LocalDateTime> createdAt();

        /** 候选人、候选组与被排除的人。 */
        default Many<Candidate> candidates() { return many(Candidate.class, c -> c.workItemId().eq(id())); }
    }

    record WorkItemRow(Long id, Long instanceId, String node, String pool, String assignee, String state, LocalDateTime createdAt) { }

    /** 谁能处理（user、group）和谁被排除（excluded：退回过收件箱，不能再认领）。 */
    @Table(schema = "QUERY_IT", value = "E_CANDIDATE")
    interface Candidate extends Entity {
        /** 编号。 */
        @Id @Column("ID") ColBigint<Long> id();

        /** 待办。 */
        @Column(value = "WORK_ITEM_ID", nullable = false) ColBigint<Long> workItemId();

        /** 种类。 */
        @Column(value = "KIND", length = 10, nullable = false) ColText<String> kind();

        /** 人或组。 */
        @Column(value = "VAL", length = 40, nullable = false) ColText<String> value();
    }

    record CandidateRow(Long id, Long workItemId, String kind, String value) { }

    /** 流转记录：只追加。 */
    @Table(schema = "QUERY_IT", value = "E_EVENT")
    interface Event extends Entity {
        /** 编号。 */
        @Id @Column("ID") ColBigint<Long> id();

        /** 实例。 */
        @Column(value = "INSTANCE_ID", nullable = false) ColBigint<Long> instanceId();

        /** 种类。 */
        @Column(value = "TYPE", length = 20, nullable = false) ColText<String> type();

        /** 时间（UTC）。 */
        @Column(value = "AT", nullable = false) ColTimestamp<LocalDateTime> at();
    }

    record EventRow(Long id, Long instanceId, String type, LocalDateTime at) { }

    /** 作业：到期后由工作进程领取。 */
    @Table(schema = "QUERY_IT", value = "E_JOB")
    interface Job extends Entity {
        /** 编号。 */
        @Id @Column("ID") ColBigint<Long> id();

        /** 到期时间（UTC）。 */
        @Column(value = "DUE_AT", nullable = false) ColTimestamp<LocalDateTime> dueAt();

        /** 领取者；没人领取时为空。 */
        @Column(value = "LOCKED_BY", length = 40) ColText<String> lockedBy();
    }

    record JobRow(Long id, LocalDateTime dueAt, String lockedBy) { }

    /** 测试数据的时间基准（UTC）。 */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 24, 8, 0);

    @BeforeEach
    void tables() {
        live.recreate(Instance.class, WorkItem.class, Candidate.class, Event.class, Job.class);
    }

    /** 一个会签实例：两个待处理的待办，分别给 alice 和 bob。 */
    private void signInstance() {
        live.db().execute(insertInto(Instance.class).values(new InstanceRow(1L, "active", "{}", 0, AT)));
        live.db().execute(insertInto(WorkItem.class).values(
            new WorkItemRow(11L, 1L, "sign", null, "alice", "open", AT),
            new WorkItemRow(12L, 1L, "sign", null, "bob", "open", AT)));
    }

    /**
     * 引擎处理一个会签待办：先把实例的版本加一（各库都会锁住这一行，后来的推进在这里排队），再把待办从 open 改成 done
     * （改到 0 行就是重复提交），同一节点没有待处理的了就生成下一个节点的待办。中间停一下，让并发的另一次提交确实撞上。
     */
    private void completeSign(long item, long next) {
        live.db().transaction(() -> {
            live.db().execute(update(Instance.class).set(i -> i.rev().to(i.rev().plus(val(1)))).where(i -> i.id().eq(1L)).expect(1));
            live.db().execute(update(WorkItem.class).set(w -> w.state().to("done")).where(w -> { w.id().eq(item); w.state().eq("open"); }).expect(1));
            pause(300);
            long open = live.db().count(from(WorkItem.class).where(w -> { w.instanceId().eq(1L); w.node().eq("sign"); w.state().eq("open"); }).select(w -> w.id()));
            if (open == 0) live.db().execute(insertInto(WorkItem.class).values(new WorkItemRow(next, 1L, "after", "review", null, "open", AT)));
        });
    }

    @Test
    void 会签的两个待办同时提交时先锁实例行所以只推进一次() throws Exception {
        signInstance();
        concurrently(() -> { completeSign(11L, 21L); return null; }, () -> { completeSign(12L, 22L); return null; });
        assertEquals(List.of("done", "done"), live.db().list(from(WorkItem.class).where(w -> w.node().eq("sign")).orderBy(w -> w.id()).select(w -> w.state())));
        assertEquals(1L, live.db().count(from(WorkItem.class).where(w -> w.node().eq("after")).select(w -> w.id())), "exactly one of the two completions moves the instance on");
        Integer rev = live.db().one(from(Instance.class).select(i -> i.rev()));
        assertEquals(2, rev);
    }

    @Test
    void 同一个待办提交两次时第二次改到0行就报错() {
        signInstance();
        var complete = update(WorkItem.class).set(w -> w.state().to("done")).where(w -> { w.id().eq(11L); w.state().eq("open"); }).expect(1);
        live.db().execute(complete);
        assertThrows(UnexpectedRowCountException.class, () -> live.db().execute(complete));
    }

    /**
     * 两个工作进程同时领取作业，各领两个，互不重复。领取不用 {@code FOR UPDATE SKIP LOCKED}：实测 MySQL（InnoDB）的加锁读
     * 会锁住扫描到的每一行，没有合适的索引时第一个进程就把到期和没到期的行全锁了，第二个进程什么也领不到，而 PostgreSQL 只锁返回的行；
     * 所以先不加锁地读出候选，再逐个用带条件的 UPDATE 抢占（改到 1 行才算领到），各个库（含只有一个写者的 SQLite）写法相同。
     */
    @Test
    void 两个工作进程同时领取作业时互不重复() throws Exception {
        live.db().execute(insertInto(Job.class).values(new JobRow(1L, AT, null), new JobRow(2L, AT.plusMinutes(1), null),
            new JobRow(3L, AT.plusMinutes(2), null), new JobRow(4L, AT.plusMinutes(3), null), new JobRow(5L, AT.plusDays(1), null)));
        Function<String, Callable<List<Long>>> worker = name -> () -> {
            List<Long> due = live.db().list(from(Job.class).where(j -> { j.lockedBy().isNull(); j.dueAt().le(AT.plusHours(1)); })
                .orderBy(j -> orders(j.dueAt(), j.id())).limit(10).select(j -> j.id()));
            List<Long> mine = new ArrayList<>();
            for (Long id : due) {
                if (mine.size() == 2) break;
                if (live.db().execute(update(Job.class).set(j -> j.lockedBy().to(name)).where(j -> { j.id().eq(id); j.lockedBy().isNull(); })) == 1) mine.add(id);
                pause(50);
            }
            return mine;
        };
        List<List<Long>> claimed = concurrently(worker.apply("w1"), worker.apply("w2"));
        List<Long> ids = new ArrayList<>(claimed.get(0));
        ids.addAll(claimed.get(1));
        assertEquals(List.of(1L, 2L, 3L, 4L), ids.stream().sorted().toList(), "each due job is claimed once: " + claimed);
        assertEquals(1L, live.db().count(from(Job.class).where(j -> j.lockedBy().isNull()).select(j -> j.id())), "the job due tomorrow stays");
    }

    /** user 的待办：办理人是自己；或者没有办理人、自己或自己的组是候选，而且自己没有被排除。在条件块里调用。 */
    private static void todo(WorkItem w, String user, List<String> groups) {
        w.state().eq("open");
        any(() -> {
            w.assignee().eq(user);
            all(() -> {
                w.assignee().isNull();
                w.candidates().any(c -> any(() -> {
                    all(() -> { c.kind().eq("user"); c.value().eq(user); });
                    all(() -> { c.kind().eq("group"); c.value().in(groups); });
                }));
                w.candidates().none(c -> { c.kind().eq("excluded"); c.value().eq(user); });
            });
        });
    }

    @Test
    void 待办包括自己的和候选的但不包括退回过收件箱的() {
        // 31 在 cost 组的收件箱里，但 alice 退回过它（excluded）；32 直接给 alice；33 的候选人是 alice；34 已经办完。
        live.db().execute(insertInto(WorkItem.class).values(
            new WorkItemRow(31L, 1L, "check", "cost", null, "open", AT),
            new WorkItemRow(32L, 1L, "fix", null, "alice", "open", AT),
            new WorkItemRow(33L, 2L, "check", null, null, "open", AT),
            new WorkItemRow(34L, 2L, "fix", null, "alice", "done", AT)));
        live.db().execute(insertInto(Candidate.class).values(
            new CandidateRow(1L, 31L, "group", "cost"), new CandidateRow(2L, 31L, "excluded", "alice"),
            new CandidateRow(3L, 33L, "user", "alice")));
        assertEquals(List.of(32L, 33L), live.db().list(from(WorkItem.class).where(w -> todo(w, "alice", List.of("cost"))).orderBy(w -> w.id()).select(w -> w.id())));
        assertEquals(List.of(31L), live.db().list(from(WorkItem.class).where(w -> todo(w, "bob", List.of("cost"))).orderBy(w -> w.id()).select(w -> w.id())));
    }

    @Test
    void 实例上的长JSON变量原样往返() {
        // 一万多个字符、含中文：要用 LOB 列，按主键整行更新时版本加一。
        String big = "{\"amount\":\"1234.50\",\"note\":\"" + "中文".repeat(3000) + "x".repeat(6000) + "\"}";
        live.db().execute(insertInto(Instance.class).values(new InstanceRow(2L, "active", big, 0, AT)));
        assertEquals(big, live.db().one(from(Instance.class).where(i -> i.id().eq(2L)).select(i -> i.variables())));
        String changed = big.replace("1234.50", "99.00");
        live.db().execute(update(Instance.class).byId(new InstanceRow(2L, "active", changed, 0, AT)));
        assertEquals(changed, live.db().one(from(Instance.class).where(i -> i.id().eq(2L)).select(i -> i.variables())));
        Integer rev = live.db().one(from(Instance.class).where(i -> i.id().eq(2L)).select(i -> i.rev()));
        assertEquals(1, rev);
    }

    @Test
    void 流转记录批量写入后按时间倒序分页且毫秒不丢() {
        List<EventRow> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) rows.add(new EventRow((long) i, 1L, i % 2 == 0 ? "completed" : "arrived", AT.plusMinutes(i).plusNanos(123_000_000)));
        live.db().execute(insertInto(Event.class).values(rows));
        Page<Long> second = live.db().page(from(Event.class).where(e -> e.instanceId().eq(1L)).orderBy(e -> orders(e.at().desc(), e.id())).select(e -> e.id()), 2, 10);
        assertEquals(30, second.total());
        assertEquals(List.of(20L, 19L, 18L, 17L, 16L, 15L, 14L, 13L, 12L, 11L), second.rows());
        assertEquals(AT.plusMinutes(7).plusNanos(123_000_000), live.db().one(from(Event.class).where(e -> e.id().eq(7L)).select(e -> e.at())));
    }

    /** 停 ms 毫秒；被中断时恢复中断标记并报错。 */
    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 两个任务在两个线程里同时开始，等两者都结束，返回各自的结果。 */
    private static <R> List<R> concurrently(Callable<R> a, Callable<R> b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return a.call(); });
            var second = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown();
            return Arrays.asList(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}

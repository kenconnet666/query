package org.dpdns.zerodep.query.it.workflow;

import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Workflow.HiTask;
import org.dpdns.zerodep.query.testing.Workflow.Link;
import org.dpdns.zerodep.query.testing.Workflow.ProcDef;
import org.dpdns.zerodep.query.testing.Workflow.ProcInst;
import org.dpdns.zerodep.query.testing.Workflow.Task;
import org.dpdns.zerodep.query.testing.Workflow.TodoSearch;
import org.dpdns.zerodep.query.testing.Workflow.Variable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.dpdns.zerodep.query.Sql.all;
import static org.dpdns.zerodep.query.Sql.any;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.count;
import static org.dpdns.zerodep.query.Sql.forUpdate;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.insertInto;
import static org.dpdns.zerodep.query.Sql.orders;
import static org.dpdns.zerodep.query.Sql.rowNumber;
import static org.dpdns.zerodep.query.Sql.secondsBetween;
import static org.dpdns.zerodep.query.Sql.sum;
import static org.dpdns.zerodep.query.it.support.Db.SQLITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 流程引擎常见的读查询（按 Flowable、Camunda 7、Activiti 的表结构简化，表声明在 {@code testing.Workflow}）：待办、按变量筛选实例、
 * 每个流程的最新版本、已办与统计、加锁领取任务。每个库按声明建一次表，写入下面的数据：
 *
 * <pre>
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
 * </pre>
 */
@LiveTest
@DisplayName("流程引擎的读查询")
class WorkflowQueryTest {
    @Parameter Live live;

    record ProcDefRow(String id, String defKey, String name, Integer version) { }

    record ProcInstRow(String id, String defId, String businessKey, String startUser, LocalDateTime startTime, LocalDateTime endTime, Long duration) { }

    record VariableRow(String id, String procInstId, String name, String textValue, Long longValue) { }

    record TaskRow(String id, String name, String assignee, String procInstId, String defId, LocalDateTime createTime) { }

    record LinkRow(String id, String taskId, String kind, String userId, String groupId) { }

    record HiTaskRow(String id, String name, String assignee, String procInstId, LocalDateTime startTime, LocalDateTime endTime, Long duration) { }

    @BeforeParameterizedClassInvocation
    static void tables(Live live) {
        live.recreate(ProcDef.class, ProcInst.class, Variable.class, Task.class, Link.class, HiTask.class);
        var db = live.database(null);
        db.execute(insertInto(ProcDef.class).values(new ProcDefRow("D1", "leave", "Leave", 1), new ProcDefRow("D2", "leave", "Leave", 2),
            new ProcDefRow("D3", "expense", "Expense", 1)));
        db.execute(insertInto(ProcInst.class).values(
            new ProcInstRow("P1", "D2", "BK-1", "alice", at(1, 10, 9), null, null),
            new ProcInstRow("P2", "D2", "BK-2", "bob", at(1, 11, 9), null, null),
            new ProcInstRow("P3", "D3", "BK-3", "alice", at(1, 5, 9), at(1, 12, 10), 7_200_000L),
            new ProcInstRow("P4", "D1", "BK-4", "alice", LocalDateTime.of(2025, 12, 1, 9, 0), LocalDateTime.of(2025, 12, 1, 10, 0), 3_600_000L)));
        db.execute(insertInto(Variable.class).values(
            new VariableRow("V1", "P1", "amount", null, 1500L), new VariableRow("V2", "P1", "dept", "X", null),
            new VariableRow("V3", "P2", "amount", null, 500L), new VariableRow("V4", "P2", "dept", "X", null),
            new VariableRow("V5", "P3", "amount", null, 2000L), new VariableRow("V6", "P3", "dept", "Y", null)));
        db.execute(insertInto(Task.class).values(
            new TaskRow("T1", "approve", null, "P1", "D2", at(1, 10, 9)),
            new TaskRow("T2", "approve", "alice", "P2", "D2", at(1, 11, 9)),
            new TaskRow("T3", "review", null, "P2", "D2", at(1, 11, 10)),
            new TaskRow("T4", "pay", "carol", "P1", "D2", at(1, 10, 11))));
        db.execute(insertInto(Link.class).values(
            new LinkRow("L1", "T1", "candidate", "bob", null), new LinkRow("L2", "T1", "candidate", null, "managers"),
            new LinkRow("L3", "T3", "candidate", null, "finance"), new LinkRow("L4", "T4", "candidate", "alice", null),
            new LinkRow("L5", "T3", "participant", "alice", null)));
        db.execute(insertInto(HiTask.class).values(
            new HiTaskRow("H1", "approve", "alice", "P3", at(1, 5, 9), at(1, 11, 9), 3_600_000L),
            new HiTaskRow("H2", "pay", "alice", "P3", at(1, 11, 9), at(1, 12, 10), 5_400_000L),
            new HiTaskRow("H3", "approve", "bob", "P4", LocalDateTime.of(2025, 12, 1, 9, 0), LocalDateTime.of(2025, 12, 1, 9, 10), 600_000L),
            new HiTaskRow("H4", "approve", "alice", "P1", at(1, 10, 9), null, null)));
    }

    /** 2026 年 month 月 day 日 hour 点整。 */
    private static LocalDateTime at(int month, int day, int hour) { return LocalDateTime.of(2026, month, day, hour, 0); }

    /**
     * 待办（Flowable 的 taskCandidateOrAssigned）：办理人是自己，或者没有办理人、自己或自己所在的组是候选。
     * 用户和组放在同一个 EXISTS 里；筛选条件为空的字段不产生条件。
     */
    private static Query<Map<String, Object>> todo(String user, List<String> groups, TodoSearch search) {
        return from(Task.class)
            .where(t -> any(t.assignee().eq(user),
                all(t.assignee().isNull(), t.candidates().any(l -> any(l.userId().eq(user), l.groupId().in(groups))))))
            .where(search)
            .orderBy(t -> orders(t.createTime().desc(), t.id()))
            .select(t -> cols(t.id(), t.name(), t.assignee(), t.definition().defKey().as("process"), t.instance().businessKey()));
    }

    @Test
    void 待办包括自己办理的和自己或所在组是候选的任务() {
        Function<List<Map<String, Object>>, List<String>> ids = rows -> rows.stream()
            .map(r -> r.get("id") + "/" + r.get("process") + "/" + r.get("businessKey")).toList();
        TodoSearch none = new TodoSearch(null, null, null);
        assertEquals(List.of("T2/leave/BK-2", "T1/leave/BK-1"), ids.apply(live.db().list(todo("alice", List.of("managers"), none))),
            "T2 由 alice 办理；T1 没有办理人、候选组 managers；T4 虽然候选人有 alice，但已经由 carol 办理");
        assertEquals(List.of("T3/leave/BK-2", "T1/leave/BK-1"), ids.apply(live.db().list(todo("bob", List.of("finance"), none))));
        assertEquals(List.of("T2/leave/BK-2"), ids.apply(live.db().list(todo("alice", List.of("managers"),
            new TodoSearch("leave", "app", LocalDateTime.of(2026, 1, 11, 0, 0))))), "流程 leave、名称含 app、1 月 11 日以后创建");
        var page = live.db().page(todo("bob", List.of("finance"), none), 1, 1);
        assertEquals(2, page.total());
        assertEquals(1, page.rows().size());
    }

    /** 某个变量满足条件：变量名相等，值的条件写在 value 里。 */
    private static Cond variable(ProcInst p, String name, Function<Variable, Cond> value) {
        return p.variables().any(v -> all(v.name().eq(name), value.apply(v)));
    }

    @Test
    void 按变量筛选实例的三种写法结果相同() {
        // 运行中、金额大于 1000 且部门是 X 的实例只有 P1（P2 金额 500，P3 已结束）。
        Query<String> exists = from(ProcInst.class)        // 每个变量条件一个 EXISTS（Flowable 的写法）
            .where(p -> all(p.endTime().isNull(), variable(p, "amount", v -> v.longValue().gt(1000L)), variable(p, "dept", v -> v.textValue().eq("X"))))
            .orderBy(p -> p.id())
            .select(p -> p.id());
        Query<String> grouped = from(ProcInst.class)       // 变量条件合成一个按实例分组的派生表
            .groupJoin(p -> p.variables())
            .where(p -> all(p.endTime().isNull(), variable(p, "amount", v -> v.longValue().gt(1000L)), variable(p, "dept", v -> v.textValue().eq("X"))))
            .orderBy(p -> p.id())
            .select(p -> p.id());
        Query<String> joined = from(ProcInst.class)        // 每个变量条件连接一次变量表（Activiti 的多别名 JOIN 写法）
            .join(Variable.class, (p, a) -> all(a.procInstId().eq(p.id()), a.name().eq("amount"), a.longValue().gt(1000L)))
            .join(Variable.class, (p, a, d) -> all(d.procInstId().eq(p.id()), d.name().eq("dept"), d.textValue().eq("X")))
            .where((p, a, d) -> p.endTime().isNull())
            .orderBy((p, a, d) -> p.id())
            .select((p, a, d) -> p.id());
        assertEquals(List.of("P1"), live.db().list(exists));
        assertEquals(List.of("P1"), live.db().list(grouped));
        assertEquals(List.of("P1"), live.db().list(joined));
    }

    @Test
    void 每个流程的最新版本两种写法结果相同() {
        Query<String> correlated = from(ProcDef.class)     // 版本号等于同一个 key 的最大版本（Flowable 的 latest()）
            .where(d -> d.version().eq(d.versions().max(x -> x.version())))
            .orderBy(d -> d.defKey())
            .select(d -> d.id());
        Query<String> ranked = from(ProcDef.class)         // 按 key 分区取版本最大的一行
            .qualify(d -> rowNumber().over(w -> w.partitionBy(d.defKey()).orderBy(d.version().desc())).eq(1L))
            .orderBy(d -> d.defKey())
            .select(d -> d.id());
        assertEquals(List.of("D3", "D2"), live.db().list(correlated));
        assertEquals(List.of("D3", "D2"), live.db().list(ranked));
    }

    @Test
    void 已办与统计() {
        // 已办：alice 办完的历史任务，按结束时间倒序。
        assertEquals(List.of("H2:pay:5400000", "H1:approve:3600000"), live.db().list(from(HiTask.class)
                .where(h -> all(h.assignee().eq("alice"), h.endTime().isNotNull()))
                .orderBy(h -> orders(h.endTime().desc(), h.id()))
                .select(h -> cols(h.id(), h.name(), h.endTime(), h.duration())))
            .stream().map(r -> r.get("id") + ":" + r.get("name") + ":" + r.get("duration")).toList());
        // 每个流程定义的运行中、已结束实例数和平均耗时（Camunda 的定义统计）：关系上的几个聚合合成一个分组派生表。
        assertEquals(List.of("D1:leave:1:0:1:3600000", "D2:leave:2:2:0:null", "D3:expense:1:0:1:7200000"), live.db().list(from(ProcDef.class)
                .groupJoin(d -> d.instances())
                .orderBy(d -> d.id())
                .select(d -> cols(d.id(), d.defKey(), d.version(),
                    d.instances().where(p -> p.endTime().isNull()).count().as("running"),
                    d.instances().where(p -> p.endTime().isNotNull()).count().as("finished"),
                    d.instances().avg(p -> p.duration()).as("avgDuration"))))
            .stream().map(r -> r.get("id") + ":" + r.get("defKey") + ":" + r.get("version") + ":" + r.get("running") + ":" + r.get("finished") + ":"
                + (r.get("avgDuration") == null ? null : ((BigDecimal) r.get("avgDuration")).stripTrailingZeros().toPlainString()))
            .toList());
        // 耗时在读取时计算（Warm-Flow、SnakerFlow 不存耗时），单位秒；未办完的为空。
        assertEquals(List.of("H1:518400", "H2:90000", "H3:600", "H4:null"), live.db().list(from(HiTask.class)
                .orderBy(h -> h.id())
                .select(h -> cols(h.id(), secondsBetween(h.startTime(), h.endTime()).as("seconds"))))
            .stream().map(r -> r.get("id") + ":" + r.get("seconds")).toList());
        // 每个办理人办完的任务数和总耗时，以及其中超过一小时的任务数。
        assertEquals(List.of("alice:2:9000000:1", "bob:1:600000:0"), live.db().list(from(HiTask.class)
                .where(h -> h.endTime().isNotNull())
                .groupBy(h -> h.assignee())
                .orderBy(h -> h.assignee())
                .select(h -> cols(h.assignee(), count().as("tasks"), sum(h.duration()).as("totalDuration"),
                    count().filter(h.duration().gt(3_600_000L)).as("slow"))))
            .stream().map(r -> r.get("assignee") + ":" + r.get("tasks") + ":" + r.get("totalDuration") + ":" + r.get("slow")).toList());
    }

    @Test
    void 在事务里加锁领取最早的待认领任务() {
        // 最早创建的、没有办理人的任务，锁住它，被别的事务锁住的跳过，多个工作进程同时领取时不会拿到同一个。
        Query<String> claimable = from(Task.class)
            .where(t -> t.assignee().isNull())
            .orderBy(t -> orders(t.createTime(), t.id()))
            .limit(1)
            .with(forUpdate(Lock.SKIP_LOCKED))
            .select(t -> t.id());
        if (live.is(SQLITE)) {
            assertThrows(UnsupportedOperationException.class, () -> live.db().list(claimable), "SQLite 没有加锁读，渲染时报错");
            return;
        }
        assertEquals(List.of("T1"), live.db().transaction(() -> live.db().list(claimable)));
        assertEquals(List.of("T1", "T3"), live.db().transaction(() -> live.db().list(from(Task.class).where(t -> t.assignee().isNull())
            .orderBy(t -> t.id()).with(forUpdate(Lock.NOWAIT)).select(t -> t.id()))));
    }
}

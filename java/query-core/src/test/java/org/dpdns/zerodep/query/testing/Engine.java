package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.Version;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;

import java.time.LocalDateTime;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.all;
import static org.dpdns.zerodep.query.Sql.any;

/**
 * 流程引擎写入一侧的对照场景：按自研引擎（flow 仓库）打算用的表形状简化出的几张表，主键是应用生成的长整数，
 * 时间是 UTC 的本地时间值。实库场景用它验证引擎的写法在各个库上都成立：先锁实例行再推进（并发汇聚只推进一次）、
 * 带状态条件的更新（重复提交改到 0 行）、SKIP LOCKED 领取作业、候选与排除、实例上的长文本变量。表由结构变更按声明建出。
 */
public final class Engine {
    /** 测试表所在的 schema。 */
    public static final String SCHEMA = "QUERY_IT";

    /** 只放表和行，不能实例化。 */
    private Engine() { }

    /** 流程实例：变量整份存成 JSON 文本；推进前先把版本加一，等于锁住这一行。 */
    @Table(schema = SCHEMA, value = "E_INSTANCE")
    public interface Instance extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 状态：active、completed。 */
        @Column(value = "STATUS", length = 20, nullable = false) Col<String> status();

        /** 流程变量（JSON）。 */
        @Column(value = "VARIABLES", lob = true) Col<String> variables();

        /** 版本：乐观锁，推进时加一。 */
        @Version @Column(value = "REV", nullable = false) Col<Integer> rev();

        /** 发起时间（UTC）。 */
        @Column("STARTED_AT") Col<LocalDateTime> startedAt();

        /** 实例的待办。 */
        default Many<WorkItem> items() { return many(WorkItem.class, w -> w.instanceId().eq(id())); }
    }

    /** 实例的一行。 */
    public record InstanceRow(Long id, String status, String variables, Integer rev, LocalDateTime startedAt) { }

    /** 待办：state 为 open、done、canceled；在岗位收件箱里等认领时 assignee 为空。 */
    @Table(schema = SCHEMA, value = "E_WORK_ITEM")
    public interface WorkItem extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 实例。 */
        @Column(value = "INSTANCE_ID", nullable = false) Col<Long> instanceId();

        /** 节点。 */
        @Column(value = "NODE", length = 40, nullable = false) Col<String> node();

        /** 岗位收件箱；直接落到人时为空。 */
        @Column(value = "POOL", length = 40) Col<String> pool();

        /** 处理人；在收件箱里等认领时为空。 */
        @Column(value = "ASSIGNEE", length = 40) Col<String> assignee();

        /** 状态。 */
        @Column(value = "STATE", length = 10, nullable = false) Col<String> state();

        /** 到达时间（UTC）。 */
        @Column("CREATED_AT") Col<LocalDateTime> createdAt();

        /** 候选人、候选组与被排除的人。 */
        default Many<Candidate> candidates() { return many(Candidate.class, c -> c.workItemId().eq(id())); }
    }

    /** 待办的一行。 */
    public record WorkItemRow(Long id, Long instanceId, String node, String pool, String assignee, String state, LocalDateTime createdAt) { }

    /** 谁能处理（user、group）和谁被排除（excluded：退回过收件箱，不能再认领）。 */
    @Table(schema = SCHEMA, value = "E_CANDIDATE")
    public interface Candidate extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 待办。 */
        @Column(value = "WORK_ITEM_ID", nullable = false) Col<Long> workItemId();

        /** 种类。 */
        @Column(value = "KIND", length = 10, nullable = false) Col<String> kind();

        /** 人或组。 */
        @Column(value = "VAL", length = 40, nullable = false) Col<String> value();
    }

    /** 候选的一行。 */
    public record CandidateRow(Long id, Long workItemId, String kind, String value) { }

    /** 流转记录：只追加。 */
    @Table(schema = SCHEMA, value = "E_EVENT")
    public interface Event extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 实例。 */
        @Column(value = "INSTANCE_ID", nullable = false) Col<Long> instanceId();

        /** 种类。 */
        @Column(value = "TYPE", length = 20, nullable = false) Col<String> type();

        /** 时间（UTC）。 */
        @Column(value = "AT", nullable = false) Col<LocalDateTime> at();
    }

    /** 流转记录的一行。 */
    public record EventRow(Long id, Long instanceId, String type, LocalDateTime at) { }

    /** 作业：到期后由工作进程领取。 */
    @Table(schema = SCHEMA, value = "E_JOB")
    public interface Job extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 到期时间（UTC）。 */
        @Column(value = "DUE_AT", nullable = false) Col<LocalDateTime> dueAt();

        /** 领取者；没人领取时为空。 */
        @Column(value = "LOCKED_BY", length = 40) Col<String> lockedBy();
    }

    /** 作业的一行。 */
    public record JobRow(Long id, LocalDateTime dueAt, String lockedBy) { }

    /** 引擎场景用到的全部表，建表与清理用。 */
    public static final List<Class<? extends Entity>> TABLES = List.of(Instance.class, WorkItem.class, Candidate.class, Event.class, Job.class);

    /** user 的待办：办理人是自己；或者没有办理人、自己或自己的组是候选，而且自己没有被排除。 */
    public static Cond todo(WorkItem w, String user, List<String> groups) {
        return all(w.state().eq("open"), any(w.assignee().eq(user), all(w.assignee().isNull(),
            w.candidates().any(c -> any(all(c.kind().eq("user"), c.value().eq(user)), all(c.kind().eq("group"), c.value().in(groups)))),
            w.candidates().none(c -> all(c.kind().eq("excluded"), c.value().eq(user))))));
    }
}

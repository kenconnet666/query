package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.table.column.ColBigint;
import org.dpdns.zerodep.query.table.column.ColInteger;
import org.dpdns.zerodep.query.table.column.ColText;
import org.dpdns.zerodep.query.table.column.ColTimestamp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.Sql.*;

/**
 * 流程引擎读查询的对照场景：按 Flowable、Camunda 7、Activiti 等引擎的表结构简化出的几张表，
 * 以及它们最常见的读查询（待办、变量过滤、最新版本、已办、统计）用本库的写法。金样例渲染这些查询；query-it 的 WorkflowQueryTest 按这些表建表，在各库上执行同样的查询。
 */
public final class Workflow {
    /** 测试表所在的 schema。 */
    public static final String SCHEMA = "QUERY_IT";

    /** 只放表和查询，不能实例化。 */
    private Workflow() { }

    // ---------- 表 ----------

    /** 流程定义：同一个 key 每次部署一个新版本。 */
    @Table(schema = SCHEMA, value = "WF_PROCDEF")
    public interface ProcDef extends Entity {
        /** 定义编号。 */
        @Column("ID") ColText<String> id();

        /** 流程的 key，各版本相同。 */
        @Column("DEF_KEY") ColText<String> defKey();

        /** 名称。 */
        @Column("NAME") ColText<String> name();

        /** 版本号，从 1 开始。 */
        @Column("VERSION") ColInteger<Integer> version();

        /** 这个定义启动的流程实例。 */
        default Many<ProcInst> instances() { return many(ProcInst.class, p -> p.defId().eq(id())); }

        /** 同一个 key 的全部版本（包括自己）。 */
        default Many<ProcDef> versions() { return many(ProcDef.class, x -> x.defKey().eq(defKey())); }
    }

    /** 流程实例：结束时间为空表示仍在运行；耗时在结束时写入（毫秒）。 */
    @Table(schema = SCHEMA, value = "WF_PROCINST")
    public interface ProcInst extends Entity {
        /** 实例编号。 */
        @Column("ID") ColText<String> id();

        /** 流程定义编号。 */
        @Column("DEF_ID") ColText<String> defId();

        /** 业务主键。 */
        @Column("BUSINESS_KEY") ColText<String> businessKey();

        /** 发起人。 */
        @Column("START_USER") ColText<String> startUser();

        /** 开始时间。 */
        @Column("START_TIME") ColTimestamp<LocalDateTime> startTime();

        /** 结束时间，运行中为空。 */
        @Column("END_TIME") ColTimestamp<LocalDateTime> endTime();

        /** 耗时（毫秒），运行中为空。 */
        @Column("DURATION") ColBigint<Long> duration();

        /** 所属的流程定义。 */
        default ProcDef definition() { return one(ProcDef.class, d -> d.id().eq(defId())); }

        /** 流程变量。 */
        default Many<Variable> variables() { return many(Variable.class, v -> v.procInstId().eq(id())); }
    }

    /** 运行中的任务：有办理人时就是办理人的待办，没有时由候选人或候选组认领。 */
    @Table(schema = SCHEMA, value = "WF_TASK")
    public interface Task extends Entity {
        /** 任务编号。 */
        @Column("ID") ColText<String> id();

        /** 任务名称。 */
        @Column("NAME") ColText<String> name();

        /** 办理人，待认领时为空。 */
        @Column("ASSIGNEE") ColText<String> assignee();

        /** 流程实例编号。 */
        @Column("PROC_INST_ID") ColText<String> procInstId();

        /** 流程定义编号。 */
        @Column("DEF_ID") ColText<String> defId();

        /** 创建时间。 */
        @Column("CREATE_TIME") ColTimestamp<LocalDateTime> createTime();

        /** 所属的流程定义。 */
        default ProcDef definition() { return one(ProcDef.class, d -> d.id().eq(defId())); }

        /** 所属的流程实例。 */
        default ProcInst instance() { return one(ProcInst.class, p -> p.id().eq(procInstId())); }

        /** 候选人和候选组。 */
        default Many<Link> candidates() { return many(Link.class, l -> { l.taskId().eq(id()); l.kind().eq("candidate"); }); }
    }

    /** 任务的身份链接：候选人（USER_ID）或候选组（GROUP_ID）。 */
    @Table(schema = SCHEMA, value = "WF_LINK")
    public interface Link extends Entity {
        /** 链接编号。 */
        @Column("ID") ColText<String> id();

        /** 任务编号。 */
        @Column("TASK_ID") ColText<String> taskId();

        /** 链接类型，例如 candidate。 */
        @Column("KIND") ColText<String> kind();

        /** 候选人。 */
        @Column("USER_ID") ColText<String> userId();

        /** 候选组。 */
        @Column("GROUP_ID") ColText<String> groupId();
    }

    /** 流程变量：按值的类型分列存放，文本在 TEXT_VALUE，整数在 LONG_VALUE。 */
    @Table(schema = SCHEMA, value = "WF_VARIABLE")
    public interface Variable extends Entity {
        /** 变量编号。 */
        @Column("ID") ColText<String> id();

        /** 流程实例编号。 */
        @Column("PROC_INST_ID") ColText<String> procInstId();

        /** 变量名。 */
        @Column("NAME") ColText<String> name();

        /** 文本值。 */
        @Column("TEXT_VALUE") ColText<String> textValue();

        /** 整数值。 */
        @Column("LONG_VALUE") ColBigint<Long> longValue();
    }

    /** 历史任务：结束时间不为空表示已办完；耗时在结束时写入（毫秒）。 */
    @Table(schema = SCHEMA, value = "WF_HI_TASK")
    public interface HiTask extends Entity {
        /** 任务编号。 */
        @Column("ID") ColText<String> id();

        /** 任务名称。 */
        @Column("NAME") ColText<String> name();

        /** 办理人。 */
        @Column("ASSIGNEE") ColText<String> assignee();

        /** 流程实例编号。 */
        @Column("PROC_INST_ID") ColText<String> procInstId();

        /** 开始时间。 */
        @Column("START_TIME") ColTimestamp<LocalDateTime> startTime();

        /** 结束时间，未办完时为空。 */
        @Column("END_TIME") ColTimestamp<LocalDateTime> endTime();

        /** 耗时（毫秒）。 */
        @Column("DURATION") ColBigint<Long> duration();
    }

    // ---------- 查询 ----------

    /** 待办列表页面上的可选筛选条件。 */
    public record TodoSearch(
        @Where("definition.defKey") String process,
        @Where(op = Where.Op.CONTAINS) String name,
        @Where(value = "createTime", op = Where.Op.GE) LocalDateTime createdFrom) { }

    /**
     * 待办（Flowable 的 taskCandidateOrAssigned）：办理人是自己，或者没有办理人、自己或自己所在的组是候选。
     * 用户和组放在同一个 EXISTS 里；筛选条件为空的字段不产生条件。
     */
    public static Query<Map<String, Object>> todo(String user, List<String> groups, TodoSearch search) {
        return from(Task.class)
            .where(t -> any(() -> {
                t.assignee().eq(user);
                all(() -> {
                    t.assignee().isNull();
                    t.candidates().any(l -> any(() -> {
                        l.userId().eq(user);
                        l.groupId().in(groups);
                    }));
                });
            }))
            .where(search)
            .orderBy(t -> orders(t.createTime().desc(), t.id()))
            .select(t -> cols(t.id(), t.name(), t.assignee(), t.definition().defKey().as("process"), t.instance().businessKey()));
    }

    /** 领取一个没有办理人的任务：最早创建的一个，锁住它，被别的事务锁住的跳过（多个工作进程同时领取时不会拿到同一个）。 */
    public static Query<String> claimable() {
        return from(Task.class)
            .where(t -> t.assignee().isNull())
            .orderBy(t -> orders(t.createTime(), t.id()))
            .limit(1)
            .with(forUpdate(Lock.SKIP_LOCKED))
            .select(t -> t.id());
    }

    /** 某个变量满足条件：变量名相等，值的条件写在 value 里；在条件块里调用，EXISTS 写进调用处的块。 */
    public static void variable(ProcInst p, String name, Consumer<Variable> value) {
        p.variables().any(v -> {
            v.name().eq(name);
            value.accept(v);
        });
    }

    /** 运行中、金额大于 1000 且部门是 X 的流程实例：每个变量条件一个 EXISTS（Flowable 的写法）。 */
    public static Query<String> instancesByVariables() {
        return from(ProcInst.class)
            .where(p -> {
                p.endTime().isNull();
                variable(p, "amount", v -> v.longValue().gt(1000L));
                variable(p, "dept", v -> v.textValue().eq("X"));
            })
            .orderBy(p -> p.id())
            .select(p -> p.id());
    }

    /** 同上，变量条件合成一个按实例分组的派生表（groupJoin）。 */
    public static Query<String> instancesByVariablesGrouped() {
        return from(ProcInst.class)
            .groupJoin(p -> p.variables())
            .where(p -> {
                p.endTime().isNull();
                variable(p, "amount", v -> v.longValue().gt(1000L));
                variable(p, "dept", v -> v.textValue().eq("X"));
            })
            .orderBy(p -> p.id())
            .select(p -> p.id());
    }

    /** 同上，每个变量条件连接一次变量表（Activiti 的多别名 JOIN 写法）。 */
    public static Query<String> instancesByVariablesJoined() {
        return from(ProcInst.class)
            .join(Variable.class, (p, a) -> { a.procInstId().eq(p.id()); a.name().eq("amount"); a.longValue().gt(1000L); })
            .join(Variable.class, (p, a, d) -> { d.procInstId().eq(p.id()); d.name().eq("dept"); d.textValue().eq("X"); })
            .where((p, a, d) -> p.endTime().isNull())
            .orderBy((p, a, d) -> p.id())
            .select((p, a, d) -> p.id());
    }

    /** 每个 key 的最新版本：版本号等于同一个 key 的最大版本（相关子查询，Flowable 的 latest()），用自关联 versions() 写。 */
    public static Query<String> latestDefinitions() {
        return from(ProcDef.class)
            .where(d -> d.version().eq(d.versions().max(x -> x.version())))
            .orderBy(d -> d.defKey())
            .select(d -> d.id());
    }

    /** 同上，按 key 分区取版本最大的一行（qualify）。 */
    public static Query<String> latestDefinitionsRanked() {
        return from(ProcDef.class)
            .qualify(d -> rowNumber().over(w -> w.partitionBy(d.defKey()).orderBy(d.version().desc())).eq(1L))
            .orderBy(d -> d.defKey())
            .select(d -> d.id());
    }

    /** 已办：某人办完的历史任务，按结束时间倒序。 */
    public static Query<Map<String, Object>> done(String user) {
        return from(HiTask.class)
            .where(h -> { h.assignee().eq(user); h.endTime().isNotNull(); })
            .orderBy(h -> orders(h.endTime().desc(), h.id()))
            .select(h -> cols(h.id(), h.name(), h.endTime(), h.duration()));
    }

    /** 每个流程定义的运行中、已结束实例数和平均耗时（Camunda 的定义统计），关系上的运算合成一个分组派生表。 */
    public static Query<Map<String, Object>> definitionStatistics() {
        return from(ProcDef.class)
            .groupJoin(d -> d.instances())
            .orderBy(d -> d.id())
            .select(d -> cols(d.id(), d.defKey(), d.version(),
                d.instances().where(p -> p.endTime().isNull()).count().as("running"),
                d.instances().where(p -> p.endTime().isNotNull()).count().as("finished"),
                d.instances().avg(p -> p.duration()).as("avgDuration")));
    }

    /** 历史任务的耗时在读取时计算（Warm-Flow、SnakerFlow 不存耗时），单位秒；未办完的为空。 */
    public static Query<Map<String, Object>> elapsed() {
        return from(HiTask.class)
            .orderBy(h -> h.id())
            .select(h -> cols(h.id(), h.startTime().secondsBetween(h.endTime()).as("seconds")));
    }

    /** 每个办理人办完的任务数和总耗时，以及其中超过一小时的任务数。 */
    public static Query<Map<String, Object>> workload() {
        return from(HiTask.class)
            .where(h -> h.endTime().isNotNull())
            .groupBy(h -> h.assignee())
            .orderBy(h -> h.assignee())
            .select(h -> cols(h.assignee(), count().as("tasks"), h.duration().sum().as("totalDuration"),
                count().filter(() -> h.duration().gt(3_600_000L)).as("slow")));
    }
}

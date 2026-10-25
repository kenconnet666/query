import type { CalendarDateTime } from "@internationalized/date";
import {
  all,
  any,
  column,
  count,
  forUpdate,
  from,
  integer,
  many,
  one,
  rowNumber,
  table,
  timestamp,
  where,
  type Many,
  type One,
  type Ref,
} from "@zerodep-dev/query-core";
import { SCHEMA } from "./erp.js";

/**
 * 流程引擎读查询的对照场景：按 Flowable、Camunda 7、Activiti 等引擎的表结构简化出的几张表，
 * 以及它们最常见的读查询（待办、变量过滤、最新版本、已办、统计）用本库的写法。与 Java 的 Workflow 一一对应。
 */

// ---------- 表 ----------

/** 流程定义：同一个 key 每次部署一个新版本。 */
@table("WF_PROCDEF", { schema: SCHEMA })
export class ProcDef {
  @column("ID") id!: string;
  @column("DEF_KEY") defKey!: string;
  @column("NAME") name!: string;
  @column("VERSION", integer) version!: number;

  /** 这个定义启动的流程实例。 */
  @many(() => ProcInst, (d, p) => p.defId.eq(d.id)) instances!: Many<ProcInst>;

  /** 同一个 key 的全部版本（包括自己）。 */
  @many(() => ProcDef, (d, x) => x.defKey.eq(d.defKey)) versions!: Many<ProcDef>;
}

/** 流程实例：结束时间为空表示仍在运行；耗时在结束时写入（毫秒）。 */
@table("WF_PROCINST", { schema: SCHEMA })
export class ProcInst {
  @column("ID") id!: string;
  @column("DEF_ID") defId!: string;
  @column("BUSINESS_KEY") businessKey!: string;
  @column("START_USER") startUser!: string;
  @column("START_TIME", timestamp) startTime!: CalendarDateTime;
  @column("END_TIME", timestamp) endTime!: CalendarDateTime | null;
  @column("DURATION", integer) duration!: number | null;

  /** 所属的流程定义。 */
  @one(() => ProcDef, (p, d) => d.id.eq(p.defId)) definition!: One<ProcDef>;

  /** 流程变量。 */
  @many(() => Variable, (p, v) => v.procInstId.eq(p.id)) variables!: Many<Variable>;
}

/** 运行中的任务：有办理人时就是办理人的待办，没有时由候选人或候选组认领。 */
@table("WF_TASK", { schema: SCHEMA })
export class Task {
  @column("ID") id!: string;
  @column("NAME") name!: string;
  @column("ASSIGNEE") assignee!: string | null;
  @column("PROC_INST_ID") procInstId!: string;
  @column("DEF_ID") defId!: string;
  @column("CREATE_TIME", timestamp) createTime!: CalendarDateTime;

  /** 所属的流程定义。 */
  @one(() => ProcDef, (t, d) => d.id.eq(t.defId)) definition!: One<ProcDef>;

  /** 所属的流程实例。 */
  @one(() => ProcInst, (t, p) => p.id.eq(t.procInstId)) instance!: One<ProcInst>;

  /** 候选人和候选组。 */
  @many(() => Link, (t, l) => {
    l.taskId.eq(t.id);
    l.kind.eq("candidate");
  }) candidates!: Many<Link>;
}

/** 任务的身份链接：候选人（USER_ID）或候选组（GROUP_ID）。 */
@table("WF_LINK", { schema: SCHEMA })
export class Link {
  @column("ID") id!: string;
  @column("TASK_ID") taskId!: string;
  @column("KIND") kind!: string;
  @column("USER_ID") userId!: string | null;
  @column("GROUP_ID") groupId!: string | null;
}

/** 流程变量：按值的类型分列存放，文本在 TEXT_VALUE，整数在 LONG_VALUE。 */
@table("WF_VARIABLE", { schema: SCHEMA })
export class Variable {
  @column("ID") id!: string;
  @column("PROC_INST_ID") procInstId!: string;
  @column("NAME") name!: string;
  @column("TEXT_VALUE") textValue!: string | null;
  @column("LONG_VALUE", integer) longValue!: number | null;
}

/** 历史任务：结束时间不为空表示已办完；耗时在结束时写入（毫秒）。 */
@table("WF_HI_TASK", { schema: SCHEMA })
export class HiTask {
  @column("ID") id!: string;
  @column("NAME") name!: string;
  @column("ASSIGNEE") assignee!: string;
  @column("PROC_INST_ID") procInstId!: string;
  @column("START_TIME", timestamp) startTime!: CalendarDateTime;
  @column("END_TIME", timestamp) endTime!: CalendarDateTime | null;
  @column("DURATION", integer) duration!: number | null;
}

// ---------- 查询 ----------

/** 待办列表页面上的可选筛选条件。 */
export class TodoSearch {
  @where("eq", "definition.defKey") process?: string;
  @where("contains") name?: string;
  @where("ge", "createTime") createdFrom?: CalendarDateTime;
}

/**
 * 待办（Flowable 的 taskCandidateOrAssigned）：办理人是自己，或者没有办理人、自己或自己所在的组是候选。
 * 用户和组放在同一个 EXISTS 里；筛选条件为空的字段不产生条件。
 */
export function todo(user: string, groups: readonly string[], search: TodoSearch) {
  return from(Task)
    .where((t) =>
      any(() => {
        t.assignee.eq(user);
        all(() => {
          t.assignee.isNull();
          t.candidates.any((l) =>
            any(() => {
              l.userId.eq(user);
              l.groupId.in(groups);
            }),
          );
        });
      }),
    )
    .where(search)
    .orderBy((t) => [t.createTime.desc(), t.id])
    .select((t) => ({ id: t.id, name: t.name, assignee: t.assignee, process: t.definition.defKey, businessKey: t.instance.businessKey }));
}

/** 领取一个没有办理人的任务：最早创建的一个，锁住它，被别的事务锁住的跳过（多个工作进程同时领取时不会拿到同一个）。 */
export function claimable() {
  return from(Task)
    .where((t) => t.assignee.isNull())
    .orderBy((t) => [t.createTime, t.id])
    .limit(1)
    .with(forUpdate("skipLocked"))
    .select((t) => t.id);
}

/** 某个变量满足条件：变量名相等，值的条件写在 value 里；写进调用处的条件块。 */
export function variable(p: Ref<ProcInst>, name: string, value: (v: Ref<Variable>) => void): void {
  p.variables.any((v) => {
    v.name.eq(name);
    value(v);
  });
}

/** 运行中、金额大于 1000 且部门是 X 的流程实例：每个变量条件一个 EXISTS（Flowable 的写法）。 */
export function instancesByVariables() {
  return from(ProcInst)
    .where((p) => {
      p.endTime.isNull();
      variable(p, "amount", (v) => v.longValue.gt(1000));
      variable(p, "dept", (v) => v.textValue.eq("X"));
    })
    .orderBy((p) => p.id)
    .select((p) => p.id);
}

/** 同上，变量条件合成一个按实例分组的派生表（groupJoin）。 */
export function instancesByVariablesGrouped() {
  return from(ProcInst)
    .groupJoin((p) => p.variables)
    .where((p) => {
      p.endTime.isNull();
      variable(p, "amount", (v) => v.longValue.gt(1000));
      variable(p, "dept", (v) => v.textValue.eq("X"));
    })
    .orderBy((p) => p.id)
    .select((p) => p.id);
}

/** 同上，每个变量条件连接一次变量表（Activiti 的多别名 JOIN 写法）。 */
export function instancesByVariablesJoined() {
  return from(ProcInst)
    .join(Variable, (p, a) => {
      a.procInstId.eq(p.id);
      a.name.eq("amount");
      a.longValue.gt(1000);
    })
    .join(Variable, (p, _a, d) => {
      d.procInstId.eq(p.id);
      d.name.eq("dept");
      d.textValue.eq("X");
    })
    .where((p) => p.endTime.isNull())
    .orderBy((p) => p.id)
    .select((p) => p.id);
}

/** 每个 key 的最新版本：版本号等于同一个 key 的最大版本（相关子查询，Flowable 的 latest()），用自关联 versions 写。 */
export function latestDefinitions() {
  return from(ProcDef)
    .where((d) => d.version.eq(d.versions.max((x) => x.version)))
    .orderBy((d) => d.defKey)
    .select((d) => d.id);
}

/** 同上，按 key 分区取版本最大的一行（qualify）。 */
export function latestDefinitionsRanked() {
  return from(ProcDef)
    .qualify((d) => rowNumber().over((w) => w.partitionBy(d.defKey).orderBy(d.version.desc())).eq(1))
    .orderBy((d) => d.defKey)
    .select((d) => d.id);
}

/** 已办：某人办完的历史任务，按结束时间倒序。 */
export function done(user: string) {
  return from(HiTask)
    .where((h) => {
      h.assignee.eq(user);
      h.endTime.isNotNull();
    })
    .orderBy((h) => [h.endTime.desc(), h.id])
    .select((h) => ({ id: h.id, name: h.name, endTime: h.endTime, duration: h.duration }));
}

/** 每个流程定义的运行中、已结束实例数和平均耗时（Camunda 的定义统计），关系上的运算合成一个分组派生表。 */
export function definitionStatistics() {
  return from(ProcDef)
    .groupJoin((d) => d.instances)
    .orderBy((d) => d.id)
    .select((d) => ({
      id: d.id,
      defKey: d.defKey,
      version: d.version,
      running: d.instances.where((p) => p.endTime.isNull()).count(),
      finished: d.instances.where((p) => p.endTime.isNotNull()).count(),
      avgDuration: d.instances.avg((p) => p.duration),
    }));
}

/** 历史任务的耗时在读取时计算（Warm-Flow、SnakerFlow 不存耗时），单位秒；未办完的为空。 */
export function elapsed() {
  return from(HiTask)
    .orderBy((h) => h.id)
    .select((h) => ({ id: h.id, seconds: h.startTime.secondsBetween(h.endTime) }));
}

/** 每个办理人办完的任务数和总耗时，以及其中超过一小时的任务数。 */
export function workload() {
  return from(HiTask)
    .where((h) => h.endTime.isNotNull())
    .groupBy((h) => h.assignee)
    .orderBy((h) => h.assignee)
    .select((h) => ({
      assignee: h.assignee,
      tasks: count(),
      totalDuration: h.duration.sum(),
      slow: count().filter(() => h.duration.gt(3_600_000)),
    }));
}

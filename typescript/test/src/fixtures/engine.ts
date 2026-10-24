import { all, any, bigint, column, id, integer, many, table, text, timestamp, version, type Cond, type Many, type Ref } from "@zerodep-dev/query-core";
import { SCHEMA } from "./erp.js";

/**
 * 流程引擎写入一侧的对照场景，与 Java 的 Engine 一一对应：按自研引擎（flow 仓库）打算用的表形状简化出的几张表，
 * 主键是应用生成的长整数，时间是 UTC 的本地时间值。表由结构变更按声明建出。
 */

/** 流程实例：变量整份存成 JSON 文本；推进前先把版本加一，等于锁住这一行。 */
@table("E_INSTANCE", { schema: SCHEMA })
export class EngineInstance {
  @id @column("ID", bigint) id!: bigint;
  @column("STATUS", text, { length: 20, nullable: false }) status!: string;
  @column("VARIABLES", text, { lob: true }) variables!: string | null;
  @version @column("REV", integer, { nullable: false }) rev!: number;
  @column("STARTED_AT", timestamp) startedAt!: string | null;

  /** 实例的待办。 */
  @many(() => EngineWorkItem, (i, w) => w.instanceId.eq(i.id)) items!: Many<EngineWorkItem>;
}

/** 待办：state 为 open、done、canceled；在岗位收件箱里等认领时 assignee 为空。 */
@table("E_WORK_ITEM", { schema: SCHEMA })
export class EngineWorkItem {
  @id @column("ID", bigint) id!: bigint;
  @column("INSTANCE_ID", bigint, { nullable: false }) instanceId!: bigint;
  @column("NODE", text, { length: 40, nullable: false }) node!: string;
  @column("POOL", text, { length: 40 }) pool!: string | null;
  @column("ASSIGNEE", text, { length: 40 }) assignee!: string | null;
  @column("STATE", text, { length: 10, nullable: false }) state!: string;
  @column("CREATED_AT", timestamp) createdAt!: string | null;

  /** 候选人、候选组与被排除的人。 */
  @many(() => EngineCandidate, (w, c) => c.workItemId.eq(w.id)) candidates!: Many<EngineCandidate>;
}

/** 谁能处理（user、group）和谁被排除（excluded：退回过收件箱，不能再认领）。 */
@table("E_CANDIDATE", { schema: SCHEMA })
export class EngineCandidate {
  @id @column("ID", bigint) id!: bigint;
  @column("WORK_ITEM_ID", bigint, { nullable: false }) workItemId!: bigint;
  @column("KIND", text, { length: 10, nullable: false }) kind!: string;
  @column("VAL", text, { length: 40, nullable: false }) value!: string;
}

/** 流转记录：只追加。 */
@table("E_EVENT", { schema: SCHEMA })
export class EngineEvent {
  @id @column("ID", bigint) id!: bigint;
  @column("INSTANCE_ID", bigint, { nullable: false }) instanceId!: bigint;
  @column("TYPE", text, { length: 20, nullable: false }) type!: string;
  @column("AT", timestamp, { nullable: false }) at!: string;
}

/** 作业：到期后由工作进程领取。 */
@table("E_JOB", { schema: SCHEMA })
export class EngineJob {
  @id @column("ID", bigint) id!: bigint;
  @column("DUE_AT", timestamp, { nullable: false }) dueAt!: string;
  @column("LOCKED_BY", text, { length: 40 }) lockedBy!: string | null;
}

/** 引擎场景用到的全部表，建表与清理用。 */
export const ENGINE_TABLES = [EngineInstance, EngineWorkItem, EngineCandidate, EngineEvent, EngineJob] as const;

/** user 的待办：办理人是自己；或者没有办理人、自己或自己的组是候选，而且自己没有被排除。与 Java 的 Engine.todo 一致。 */
export function engineTodo(w: Ref<EngineWorkItem>, user: string, groups: readonly string[]): Cond {
  return all(
    w.state.eq("open"),
    any(
      w.assignee.eq(user),
      all(
        w.assignee.isNull(),
        w.candidates.any((c) => any(all(c.kind.eq("user"), c.value.eq(user)), all(c.kind.eq("group"), c.value.in(groups)))),
        w.candidates.none((c) => all(c.kind.eq("excluded"), c.value.eq(user))),
      ),
    ),
  );
}

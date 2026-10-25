import type { Decimal } from "decimal.js";
import type { CalendarDate } from "@internationalized/date";
import { bigint, column, date, decimal, id, integer, modulo, monthly, shard, table, text, type ShardConfig } from "@zerodep-dev/query-core";
import { SCHEMA } from "./erp.js";

/**
 * 同库分表的测试表，与 Java 的 testing/Shards.java 对应：按月分成 SH_EVENT_202601 到 SH_EVENT_202603 的事件，
 * 按客户取模分成 SH_ORDER_0、SH_ORDER_1 的订单。
 */

/** 按月分表的事件。 */
@table("SH_EVENT", { schema: SCHEMA })
export class ShEvent {
  @id @column("ID", bigint) id!: bigint;
  /** 发生日，分表键。 */
  @column("DAY", date, { nullable: false }) day!: CalendarDate;
  @column("AMOUNT", decimal, { precision: 10, scale: 2 }) amount!: Decimal | null;
  @column("NOTE", text, { length: 20 }) note!: string | null;
}

/** 按客户取模分表的订单。 */
@table("SH_ORDER", { schema: SCHEMA })
export class ShOrder {
  @id @column("ID", bigint) id!: bigint;
  /** 客户编号，分表键。 */
  @column("CUSTOMER", integer, { nullable: false }) customer!: number;
  @column("TOTAL", decimal, { precision: 10, scale: 2 }) total!: Decimal | null;
}

/** 按月：2026 年 1 到 3 月。 */
export const MONTHLY = monthly("day", "2026-01", "2026-03");

/** 取模：两张表。 */
export const MODULO = modulo("customer", 2);

/** 数据库选项里的两张分表。 */
export const SHARDS: readonly ShardConfig[] = [shard(ShEvent, MONTHLY), shard(ShOrder, MODULO)];

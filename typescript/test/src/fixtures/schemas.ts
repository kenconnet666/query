import type { CalendarDate } from "@internationalized/date";
import type { Decimal } from "decimal.js";
import { bigint, column, columnType, date, decimal, generated, id, insertInto, integer, migration, namedTable, table, tableBuilder, text } from "@zerodep-dev/query-core";

/**
 * 结构变更的测试表，与 Java 的 testing/Schemas.java 对应：同一张 S_ITEM 的三个版本（初版、安全的变更、有风险的变更），
 * 被它引用的 S_OWNER，以及把 LIPS 当外部表的两种声明。
 */

const SCHEMA = "QUERY_IT";

/** 被引用的表。 */
@table("S_OWNER", { schema: SCHEMA })
export class SOwner {
  @id @column("ID", bigint) id!: bigint;
  @column("NAME", text, { length: 30 }) name!: string | null;
}

/** 初版：自增主键、唯一索引、外键、默认值和注释。 */
@table("S_ITEM", {
  schema: SCHEMA,
  comment: "items",
  indexes: [{ name: "IX_S_ITEM_CODE", columns: ["code"], unique: true }],
  foreignKeys: [{ name: "FK_S_ITEM_OWNER", columns: ["ownerId"], references: () => SOwner }],
})
export class SItem {
  @id @generated @column("ID", bigint) id!: bigint;
  @column("CODE", text, { length: 20, nullable: false }) code!: string;
  @column("TITLE", text, { length: 40, comment: "title" }) title!: string | null;
  @column("PRICE", decimal, { precision: 13, scale: 3 }) price!: Decimal | null;
  @column("STATUS", text, { length: 10, nullable: false, default: "'open'" }) status!: string;
  @column("OWNER_ID", bigint) ownerId!: bigint | null;
  @column("CREATED_ON", date) createdOn!: CalendarDate | null;
}

/** 安全的变更：放宽标题和价格、加一列、按旧名把 STATUS 改名为 STATE、加一个索引。 */
@table("S_ITEM", {
  schema: SCHEMA,
  comment: "items",
  indexes: [
    { name: "IX_S_ITEM_CODE", columns: ["code"], unique: true },
    { name: "IX_S_ITEM_OWNER", columns: ["ownerId"] },
  ],
  foreignKeys: [{ name: "FK_S_ITEM_OWNER", columns: ["ownerId"], references: () => SOwner }],
})
export class SItemV2 {
  @id @generated @column("ID", bigint) id!: bigint;
  @column("CODE", text, { length: 20, nullable: false }) code!: string;
  @column("TITLE", text, { length: 80, comment: "title" }) title!: string | null;
  @column("PRICE", decimal, { precision: 15, scale: 3 }) price!: Decimal | null;
  @column("STATE", text, { length: 10, nullable: false, default: "'open'", previousName: "STATUS" }) state!: string;
  @column("OWNER_ID", bigint) ownerId!: bigint | null;
  @column("CREATED_ON", date) createdOn!: CalendarDate | null;
  @column("NOTE", text, { length: 100 }) note!: string | null;
}

/** 有风险的变更：缩窄标题、创建日期改成 NOT NULL。 */
@table("S_ITEM", {
  schema: SCHEMA,
  comment: "items",
  indexes: [
    { name: "IX_S_ITEM_CODE", columns: ["code"], unique: true },
    { name: "IX_S_ITEM_OWNER", columns: ["ownerId"] },
  ],
  foreignKeys: [{ name: "FK_S_ITEM_OWNER", columns: ["ownerId"], references: () => SOwner }],
})
export class SItemV3 {
  @id @generated @column("ID", bigint) id!: bigint;
  @column("CODE", text, { length: 20, nullable: false }) code!: string;
  @column("TITLE", text, { length: 10, comment: "title" }) title!: string | null;
  @column("PRICE", decimal, { precision: 15, scale: 3 }) price!: Decimal | null;
  @column("STATE", text, { length: 10, nullable: false, default: "'open'" }) state!: string;
  @column("OWNER_ID", bigint) ownerId!: bigint | null;
  @column("CREATED_ON", date, { nullable: false }) createdOn!: CalendarDate;
  @column("NOTE", text, { length: 100 }) note!: string | null;
}

/** 把 LIPS 当作外部表：只核对这两列存在、类型相容。 */
@table("LIPS", { schema: SCHEMA, external: true })
export class LipsExternal {
  @column("VBELN") delivery!: string;
  @column("LFIMG", decimal) quantity!: Decimal;
}

/** 外部表的错误声明：一列不存在，一列类型装不下。 */
@table("LIPS", { schema: SCHEMA, external: true })
export class LipsBroken {
  @column("NO_SUCH") missing!: string;
  @column("LFIMG", date) quantityAsDate!: CalendarDate;
}

/** 迁移建的表，供迁移里的数据步骤写入。 */
@table("M_TASK", { schema: SCHEMA })
export class MTask {
  @id @column("ID") id!: string;
  @column("TITLE") title!: string | null;
  @column("PRIORITY", integer) priority!: number | null;
}

/** 第一个迁移：建 M_TASK。 */
export const CREATE_TASK = migration("1", "create M_TASK", (steps) =>
  steps.createTable(tableBuilder(SCHEMA, "M_TASK").column("ID", columnType("TEXT", 10)).column("TITLE", columnType("TEXT", 40)).primaryKey("ID").build()),
);

/** 第二个迁移：加优先级列并写入一行种子数据。 */
export const ADD_PRIORITY = migration("2", "add PRIORITY", (steps) =>
  steps
    .addColumn(namedTable(SCHEMA, "M_TASK"), { name: "PRIORITY", type: columnType("INTEGER"), nullable: true, defaultValue: "0", identity: false, comment: "", previousName: "" })
    .execute(insertInto(MTask).values({ id: "T1", title: "seed", priority: 5 })),
);

/** 改过内容的第二个迁移：校验和与已执行的不同。 */
export const ADD_PRIORITY_CHANGED = migration("2", "add PRIORITY", (steps) =>
  steps.addColumn(namedTable(SCHEMA, "M_TASK"), { name: "PRIORITY", type: columnType("BIGINT"), nullable: true, defaultValue: "0", identity: false, comment: "", previousName: "" }),
);

/** 会失败的迁移。 */
export const BROKEN = migration("3", "broken", (steps) => steps.sql(`UPDATE ${SCHEMA}.NO_SUCH_TABLE SET X = 1`));

import { all, column, deleteFrom, from, generated, id, insertInto, integer, logicDelete, onInsert, onUpdate, plus, readOnly, table, timestamp, update, val, version, writeOnce, type Database, type Fill, type InsertRow } from "@zerodep-dev/query-core";
import { SCHEMA } from "./erp.js";

/** 写入场景的表和语句：单元测试、金样例和三库实库场景共用，与 Java 的 Writes 一一对应。 */

/** 带租户列的表，全局过滤器按它匹配。 */
export abstract class Tenanted {
  @column("TENANT") tenant!: string;
}

/** 便签：主键是文本，数量和状态用于更新的场景。 */
@table("W_NOTE", { schema: SCHEMA })
export class Note extends Tenanted {
  @id @column("ID") id!: string;
  @column("TITLE") title!: string | null;
  @column("QTY", integer) qty!: number;
  @column("STATUS") status!: string;
}

/** 文档：带乐观锁的版本列。 */
@table("W_DOC", { schema: SCHEMA })
export class Doc {
  @id @column("ID") id!: string;
  @column("TITLE") title!: string | null;
  @version @column("VERSION", integer) version!: number;
  /** 来源：由数据库默认值维护，本库从不写。 */
  @readOnly @column("SOURCE") source!: string | null;
}

/** 编号由数据库生成的表（IDENTITY / AUTO_INCREMENT）。 */
@table("W_SEQ", { schema: SCHEMA })
export class Seq {
  @id @generated @column("ID", integer) id!: number;
  @column("NAME") name!: string;
}

/** 带审计列的表：创建时间、修改时间和修改人由数据库实例的 fills 自动填。 */
export abstract class Audited {
  @writeOnce @column("CREATED_AT", timestamp) createdAt!: string | null;
  @column("UPDATED_AT", timestamp) updatedAt!: string | null;
  @column("UPDATED_BY") updatedBy!: string | null;
}

/** 工单：逻辑删除（DELETED 为 1 表示已删除）加审计列。 */
@table("W_TICKET", { schema: SCHEMA })
export class Ticket extends Audited {
  @id @column("ID") id!: string;
  @column("TITLE") title!: string;
  @logicDelete() @column("DELETED", integer) deleted!: number;
}

/** 归档：逻辑删除列记录删除时间。 */
@table("W_ARCHIVE", { schema: SCHEMA })
export class Archive {
  @id @column("ID") id!: string;
  @logicDelete() @column("DELETED_AT", timestamp) deletedAt!: string | null;
}

/** 审计填充用的固定时间，便于核对。 */
export const CREATED = "2026-01-01 08:00:00";

/** 同上，修改时间。 */
export const UPDATED = "2026-01-02 09:30:00";

/** 审计填充：插入时填创建时间，更新时填修改时间和修改人。 */
export const audit: readonly Fill[] = [
  onInsert(Audited, (a) => a.createdAt, () => CREATED),
  onUpdate(Audited, (a) => a.updatedAt, () => UPDATED),
  onUpdate(Audited, (a) => a.updatedBy, () => "bob"),
];

/** 删除工单：表上有逻辑删除列，所以是把 DELETED 设为 1 的 UPDATE。 */
export function deleteTicket(ticketId: string) {
  return deleteFrom(Ticket).where((t) => t.id.eq(ticketId));
}

/** 按主键改标题：版本号加一，版本不对时没有改到行。 */
export function renameDoc(row: InsertRow<Doc>) {
  return update(Doc).byId(row);
}

/** 按主键和版本删除文档。 */
export function deleteDoc(row: InsertRow<Doc>) {
  return deleteFrom(Doc).byId(row);
}

/** 插入文档，主键已有时只更新标题。 */
export function upsertDoc(row: InsertRow<Doc>) {
  return insertInto(Doc)
    .values(row)
    .onConflict()
    .update((d) => d.title);
}

/** 插入文档，主键已有时保留原来的行。 */
export function insertDocIfAbsent(row: InsertRow<Doc>) {
  return insertInto(Doc)
    .values(row)
    .onConflict((d) => d.id)
    .doNothing();
}

/** 插入两行，第二行的标题为 NULL。 */
export function insertNotes() {
  return insertInto(Note).values({ id: "N1", tenant: "T1", title: "first", qty: 1, status: "open" }, { id: "N2", tenant: "T1", title: null, qty: 2, status: "open" });
}

/** 把开着的便签数量加 10 并关闭："比较后更新"。 */
export function closeOpenNote(noteId: string) {
  return update(Note)
    .set((n) => ({ qty: plus(n.qty, val(10)), status: "closed" }))
    .where((n) => all(n.id.eq(noteId), n.status.eq("open")))
    .expect(1);
}

/** 删除关闭了的便签。 */
export function deleteClosed() {
  return deleteFrom(Note).where((n) => n.status.eq("closed"));
}

/** 把查询结果插入表里：复制 N1 为 N9。 */
export function copyNote() {
  return insertInto(Note).from(
    from(Note)
      .where((n) => n.id.eq("N1"))
      .select((n) => ({ id: val("N9"), tenant: n.tenant, title: n.title, qty: n.qty, status: n.status })),
  );
}

/** 全部便签的编号，按编号排序。 */
export function ids(db: Database): Promise<string[]> {
  return db.list(
    from(Note)
      .orderBy((n) => n.id)
      .select((n) => n.id),
  );
}

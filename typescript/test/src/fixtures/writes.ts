import { all, column, deleteFrom, from, id, insertInto, integer, plus, table, update, val, type Database } from "@zerodep-dev/query-core";
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

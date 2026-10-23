import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { deleteFrom, dryRunDriver, filter, forUpdate, from, insertInto, update, UnexpectedRowCountError } from "@zerodep-dev/query-core";
import { FakeDriver, TestDatabase, TestDialect } from "../fixtures/support.js";
import { claimable } from "../fixtures/workflow.js";
import { closeOpenNote, copyNote, deleteClosed, deleteDoc, Doc, insertNotes, Note, renameDoc, Seq, Tenanted, upsertDoc } from "../fixtures/writes.js";

const db = new TestDatabase();
const tenant = new TestDatabase({ filters: [filter(Tenanted, (t) => t.tenant.eq("T1"))] });

describe("写入", () => {
  it("插入每行一条语句，null 写成 NULL 参数", () => {
    const r = db.render(insertNotes());
    assert.equal(r.length, 2);
    assert.equal(r[0]!.sql, "INSERT INTO QUERY_IT.W_NOTE (ID, TENANT, TITLE, QTY, STATUS) VALUES (?, ?, ?, ?, ?)");
    assert.equal(r[1]!.sql, r[0]!.sql);
    assert.deepEqual(r[1]!.parameters, ["N2", "T1", null, 2, "open"]);
  });

  it("行的字段不是表的列，或各行字段不同时报错", () => {
    assert.throws(() => insertInto(Note).values({ id: "N1", color: "red" } as never), /color' has no column in Note/);
    assert.throws(() => insertInto(Note).values({ id: "N1", qty: 1 }, { id: "N2" }), /rows must have the same fields/);
  });

  it("从查询插入时按列名对应表的列", () => {
    assert.equal(
      db.render(copyNote())[0]!.sql,
      `INSERT INTO QUERY_IT.W_NOTE (ID, TENANT, TITLE, QTY, STATUS)
SELECT 'N9' AS "id", NOTE.TENANT AS "tenant", NOTE.TITLE AS "title", NOTE.QTY AS "qty", NOTE.STATUS AS "status"
FROM QUERY_IT.W_NOTE NOTE
WHERE NOTE.ID = ?`,
    );
  });

  it("更新的 SET 可以是值或表达式，WHERE 带上全局过滤器", () => {
    const r = tenant.render(closeOpenNote("N1"))[0]!;
    assert.equal(
      r.sql,
      `UPDATE QUERY_IT.W_NOTE NOTE
SET QTY = (NOTE.QTY + ?), STATUS = ?
WHERE NOTE.TENANT = ? AND NOTE.ID = ? AND NOTE.STATUS = ?`,
    );
    assert.deepEqual(r.parameters, [10, "closed", "T1", "N1", "open"]);
    assert.equal(closeOpenNote("N1").expected, 1);
  });

  it("没有 where 的更新和删除报错，all() 放行", () => {
    assert.throws(() => db.render(update(Note).set(() => ({ status: "x" }))), /would touch every row of Note/);
    assert.throws(() => db.render(deleteFrom(Note)), /would touch every row/);
    assert.throws(() => db.render(deleteFrom(Note).skipEmpty().where((n) => n.id.eq(null))), /would touch every row/, "conditions dropped by skipEmpty do not count");
    assert.equal(db.render(deleteFrom(Note).all())[0]!.sql, "DELETE FROM QUERY_IT.W_NOTE NOTE");
  });

  it("skipEmpty 省略值为空的 SET 项，全部省略时没有语句", () => {
    const u = update(Note)
      .skipEmpty()
      .set(() => ({ title: null, status: "x" }))
      .where((n) => n.id.eq("N1"));
    assert.equal(db.render(u)[0]!.sql, "UPDATE QUERY_IT.W_NOTE NOTE\nSET STATUS = ?\nWHERE NOTE.ID = ?");
    assert.deepEqual(db.render(update(Note).skipEmpty().set(() => ({ title: null })).where((n) => n.id.eq("N1"))), []);
    assert.equal(db.render(update(Note).set(() => ({ title: null })).where((n) => n.id.eq("N1")))[0]!.sql, "UPDATE QUERY_IT.W_NOTE NOTE\nSET TITLE = ?\nWHERE NOTE.ID = ?");
  });

  it("删除可以用条件对象，全局过滤器可以关闭", () => {
    assert.equal(tenant.render(deleteFrom(Note).where({ status: "closed" }))[0]!.sql, "DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.TENANT = ? AND NOTE.STATUS = ?");
    assert.equal(tenant.render(deleteFrom(Note).unfiltered().where({ status: "closed" }))[0]!.sql, "DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.STATUS = ?");
  });

  it("按主键更新时 SET 其余字段，带版本时检查并递增版本", () => {
    const r = db.render(renameDoc({ id: "D1", title: "new", version: 3 }))[0]!;
    assert.equal(r.sql, "UPDATE QUERY_IT.W_DOC DOC\nSET TITLE = ?, VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?");
    assert.deepEqual(r.parameters, ["new", "D1", 3]);
    assert.equal(update(Doc).byId({ id: "D1", title: "a", version: 1 }, { id: "D2", title: "b", version: 1 }).expected, 2);
    assert.equal(
      db.render(update(Doc).skipEmpty().byId({ id: "D1", title: null, version: 3 }))[0]!.sql,
      "UPDATE QUERY_IT.W_DOC DOC\nSET VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?",
      "skipEmpty leaves null fields alone",
    );
  });

  it("按主键删除时带上版本，行缺主键或版本时报错", () => {
    assert.equal(db.render(deleteDoc({ id: "D1", version: 2 }))[0]!.sql, "DELETE FROM QUERY_IT.W_DOC DOC\nWHERE DOC.ID = ? AND DOC.VERSION = ?");
    assert.equal(db.render(deleteFrom(Note).byId({ id: "N1" }))[0]!.sql, "DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.ID = ?", "without @version only the key locates the row");
    assert.throws(() => deleteFrom(Doc).byId({ id: "D1" }), /needs the version of every row/);
    assert.throws(() => deleteFrom(Doc).byId({ title: "x", version: 1 }), /needs the id of every row/);
  });

  it("生成主键的列不插入，只有带生成列的表能取回生成的值", async () => {
    assert.equal(db.render(insertInto(Seq).values({ name: "a" }))[0]!.sql, "INSERT INTO QUERY_IT.W_SEQ (NAME) VALUES (?)");
    const dry = new TestDatabase({}, dryRunDriver());
    assert.deepEqual(await dry.insertKeys(insertInto(Seq).values({ name: "a" })), [null]);
    await assert.rejects(dry.insertKeys(insertNotes()), /has no @generated column/);
  });

  it("插入或更新由方言写出，键必须是插入的列", () => {
    assert.throws(() => db.render(upsertDoc({ id: "D1", title: "a", version: 1 })), /has no insert-or-update statement/);
    assert.throws(() => db.render(insertInto(Doc).values({ title: "a" }).onConflict().doNothing()), /must be one of the inserted columns/);
  });

  it("加锁读写在语句末尾", () => {
    assert.ok(claimable().toSql(new TestDialect()).sql.endsWith("LIMIT 1\nFOR UPDATE SKIP LOCKED"));
    assert.ok(
      from(Note)
        .with(forUpdate())
        .select((n) => n.id)
        .toSql(new TestDialect())
        .sql.endsWith("\nFOR UPDATE"),
    );
  });

  it("执行时影响的行数与期望不符则报错，空跑不检查", async () => {
    const driver = new FakeDriver(() => ({ columns: [], rows: [], affected: 0 }));
    await assert.rejects(new TestDatabase({}, driver).execute(closeOpenNote("N1")), UnexpectedRowCountError);
    const dry = new TestDatabase({}, dryRunDriver());
    const events = await dry.capture(async () => {
      assert.equal(await dry.execute(insertNotes()), 0);
      assert.equal(await dry.execute(closeOpenNote("N1")), 0);
      assert.equal(await dry.execute(insertInto(Note)), 0);
    });
    assert.equal(events.length, 3, "two inserted rows and the update; an empty insert runs nothing");
    assert.equal(await dry.transaction(async () => 1), 1);
    assert.equal(db.render(deleteClosed()).length, 1);
  });
});

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { deleteFrom, dryRunDriver, filter, forUpdate, from, generate, insertInto, JoinUpdate, update, UnexpectedRowCountError } from "@zerodep-dev/query-core";
import { FakeDriver, TestDatabase, TestDialect } from "../fixtures/support.js";
import { Lips } from "../fixtures/erp.js";
import { claimable } from "../fixtures/workflow.js";
import { AppNote, Archive, audit, Code, syncNoteTitles, closeOpenNote, copyNote, CREATED, deleteClosed, deleteDoc, deleteTicket, Doc, insertNotes, Note, renameDoc, Seq, Tenanted, Ticket, upsertDoc } from "../fixtures/writes.js";

const db = new TestDatabase();
const tenant = new TestDatabase({ filters: [filter(Tenanted, (t) => t.tenant.eq("T1"))] });

describe("写入", () => {
  it("逻辑删除的表查询时只看未删除的行且删除改写成 UPDATE", () => {
    assert.ok(db.render(from(Ticket).select((t) => t.id)).sql.endsWith("WHERE TICKET.DELETED = ?"));
    const [del] = db.render(deleteTicket("T1"));
    assert.equal(del!.sql, "UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?");
    assert.deepEqual(del!.parameters, [1, 0, "T1"]);
    assert.equal(db.render(deleteTicket("T1").physically())[0]!.sql, "DELETE FROM QUERY_IT.W_TICKET TICKET\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?");
    assert.equal(db.render(deleteTicket("T1").physically().unfiltered())[0]!.sql, "DELETE FROM QUERY_IT.W_TICKET TICKET\nWHERE TICKET.ID = ?");
    assert.ok(db.render(from(Ticket).unfiltered().select((t) => t.id)).sql.endsWith("FROM QUERY_IT.W_TICKET TICKET"));
    assert.equal(
      db.render(deleteFrom(Archive).where((a) => a.id.eq("A1")))[0]!.sql,
      "UPDATE QUERY_IT.W_ARCHIVE ARCHIVE\nSET DELETED_AT = CURRENT_TIMESTAMP\nWHERE ARCHIVE.DELETED_AT IS NULL AND ARCHIVE.ID = ?",
    );
  });

  it("审计填充在插入和更新时写入且插入时补上未删除的标记", () => {
    const audited = new TestDatabase({ fills: audit });
    const [insert] = audited.render(insertInto(Ticket).values({ id: "T1", title: "a" }));
    assert.equal(insert!.sql, "INSERT INTO QUERY_IT.W_TICKET (ID, TITLE, CREATED_AT, DELETED) VALUES (?, ?, ?, ?)");
    assert.deepEqual(insert!.parameters, ["T1", "a", "2026-01-01 08:00:00", 0]);
    const [changed] = audited.render(
      update(Ticket)
        .set(() => ({ title: "b", updatedBy: "me" }))
        .where((t) => t.id.eq("T1")),
    );
    assert.equal(changed!.sql, "UPDATE QUERY_IT.W_TICKET TICKET\nSET TITLE = ?, UPDATED_AT = ?, UPDATED_BY = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?");
    assert.deepEqual(changed!.parameters, ["b", "2026-01-02 09:30:00", "bob", 0, "T1"], "the fill wins over the statement's own value");
    assert.equal(audited.render(deleteTicket("T1"))[0]!.sql, "UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?, UPDATED_AT = ?, UPDATED_BY = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?");
    assert.equal(audited.render(insertNotes())[0]!.sql, "INSERT INTO QUERY_IT.W_NOTE (ID, TENANT, TITLE, QTY, STATUS) VALUES (?, ?, ?, ?, ?)", "tables outside Audited get no fill");
  });

  it("插入每行一条语句，null 写成 NULL 参数", () => {
    const r = db.render(insertNotes());
    assert.equal(r.length, 2);
    assert.equal(r[0]!.sql, "INSERT INTO QUERY_IT.W_NOTE (ID, TENANT, TITLE, QTY, STATUS) VALUES (?, ?, ?, ?, ?)");
    assert.equal(r[1]!.sql, r[0]!.sql);
    assert.deepEqual(r[1]!.parameters, ["N2", "T1", null, 2, "open"]);
  });

  it("生成器只给值为空的行生成值", () => {
    let next = 0;
    const gen = new TestDatabase({ fills: [generate(Note, (n) => n.id, () => `G${++next}`)] });
    const r = gen.render(insertInto(Note).values({ tenant: "T1", title: "a" }, { tenant: "T1", title: "b" }));
    assert.equal(r[0]!.sql, "INSERT INTO QUERY_IT.W_NOTE (TENANT, TITLE, ID) VALUES (?, ?, ?)");
    assert.deepEqual(r.map((s) => s.parameters), [["T1", "a", "G1"], ["T1", "b", "G2"]]);
    assert.deepEqual(gen.render(insertInto(Note).values({ id: "N2", tenant: "T1", title: "c" }))[0]!.parameters, ["N2", "T1", "c"]);
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

  it("条件经过导航关系时包成 EXISTS，而 SET 经过导航关系时报错", () => {
    const [r] = db.render(
      update(Lips)
        .set(() => ({ unit: "EA" }))
        .where((l) => l.mara.manufacturer.eq("MK1")),
    );
    assert.equal(
      r!.sql,
      "UPDATE QUERY_IT.LIPS LIPS\nSET VRKME = ?\nWHERE EXISTS (SELECT 1\nFROM (SELECT 1 AS ONE) SEED\nLEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT\nWHERE MARA.MFRNR = ?)",
    );
    assert.equal(db.render(deleteFrom(Lips).where((l) => l.delivery.eq("D1")))[0]!.sql, "DELETE FROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VBELN = ?");
    assert.throws(
      () =>
        db.render(
          update(Lips)
            .set((l) => ({ unit: l.mara.manufacturer as never }))
            .where((l) => l.delivery.eq("D1")),
        ),
      /SET reads a related table/,
    );
  });

  it("diffFrom 只更新变了的字段，没有变化时不发语句", () => {
    const changed = update(Doc).byId({ id: "D1", title: "new", version: 3 }).diffFrom({ id: "D1", title: "old", version: 3 });
    assert.equal(db.render(changed)[0]!.sql, "UPDATE QUERY_IT.W_DOC DOC\nSET TITLE = ?, VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?");
    const same = update(Doc)
      .byId({ id: "D1", title: "a", version: 3 }, { id: "D2", title: "b", version: 1 })
      .diffFrom({ id: "D1", title: "a", version: 3 }, { id: "D2", title: "c", version: 1 });
    assert.equal(db.render(same).length, 1, "the unchanged row sends nothing");
    assert.equal(same.expected, 1);
    assert.throws(() => update(Doc).byId({ id: "D1", title: "a", version: 3 }).diffFrom({ id: "D1", title: "a", version: 2 }), /different version/);
    assert.throws(() => update(Doc).diffFrom({ id: "D1", title: "a", version: 3 }), /call byId/);
  });

  it("只读列从不写，只在插入时写的列在按主键更新时跳过", () => {
    const full = { id: "D1", title: "a", version: 1, source: "app" };
    assert.equal(db.render(insertInto(Doc).values(full))[0]!.sql, "INSERT INTO QUERY_IT.W_DOC (ID, TITLE, VERSION) VALUES (?, ?, ?)");
    assert.equal(db.render(update(Doc).byId(full))[0]!.sql, "UPDATE QUERY_IT.W_DOC DOC\nSET TITLE = ?, VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?");
    assert.throws(() => update(Doc).set(() => ({ source: "x" })), /Doc\.source is @readOnly/);
    assert.equal(
      db.render(update(Ticket).byId({ id: "T1", title: "a", createdAt: CREATED }))[0]!.sql,
      "UPDATE QUERY_IT.W_TICKET TICKET\nSET TITLE = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
    );
    assert.equal(
      db.render(insertInto(Ticket).values({ id: "T1", title: "a", createdAt: CREATED }))[0]!.sql,
      "INSERT INTO QUERY_IT.W_TICKET (ID, TITLE, CREATED_AT, DELETED) VALUES (?, ?, ?, ?)",
      "inserts still write it",
    );
    assert.equal(
      db.render(
        update(Ticket)
          .set(() => ({ createdAt: CREATED }))
          .where((t) => t.id.eq("T1")),
      )[0]!.sql,
      "UPDATE QUERY_IT.W_TICKET TICKET\nSET CREATED_AT = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
      "an explicit set(...) may still change it",
    );
  });

  it("联表写入只在方言包里提供，没有 where 时报错", () => {
    assert.throws(() => db.render(syncNoteTitles()), /has no join update/);
    assert.throws(
      () =>
        db.render(
          JoinUpdate.of(Note, Doc, (n, d) => n.id.eq(d.id))
            .set((_n, d) => ({ title: d.title })),
        ),
      /without where/,
    );
    assert.throws(() => db.render(JoinUpdate.of(Note, Doc, (n, d) => n.id.eq(d.id)).all()), /nothing to SET/);
    assert.throws(
      () =>
        db.render(
          JoinUpdate.of(Lips, Doc, (l, d) => l.delivery.eq(d.id))
            .set((_l, d) => ({ unit: d.title as never }))
            .where((l) => l.mara.manufacturer.eq("MK1")),
        ),
      /cannot follow relations/,
    );
  });

  it("具名过滤器可以单独关闭，withDeleted 只关闭逻辑删除", () => {
    const named = new TestDatabase({ filters: [filter("tenant", Tenanted, (t) => t.tenant.eq("T1"))] });
    assert.ok(named.render(from(Note).select((n) => n.id)).sql.endsWith("WHERE NOTE.TENANT = ?"));
    assert.ok(
      named
        .render(
          from(Note)
            .unfiltered("tenant")
            .select((n) => n.id),
        )
        .sql.endsWith("FROM QUERY_IT.W_NOTE NOTE"),
    );
    assert.equal(
      named.render(
        deleteFrom(Note)
          .unfiltered("tenant")
          .where((n) => n.id.eq("N1")),
      )[0]!.sql,
      "DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.ID = ?",
    );
    assert.throws(
      () =>
        named.render(
          from(Note)
            .unfiltered("tennant")
            .select((n) => n.id),
        ),
      /names no configured filter; known: tenant/,
    );
    assert.ok(
      from(Ticket)
        .withDeleted()
        .select((t) => t.id)
        .toSql(new TestDialect())
        .sql.endsWith("FROM QUERY_IT.W_TICKET TICKET"),
    );
    assert.equal(db.render(deleteTicket("T1").withDeleted())[0]!.sql, "UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?\nWHERE TICKET.ID = ?");
  });

  it("schema 按数据库实例的映射换成实际的名字", () => {
    const mapped = new TestDatabase({ schemas: { QUERY_IT: "PROD" } });
    assert.ok(mapped.render(from(Note).select((n) => n.id)).sql.includes("FROM PROD.W_NOTE NOTE"));
    assert.equal(mapped.render(insertNotes())[0]!.sql, "INSERT INTO PROD.W_NOTE (ID, TENANT, TITLE, QTY, STATUS) VALUES (?, ?, ?, ?, ?)");
    assert.ok(mapped.render(closeOpenNote("N1"))[0]!.sql.startsWith("UPDATE PROD.W_NOTE NOTE"));
    const ruled = new TestDatabase({ schemas: (s) => (s === "APP" ? "QUERY_IT" : `${s}_T`) });
    assert.ok(ruled.render(from(Note).select((n) => n.id)).sql.includes("FROM QUERY_IT_T.W_NOTE NOTE"));
    assert.ok(ruled.render(from(AppNote).select((n) => n.id)).sql.includes("FROM QUERY_IT.W_NOTE APPNOTE"));
  });

  it("withoutFills 不套用审计填充", () => {
    const audited = new TestDatabase({ fills: audit });
    assert.equal(
      audited.render(insertInto(Ticket).values({ id: "T1", title: "a" }).withoutFills())[0]!.sql,
      "INSERT INTO QUERY_IT.W_TICKET (ID, TITLE, DELETED) VALUES (?, ?, ?)",
    );
    assert.equal(
      audited.render(
        update(Ticket)
          .set(() => ({ title: "b" }))
          .where((t) => t.id.eq("T1"))
          .withoutFills(),
      )[0]!.sql,
      "UPDATE QUERY_IT.W_TICKET TICKET\nSET TITLE = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
    );
    assert.equal(audited.render(deleteTicket("T1").withoutFills())[0]!.sql, "UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?");
  });

  it("逻辑删除记毫秒数时删除写此刻的毫秒数", () => {
    const before = BigInt(Date.now());
    const [r] = db.render(deleteFrom(Code).where((c) => c.code.eq("A")));
    assert.equal(r!.sql, "UPDATE QUERY_IT.W_CODE CODE\nSET DELETED = ?\nWHERE CODE.DELETED = ? AND CODE.CODE = ?");
    assert.ok((r!.parameters[0] as bigint) >= before);
    assert.deepEqual(r!.parameters.slice(1), [0, "A"]);
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

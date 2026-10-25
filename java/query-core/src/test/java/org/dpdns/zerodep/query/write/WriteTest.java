package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.QueryListener;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.dpdns.zerodep.query.testing.Writes.Note;
import org.dpdns.zerodep.query.testing.Writes.NoteRow;
import org.dpdns.zerodep.query.testing.Writes.Tenanted;
import org.dpdns.zerodep.query.testing.Writes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("写入")
class WriteTest {
    private static final TestDialect DIALECT = new TestDialect();

    @Test
    void 插入每行一条语句且null写成NULL参数() {
        List<Rendered> r = Writes.insertNotes().toSql(DIALECT);
        assertEquals(2, r.size());
        assertEquals("INSERT INTO QUERY_IT.W_NOTE (ID, TENANT, TITLE, QTY, STATUS) VALUES (?, ?, ?, ?, ?)", r.get(0).sql());
        assertEquals(r.get(0).sql(), r.get(1).sql());
        assertEquals(Arrays.asList("N2", "T1", null, 2, "open"), r.get(1).parameters());
    }

    @Test
    void 生成器只给值为空的行生成值() {
        AtomicInteger next = new AtomicInteger();
        Database db = TestDatabase.dryRun(c -> c.generate(Note.class, n -> n.id(), () -> "G" + next.incrementAndGet()));
        List<Rendered> r = db.render(insertInto(Note.class).values(new NoteRow(null, "T1", "a", 1, "open"), new NoteRow("N2", "T1", "b", 2, "open")));
        assertEquals(Arrays.asList("G1", "T1", "a", 1, "open"), r.get(0).parameters());
        assertEquals("N2", r.get(1).parameters().get(0));
        record Untitled(String tenant, String title) { }
        Rendered missing = db.render(insertInto(Note.class).values(new Untitled("T1", "c"))).get(0);
        assertEquals("INSERT INTO QUERY_IT.W_NOTE (TENANT, TITLE, ID) VALUES (?, ?, ?)", missing.sql());
        assertEquals(List.of("T1", "c", "G2"), missing.parameters());
    }

    @Test
    void 行的属性在表里找不到或类型不对时报错() {
        record Stray(String id, String color) { }
        var e = assertThrows(IllegalArgumentException.class, () -> insertInto(Note.class).values(new Stray("N1", "red")));
        assertTrue(e.getMessage().contains("Stray.color 'color' has no column in Note"), e.getMessage());
        record Wrong(String id, String qty) { }
        e = assertThrows(IllegalArgumentException.class, () -> insertInto(Note.class).values(new Wrong("N1", "1")));
        assertEquals("Wrong.qty is String but Note.qty() holds Integer", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> insertInto(Note.class).values(new NoteRow("N1", "T1", "a", 1, "open"), new Stray("N2", "b")));
    }

    @Test
    void 从查询插入时按列名对应表的列() {
        assertEquals("""
            INSERT INTO QUERY_IT.W_NOTE (ID, TENANT, TITLE, QTY, STATUS)
            SELECT 'N9' AS "id", NOTE.TENANT AS "tenant", NOTE.TITLE AS "title", NOTE.QTY AS "qty", NOTE.STATUS AS "status"
            FROM QUERY_IT.W_NOTE NOTE
            WHERE NOTE.ID = ?""", Writes.copyNote().toSql(DIALECT).get(0).sql());
    }

    @Test
    void 更新的SET可以是值或表达式且WHERE带上全局过滤器() {
        Database db = new TestDatabase(c -> c.filter(Tenanted.class, t -> t.tenant().eq("T1")));
        Rendered r = db.render(Writes.closeOpenNote("N1")).get(0);
        assertEquals("""
            UPDATE QUERY_IT.W_NOTE NOTE
            SET QTY = (NOTE.QTY + ?), STATUS = ?
            WHERE NOTE.TENANT = ? AND NOTE.ID = ? AND NOTE.STATUS = ?""", r.sql());
        assertEquals(List.of(10, "closed", "T1", "N1", "open"), r.parameters());
        assertEquals(1L, Writes.closeOpenNote("N1").expected());
    }

    @Test
    void 没有where的更新和删除报错而all放行() {
        assertThrows(IllegalStateException.class, () -> update(Note.class).set(n -> n.status().to("x")).toSql(DIALECT));
        assertThrows(IllegalStateException.class, () -> deleteFrom(Note.class).toSql(DIALECT));
        String none = null;
        assertThrows(IllegalStateException.class, () -> deleteFrom(Note.class).skipEmpty().where(n -> n.id().eq(none)).toSql(DIALECT),
            "conditions dropped by skipEmpty do not count");
        assertEquals("DELETE FROM QUERY_IT.W_NOTE NOTE", deleteFrom(Note.class).all().toSql(DIALECT).get(0).sql());
        assertEquals("UPDATE QUERY_IT.W_NOTE NOTE\nSET STATUS = ?", update(Note.class).set(n -> n.status().to("x")).all().toSql(DIALECT).get(0).sql());
    }

    @Test
    void skipEmpty省略值为空的SET项且全部省略时没有语句() {
        String none = null;
        Update<Note> u = update(Note.class).skipEmpty().set(n -> n.title().to(none)).set(n -> n.status().to("x")).where(n -> n.id().eq("N1"));
        assertEquals("UPDATE QUERY_IT.W_NOTE NOTE\nSET STATUS = ?\nWHERE NOTE.ID = ?", u.toSql(DIALECT).get(0).sql());
        assertTrue(update(Note.class).skipEmpty().set(n -> n.title().to(none)).where(n -> n.id().eq("N1")).toSql(DIALECT).isEmpty());
        assertEquals("UPDATE QUERY_IT.W_NOTE NOTE\nSET TITLE = ?\nWHERE NOTE.ID = ?",
            update(Note.class).set(n -> n.title().to(none)).where(n -> n.id().eq("N1")).toSql(DIALECT).get(0).sql(), "without skipEmpty null sets NULL");
    }

    @Test
    void 删除可以用条件对象且全局过滤器可以关闭() {
        record Search(String status) { }
        Database db = new TestDatabase(c -> c.filter(Tenanted.class, t -> t.tenant().eq("T1")));
        assertEquals("DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.TENANT = ? AND NOTE.STATUS = ?",
            db.render(deleteFrom(Note.class).where(new Search("closed"))).get(0).sql());
        assertEquals("DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.STATUS = ?",
            db.render(deleteFrom(Note.class).unfiltered().where(new Search("closed"))).get(0).sql());
    }

    @Test
    void 按主键更新时SET其余属性且带版本时检查并递增版本() {
        List<Rendered> r = Writes.renameDoc(new Writes.DocRow("D1", "new", 3)).toSql(DIALECT);
        assertEquals("""
            UPDATE QUERY_IT.W_DOC DOC
            SET TITLE = ?, VERSION = (DOC.VERSION + 1)
            WHERE DOC.ID = ? AND DOC.VERSION = ?""", r.get(0).sql());
        assertEquals(List.of("new", "D1", 3), r.get(0).parameters());
        assertEquals(2L, update(Writes.Doc.class).byId(new Writes.DocRow("D1", "a", 1), new Writes.DocRow("D2", "b", 1)).expected());
        String none = null;
        assertEquals("UPDATE QUERY_IT.W_DOC DOC\nSET VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?",
            update(Writes.Doc.class).skipEmpty().byId(new Writes.DocRow("D1", none, 3)).toSql(DIALECT).get(0).sql(), "skipEmpty leaves null properties alone");
    }

    @Test
    void 按主键删除时带上版本且行缺主键或主键为null时报错() {
        assertEquals("DELETE FROM QUERY_IT.W_DOC DOC\nWHERE DOC.ID = ? AND DOC.VERSION = ?",
            Writes.deleteDoc(new Writes.DocRow("D1", null, 2)).toSql(DIALECT).get(0).sql());
        assertEquals("DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.ID = ?",
            deleteFrom(Note.class).byId(new NoteRow("N1", "T1", "a", 1, "open")).toSql(DIALECT).get(0).sql(), "without @Version only the key locates the row");
        record NoVersion(String id) { }
        assertThrows(IllegalArgumentException.class, () -> deleteFrom(Writes.Doc.class).byId(new NoVersion("D1")));
        assertThrows(IllegalArgumentException.class, () -> deleteFrom(Writes.Doc.class).byId(new Writes.DocRow(null, "x", 1)).toSql(DIALECT));
    }

    @Test
    void 生成主键的列不插入且只有带生成列的表能取回生成的值() {
        assertEquals("INSERT INTO QUERY_IT.W_SEQ (NAME) VALUES (?)", insertInto(Writes.Seq.class).values(new Writes.SeqRow("a")).toSql(DIALECT).get(0).sql());
        Database db = TestDatabase.dryRun(null);
        assertEquals(Arrays.asList((Object) null), db.insertKeys(insertInto(Writes.Seq.class).values(new Writes.SeqRow("a"))));
        assertThrows(IllegalStateException.class, () -> db.insertKeys(Writes.insertNotes()));
    }

    @Test
    void 插入或更新由方言写出且键必须是插入的列() {
        assertThrows(UnsupportedOperationException.class, () -> Writes.upsertDoc(new Writes.DocRow("D1", "a", 1)).toSql(DIALECT),
            "a dialect without an insert-or-update statement refuses");
        record TitleOnly(String title) { }
        assertThrows(IllegalArgumentException.class,
            () -> insertInto(Writes.Doc.class).values(new TitleOnly("a")).onConflict().doNothing().toSql(DIALECT));
    }

    @Test
    void 加锁读写在语句末尾() {
        assertTrue(org.dpdns.zerodep.query.testing.Workflow.claimable().toSql(DIALECT).sql().endsWith("LIMIT 1\nFOR UPDATE SKIP LOCKED"),
            org.dpdns.zerodep.query.testing.Workflow.claimable().toSql(DIALECT).sql());
        assertTrue(from(Note.class).with(forUpdate()).select(n -> n.id()).toSql(DIALECT).sql().endsWith("\nFOR UPDATE"));
    }

    @Test
    void 条件经过导航关系时包成EXISTS而SET经过导航关系时报错() {
        String sql = update(Lips.class).set(l -> l.unit().to("EA")).where(l -> l.mara().manufacturer().eq("MK1")).toSql(DIALECT).get(0).sql();
        assertEquals("""
            UPDATE QUERY_IT.LIPS LIPS
            SET VRKME = ?
            WHERE EXISTS (SELECT 1
            FROM (SELECT 1 AS ONE) SEED
            LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
            WHERE MARA.MFRNR = ?)""", sql);
        assertEquals("DELETE FROM QUERY_IT.LIPS LIPS\nWHERE LIPS.VBELN = ?", deleteFrom(Lips.class).where(l -> l.delivery().eq("D1")).toSql(DIALECT).get(0).sql(),
            "a condition on the table itself stays as it is");
        var e = assertThrows(IllegalArgumentException.class,
            () -> update(Lips.class).set(l -> l.unit().to(l.mara().manufacturer())).where(l -> l.delivery().eq("D1")).toSql(DIALECT));
        assertTrue(e.getMessage().contains("SET reads a related table"), e.getMessage());
    }

    @Test
    void diffFrom只更新变了的属性且没有变化时不发语句() {
        Update<Writes.Doc> changed = update(Writes.Doc.class).byId(new Writes.DocRow("D1", "new", 3)).diffFrom(new Writes.DocRow("D1", "old", 3));
        assertEquals("UPDATE QUERY_IT.W_DOC DOC\nSET TITLE = ?, VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?",
            changed.toSql(DIALECT).get(0).sql());
        Update<Writes.Doc> same = update(Writes.Doc.class).byId(new Writes.DocRow("D1", "a", 3), new Writes.DocRow("D2", "b", 1))
            .diffFrom(new Writes.DocRow("D1", "a", 3), new Writes.DocRow("D2", "c", 1));
        assertEquals(1, same.toSql(DIALECT).size(), "the unchanged row sends nothing");
        assertEquals(1L, same.expected());
        assertThrows(IllegalArgumentException.class,
            () -> update(Writes.Doc.class).byId(new Writes.DocRow("D1", "a", 3)).diffFrom(new Writes.DocRow("D1", "a", 2)), "the version must match");
        assertThrows(IllegalArgumentException.class,
            () -> update(Writes.Doc.class).byId(new Writes.DocRow("D1", "a", 3)).diffFrom(new Writes.DocRow("D1", "a", 3), new Writes.DocRow("D2", "b", 1)));
        assertThrows(IllegalStateException.class, () -> update(Writes.Doc.class).diffFrom(new Writes.DocRow("D1", "a", 3)));
    }

    @Test
    void 只读列从不写而只在插入时写的列在按主键更新时跳过() {
        Writes.DocFull full = new Writes.DocFull("D1", "a", 1, "app");
        assertEquals("INSERT INTO QUERY_IT.W_DOC (ID, TITLE, VERSION) VALUES (?, ?, ?)", insertInto(Writes.Doc.class).values(full).toSql(DIALECT).get(0).sql());
        assertEquals("UPDATE QUERY_IT.W_DOC DOC\nSET TITLE = ?, VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?",
            update(Writes.Doc.class).byId(full).toSql(DIALECT).get(0).sql());
        var e = assertThrows(IllegalArgumentException.class, () -> update(Writes.Doc.class).set(d -> d.source().to("x")).all());
        assertEquals("Doc.source() is @ReadOnly", e.getMessage());
        record Dated(String id, String title, java.time.LocalDateTime createdAt) { }
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET TITLE = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
            update(Writes.Ticket.class).byId(new Dated("T1", "a", Writes.CREATED)).toSql(DIALECT).get(0).sql());
        assertEquals("INSERT INTO QUERY_IT.W_TICKET (ID, TITLE, CREATED_AT, DELETED) VALUES (?, ?, ?, ?)",
            insertInto(Writes.Ticket.class).values(new Dated("T1", "a", Writes.CREATED)).toSql(DIALECT).get(0).sql(), "inserts still write it");
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET CREATED_AT = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
            update(Writes.Ticket.class).set(t -> t.createdAt().to(Writes.CREATED)).where(t -> t.id().eq("T1")).toSql(DIALECT).get(0).sql(),
            "an explicit set(...) may still change it");
    }

    @Test
    void 联表写入只在方言包里提供且没有where时报错() {
        assertThrows(UnsupportedOperationException.class, () -> Writes.syncNoteTitles().toSql(DIALECT), "the core dialect has no join update");
        var e = assertThrows(IllegalStateException.class,
            () -> JoinUpdate.of(Writes.Note.class, Writes.Doc.class, (n, d) -> n.id().eq(d.id())).set((n, d) -> n.title().to(d.title())).toSql(DIALECT));
        assertTrue(e.getMessage().contains("without where(...)"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> JoinUpdate.of(Writes.Note.class, Writes.Doc.class, (n, d) -> n.id().eq(d.id())).all().toSql(DIALECT),
            "nothing to SET");
        assertThrows(IllegalArgumentException.class, () -> JoinUpdate.of(Writes.Note.class, Writes.Doc.class, (n, d) -> { }));
        var nav = assertThrows(IllegalArgumentException.class, () -> JoinUpdate.of(Lips.class, Writes.Doc.class, (l, d) -> l.delivery().eq(d.id()))
            .set((l, d) -> l.unit().to(d.title())).where((l, d) -> l.mara().manufacturer().eq("MK1")).toSql(DIALECT));
        assertTrue(nav.getMessage().contains("cannot follow relations"), nav.getMessage());
    }

    @Test
    void 具名过滤器可以单独关闭而withDeleted只关闭逻辑删除() {
        Database db = new TestDatabase(c -> c.filter("tenant", Tenanted.class, t -> t.tenant().eq("T1")));
        assertTrue(db.render(from(Note.class).select(n -> n.id())).sql().endsWith("WHERE NOTE.TENANT = ?"));
        assertTrue(db.render(from(Note.class).unfiltered("tenant").select(n -> n.id())).sql().endsWith("FROM QUERY_IT.W_NOTE NOTE"));
        assertEquals("DELETE FROM QUERY_IT.W_NOTE NOTE\nWHERE NOTE.ID = ?",
            db.render(deleteFrom(Note.class).unfiltered("tenant").where(n -> n.id().eq("N1"))).get(0).sql());
        var e = assertThrows(IllegalArgumentException.class, () -> db.render(from(Note.class).unfiltered("tennant").select(n -> n.id())));
        assertTrue(e.getMessage().contains("names no configured filter; known: [tenant]"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new TestDatabase(c -> c.filter("t", Note.class, n -> n.id().eq("x")).filter("t", Note.class, n -> n.id().eq("y"))));
        assertTrue(from(Writes.Ticket.class).withDeleted().select(t -> t.id()).toSql(DIALECT).sql().endsWith("FROM QUERY_IT.W_TICKET TICKET"));
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?\nWHERE TICKET.ID = ?", Writes.deleteTicket("T1").withDeleted().toSql(DIALECT).get(0).sql());
    }

    @Test
    void schema按数据库实例的映射换成实际的名字() {
        Database db = new TestDatabase(c -> c.schema("QUERY_IT", "PROD"));
        assertTrue(db.render(from(Note.class).select(n -> n.id())).sql().contains("FROM PROD.W_NOTE NOTE"));
        assertEquals("INSERT INTO PROD.W_NOTE (ID, TENANT, TITLE, QTY, STATUS) VALUES (?, ?, ?, ?, ?)", db.render(Writes.insertNotes()).get(0).sql());
        assertTrue(db.render(Writes.closeOpenNote("N1")).get(0).sql().startsWith("UPDATE PROD.W_NOTE NOTE"));
        Database ruled = new TestDatabase(c -> c.schema(s -> s + "_T").schema("APP", "QUERY_IT"));
        assertTrue(ruled.render(from(Note.class).select(n -> n.id())).sql().contains("FROM QUERY_IT_T.W_NOTE NOTE"));
        assertTrue(ruled.render(from(Writes.AppNote.class).select(n -> n.id())).sql().contains("FROM QUERY_IT.W_NOTE APPNOTE"), "a named schema wins over the rule");
    }

    @Test
    void withoutFills不套用审计填充() {
        Database db = new TestDatabase(Writes::audit);
        assertEquals("INSERT INTO QUERY_IT.W_TICKET (ID, TITLE, DELETED) VALUES (?, ?, ?)",
            db.render(insertInto(Writes.Ticket.class).values(new Writes.TicketRow("T1", "a")).withoutFills()).get(0).sql());
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET TITLE = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
            db.render(update(Writes.Ticket.class).set(t -> t.title().to("b")).where(t -> t.id().eq("T1")).withoutFills()).get(0).sql());
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
            db.render(Writes.deleteTicket("T1").withoutFills()).get(0).sql());
    }

    @Test
    void 实现CodeEnum的枚举按编码存取() {
        Rendered r = update(Writes.StatefulNote.class).set(n -> n.state().to(Writes.NoteState.CLOSED))
            .where(n -> n.state().eq(Writes.NoteState.OPEN)).toSql(DIALECT).get(0);
        assertEquals(List.of("closed", "open"), r.parameters());
        org.dpdns.zerodep.query.type.SqlType<Writes.NoteState> type = org.dpdns.zerodep.query.type.SqlType.of(Writes.NoteState.class);
        assertEquals(String.class, type.storageType());
        assertEquals(Writes.NoteState.OPEN, type.fromDatabase("open"));
        assertThrows(IllegalArgumentException.class, () -> type.fromDatabase("gone"));
    }

    @Test
    void 逻辑删除记毫秒数时删除写此刻的毫秒数() {
        long before = System.currentTimeMillis();
        Rendered r = deleteFrom(Writes.Code.class).where(c -> c.code().eq("A")).toSql(DIALECT).get(0);
        assertEquals("UPDATE QUERY_IT.W_CODE CODE\nSET DELETED = ?\nWHERE CODE.DELETED = ? AND CODE.CODE = ?", r.sql());
        assertTrue((Long) r.parameters().get(0) >= before);
        assertEquals(List.of(0L, "A"), r.parameters().subList(1, 3));
    }

    @Test
    void 逻辑删除的表查询时只看未删除的行且删除改写成UPDATE() {
        assertTrue(from(Writes.Ticket.class).select(t -> t.id()).toSql(DIALECT).sql().endsWith("WHERE TICKET.DELETED = ?"));
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
            Writes.deleteTicket("T1").toSql(DIALECT).get(0).sql());
        assertEquals(List.of(1, 0, "T1"), Writes.deleteTicket("T1").toSql(DIALECT).get(0).parameters());
        assertEquals("DELETE FROM QUERY_IT.W_TICKET TICKET\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
            Writes.deleteTicket("T1").physically().toSql(DIALECT).get(0).sql());
        assertEquals("DELETE FROM QUERY_IT.W_TICKET TICKET\nWHERE TICKET.ID = ?", Writes.deleteTicket("T1").physically().unfiltered().toSql(DIALECT).get(0).sql());
        assertTrue(from(Writes.Ticket.class).unfiltered().select(t -> t.id()).toSql(DIALECT).sql().endsWith("FROM QUERY_IT.W_TICKET TICKET"));
        assertEquals("UPDATE QUERY_IT.W_ARCHIVE ARCHIVE\nSET DELETED_AT = CURRENT_TIMESTAMP\nWHERE ARCHIVE.DELETED_AT IS NULL AND ARCHIVE.ID = ?",
            deleteFrom(Writes.Archive.class).where(a -> a.id().eq("A1")).toSql(DIALECT).get(0).sql());
    }

    @Test
    void 审计填充在插入和更新时写入且插入时补上未删除的标记() {
        Database db = new TestDatabase(Writes::audit);
        Rendered insert = db.render(insertInto(Writes.Ticket.class).values(new Writes.TicketRow("T1", "a"))).get(0);
        assertEquals("INSERT INTO QUERY_IT.W_TICKET (ID, TITLE, CREATED_AT, DELETED) VALUES (?, ?, ?, ?)", insert.sql());
        assertEquals(List.of("T1", "a", Writes.CREATED, 0), insert.parameters());
        Rendered update = db.render(update(Writes.Ticket.class).set(t -> t.title().to("b")).set(t -> t.updatedBy().to("me")).where(t -> t.id().eq("T1"))).get(0);
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET TITLE = ?, UPDATED_AT = ?, UPDATED_BY = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?", update.sql());
        assertEquals(List.of("b", Writes.UPDATED, "bob", 0, "T1"), update.parameters(), "the fill wins over the statement's own value");
        assertEquals("UPDATE QUERY_IT.W_TICKET TICKET\nSET DELETED = ?, UPDATED_AT = ?, UPDATED_BY = ?\nWHERE TICKET.DELETED = ? AND TICKET.ID = ?",
            db.render(Writes.deleteTicket("T1")).get(0).sql());
        assertEquals("INSERT INTO QUERY_IT.W_NOTE (ID, TENANT, TITLE, QTY, STATUS) VALUES (?, ?, ?, ?, ?)", db.render(Writes.insertNotes()).get(0).sql(),
            "tables outside Audited get no fill");
    }

    @Test
    void 空跑执行写语句时按批通知监听器且不检查期望的行数() {
        Database db = TestDatabase.dryRun(null);
        List<QueryListener.Event> events = db.capture(() -> {
            assertEquals(0, db.execute(Writes.insertNotes()));
            assertEquals(0, db.execute(Writes.closeOpenNote("N1")));
            assertEquals(0, db.execute(insertInto(Note.class)));
        });
        assertEquals(2, events.size(), "two rows in one batch, then the update; an empty insert runs nothing");
        assertEquals(List.of("N1", "T1", "first", 1, "open"), events.get(0).statement().parameters());
        assertEquals(1, db.transaction(() -> 1));
    }
}

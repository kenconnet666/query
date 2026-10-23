package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.QueryListener;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.dpdns.zerodep.query.testing.Writes;
import org.dpdns.zerodep.query.testing.Writes.Note;
import org.dpdns.zerodep.query.testing.Writes.NoteRow;
import org.dpdns.zerodep.query.testing.Writes.Tenanted;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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

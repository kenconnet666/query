package org.dpdns.zerodep.query.it.write;

import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.exec.QueryExecutionException;
import org.dpdns.zerodep.query.exec.UnexpectedRowCountException;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.testing.Writes.AppNote;
import org.dpdns.zerodep.query.testing.Writes.Audited;
import org.dpdns.zerodep.query.testing.Writes.Code;
import org.dpdns.zerodep.query.testing.Writes.CodeRow;
import org.dpdns.zerodep.query.testing.Writes.Doc;
import org.dpdns.zerodep.query.testing.Writes.DocFull;
import org.dpdns.zerodep.query.testing.Writes.DocRow;
import org.dpdns.zerodep.query.testing.Writes.Note;
import org.dpdns.zerodep.query.testing.Writes.NoteRow;
import org.dpdns.zerodep.query.testing.Writes.NoteState;
import org.dpdns.zerodep.query.testing.Writes.Seq;
import org.dpdns.zerodep.query.testing.Writes.SeqRow;
import org.dpdns.zerodep.query.testing.Writes.StatefulNote;
import org.dpdns.zerodep.query.testing.Writes.Tenanted;
import org.dpdns.zerodep.query.testing.Writes.Ticket;
import org.dpdns.zerodep.query.testing.Writes.TicketRow;
import org.dpdns.zerodep.query.write.Insert;
import org.dpdns.zerodep.query.write.JoinDelete;
import org.dpdns.zerodep.query.write.JoinUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.deleteFrom;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.insertInto;
import static org.dpdns.zerodep.query.Sql.update;
import static org.dpdns.zerodep.query.Sql.val;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 插入、更新与删除：期望的行数、事务回滚、分批插入、乐观锁、差异更新、生成的主键、插入或更新、联表写、逻辑删除与审计填充、
 * 具名过滤器和 schema 映射。表声明在 {@code testing.Writes}，每个库按声明建一次：
 * <ul>
 *   <li>W_NOTE 便签（文本主键、租户、标题、数量、状态）；</li>
 *   <li>W_DOC 文档（版本列做乐观锁，SOURCE 由数据库默认值 'db' 维护，本库不写）；</li>
 *   <li>W_SEQ 数据库生成主键的表；W_CODE 编码表（逻辑删除记删除时刻的毫秒数，编码加删除时刻唯一）；</li>
 *   <li>W_TICKET 工单（DELETED 为 1 是逻辑删除，另有创建时间、修改时间和修改人三个审计列）。</li>
 * </ul>
 * 每个测试先清空自己用的表。
 */
@LiveTest
@DisplayName("插入、更新与删除")
class WriteTest {
    @Parameter Live live;

    @BeforeParameterizedClassInvocation
    static void tables(Live live) {
        live.recreate(Note.class, Doc.class, Seq.class, Code.class, Ticket.class);
    }

    /** 两张便签：N1 标题 first、数量 1；N2 没有标题、数量 2；都属于租户 T1、状态 open。 */
    private static Insert<Note> twoNotes() {
        return insertInto(Note.class).values(new NoteRow("N1", "T1", "first", 1, "open"), new NoteRow("N2", "T1", null, 2, "open"));
    }

    /** 全部便签的编号。 */
    private List<String> noteIds() { return live.db().list(from(Note.class).orderBy(n -> n.id()).select(n -> n.id())); }

    @Test
    void 插入更新删除按期望的行数执行() {
        live.db().execute(deleteFrom(Note.class).all());
        assertEquals(2, live.db().execute(twoNotes()));
        // 比较后更新：只改开着的便签，期望改到一行，改到 0 行（已经被关了）就报错。
        var closeN1 = update(Note.class)
            .set(n -> n.qty().to(n.qty().plus(val(10))))
            .set(n -> n.status().to("closed"))
            .where(n -> { n.id().eq("N1"); n.status().eq("open"); })
            .expect(1);
        assertEquals(1, live.db().execute(closeN1));
        assertEquals(0, assertThrows(UnexpectedRowCountException.class, () -> live.db().execute(closeN1)).actual());
        assertEquals(List.of("N1:first:11:closed", "N2:null:2:open"), live.db().list(from(Note.class).orderBy(n -> n.id())
                .select(n -> cols(n.id(), n.title(), n.qty(), n.status()))).stream()
            .map(r -> r.get("id") + ":" + r.get("title") + ":" + r.get("qty") + ":" + r.get("status")).toList());
        // 把查询结果插入表里：复制 N1 为 N9；再删掉关闭的 N1、N9。
        assertEquals(1, live.db().execute(insertInto(Note.class).from(from(Note.class)
            .where(n -> n.id().eq("N1"))
            .select(n -> cols(val("N9").as("id"), n.tenant(), n.title(), n.qty(), n.status())))));
        assertEquals(2, live.db().execute(deleteFrom(Note.class).where(n -> n.status().eq("closed"))));
        assertEquals(List.of("N2"), noteIds());
    }

    @Test
    void 事务里出错时写入整体回滚() {
        live.db().execute(deleteFrom(Note.class).all());
        assertThrows(IllegalStateException.class, () -> live.db().transaction(() -> {
            live.db().execute(twoNotes());
            assertEquals(List.of("N1", "N2"), noteIds(), "the transaction sees its own rows");
            throw new IllegalStateException("roll back");
        }));
        assertEquals(List.of(), noteIds());
        live.db().transaction(() -> live.db().execute(twoNotes()));
        assertEquals(List.of("N1", "N2"), noteIds());
    }

    @Test
    void 多行插入超过一批时分批执行() {
        live.db().execute(deleteFrom(Note.class).all());
        List<NoteRow> rows = new ArrayList<>();
        for (int i = 0; i < 1203; i++) rows.add(new NoteRow("B" + i, "T1", "bulk", i, "open"));
        assertEquals(1203, live.db().execute(insertInto(Note.class).values(rows)));
        assertEquals(1203L, live.db().count(from(Note.class).select(n -> n.id())));
        assertEquals(1203, live.db().execute(deleteFrom(Note.class).where(n -> n.title().eq("bulk"))));
    }

    /** 全部文档写成 编号:标题:版本。 */
    private List<String> docs() {
        return live.db().list(from(Doc.class).orderBy(d -> d.id()).select(d -> cols(d.id(), d.title(), d.version()))).stream()
            .map(r -> r.get("id") + ":" + r.get("title") + ":" + r.get("version")).toList();
    }

    @Test
    void 按主键更新和删除时版本不对就报乐观锁冲突() {
        live.db().execute(deleteFrom(Doc.class).all());
        live.db().execute(insertInto(Doc.class).values(new DocRow("D1", "a", 1)));
        // byId 按主键和版本改，版本加一；行里的版本与库里不同就改到 0 行，报冲突。
        assertEquals(1, live.db().execute(update(Doc.class).byId(new DocRow("D1", "b", 1))));
        assertEquals(List.of("D1:b:2"), docs());
        assertThrows(UnexpectedRowCountException.class, () -> live.db().execute(update(Doc.class).byId(new DocRow("D1", "c", 1))));
        assertThrows(UnexpectedRowCountException.class, () -> live.db().execute(deleteFrom(Doc.class).byId(new DocRow("D1", null, 1))));
        assertEquals(1, live.db().execute(deleteFrom(Doc.class).byId(new DocRow("D1", null, 2))));
    }

    @Test
    void 差异更新只写变了的列且只读列保留数据库默认值() {
        live.db().execute(deleteFrom(Doc.class).all());
        live.db().execute(insertInto(Doc.class).values(new DocFull("D1", "a", 1, "app")));
        assertEquals(0, live.db().execute(update(Doc.class).byId(new DocRow("D1", "a", 1)).diffFrom(new DocRow("D1", "a", 1))),
            "nothing changed, so nothing runs and the version stays");
        assertEquals(1, live.db().execute(update(Doc.class).byId(new DocFull("D1", "b", 1, "app")).diffFrom(new DocFull("D1", "a", 1, "db"))));
        assertEquals(List.of("b:2:db"), live.db().list(from(Doc.class).select(d -> cols(d.title(), d.version(), d.source()))).stream()
            .map(r -> r.get("title") + ":" + r.get("version") + ":" + r.get("source")).toList(), "SOURCE 是只读列：插入和更新都不写它，留着默认值 db");
        assertTrue(live.db().exists(from(Doc.class).where(d -> d.id().eq("D1")).select(d -> d.id())));
        assertFalse(live.db().exists(from(Doc.class).where(d -> d.id().eq("D9")).select(d -> d.id())));
    }

    @Test
    void 插入后按行的顺序取回数据库生成的主键() {
        live.db().execute(deleteFrom(Seq.class).all());
        List<Integer> keys = live.db().insertKeys(insertInto(Seq.class).values(new SeqRow("a"), new SeqRow("b")));
        assertEquals(2, keys.size());
        assertEquals(keys.get(0) + 1, keys.get(1));
        assertEquals(List.of(keys.get(0) + ":a", keys.get(1) + ":b"), live.db().list(from(Seq.class).orderBy(s -> s.id())
            .select(s -> cols(s.id(), s.name()))).stream().map(r -> r.get("id") + ":" + r.get("name")).toList());
    }

    @Test
    void 插入或更新在冲突时更新指定的列或保留原来的行() {
        live.db().execute(deleteFrom(Doc.class).all());
        live.db().execute(insertInto(Doc.class).values(new DocRow("D1", "a", 1)).onConflict().update(d -> d.title()));
        live.db().execute(insertInto(Doc.class).values(new DocRow("D1", "b", 9)).onConflict().update(d -> d.title()));   // 只更新标题，版本还是 1
        live.db().execute(insertInto(Doc.class).values(new DocRow("D1", "c", 5)).onConflict(d -> d.id()).doNothing());   // 保留原来的行
        live.db().execute(insertInto(Doc.class).values(new DocRow("D2", "d", 1)).onConflict(d -> d.id()).doNothing());
        assertEquals(List.of("D1:b:1", "D2:d:1"), docs());
    }

    @Test
    void 联表更新与删除按连接得上的行改动() {
        live.db().execute(deleteFrom(Note.class).all());
        live.db().execute(deleteFrom(Doc.class).all());
        live.db().execute(twoNotes());
        live.db().execute(insertInto(Doc.class).values(new DocRow("N1", "synced", 2), new DocRow("N2", "gone", 1)));
        // 把同编号文档的标题抄到便签上，只抄改过的文档（版本大于 1）；再删掉对应文档标题为 gone 的便签。
        assertEquals(1, live.db().execute(JoinUpdate.of(Note.class, Doc.class, (n, d) -> n.id().eq(d.id()))
            .set((n, d) -> n.title().to(d.title())).where((n, d) -> d.version().gt(1))));
        assertEquals(1, live.db().execute(JoinDelete.of(Note.class, Doc.class, (n, d) -> n.id().eq(d.id())).where((n, d) -> d.title().eq("gone"))));
        assertEquals(List.of("N1:synced"), live.db().list(from(Note.class).select(n -> cols(n.id(), n.title()))).stream()
            .map(r -> r.get("id") + ":" + r.get("title")).toList());
        // 工单有逻辑删除列：联表删除改写成把 DELETED 设为 1 的联表更新。
        live.db().execute(deleteFrom(Ticket.class).physically().unfiltered().all());
        live.db().execute(insertInto(Ticket.class).values(new TicketRow("N2", "t")));
        assertEquals(1, live.db().execute(JoinDelete.of(Ticket.class, Doc.class, (t, d) -> t.id().eq(d.id())).where((t, d) -> d.title().eq("gone"))));
        assertEquals(0, live.db().count(from(Ticket.class).select(t -> t.id())), "the ticket is deleted logically");
        assertEquals(1, live.db().count(from(Ticket.class).withDeleted().select(t -> t.id())));
    }

    @Test
    void 逻辑删除记毫秒数时同一个编码删除后可以再建() {
        live.db().execute(deleteFrom(Code.class).physically().unfiltered().all());
        live.db().execute(insertInto(Code.class).values(new CodeRow("C1", "A")));
        assertThrows(QueryExecutionException.class, () -> live.db().execute(insertInto(Code.class).values(new CodeRow("C2", "A"))),
            "the unique key stops a second live row with the same code");
        // 删除把 DELETED 从 0 改成删除时刻的毫秒数，(CODE, DELETED) 不再冲突。
        assertEquals(1, live.db().execute(deleteFrom(Code.class).where(c -> c.code().eq("A"))));
        live.db().execute(insertInto(Code.class).values(new CodeRow("C2", "A")));
        assertEquals(List.of("C2"), live.db().list(from(Code.class).select(c -> c.id())));
        assertEquals(2, live.db().count(from(Code.class).withDeleted().select(c -> c.id())));
    }

    /** 审计填充用的固定时间，便于核对。 */
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 1, 8, 0);

    @Test
    void 逻辑删除与审计填充按预期写入和过滤() {
        // 插入时填创建时间，更新时填修改时间和修改人；删除工单是把 DELETED 设为 1 的更新，所以也填修改人。
        Database audited = live.database(c -> c.onInsert(Audited.class, a -> a.createdAt(), () -> CREATED)
            .onUpdate(Audited.class, a -> a.updatedAt(), () -> LocalDateTime.of(2026, 1, 2, 9, 30))
            .onUpdate(Audited.class, a -> a.updatedBy(), () -> "bob"));
        audited.execute(deleteFrom(Ticket.class).physically().unfiltered().all());
        audited.execute(insertInto(Ticket.class).values(new TicketRow("T1", "a"), new TicketRow("T2", "b")));
        audited.execute(update(Ticket.class).set(t -> t.title().to("b2")).where(t -> t.id().eq("T2")));
        assertEquals(1, audited.execute(deleteFrom(Ticket.class).where(t -> t.id().eq("T1"))));
        assertEquals(0, audited.execute(deleteFrom(Ticket.class).where(t -> t.id().eq("T1"))), "a deleted row is no longer visible");
        audited.execute(insertInto(Ticket.class).values(new TicketRow("T3", "c")).onConflict().update());
        audited.execute(insertInto(Ticket.class).values(new TicketRow("T3", "c2")).onConflict().update());
        assertEquals(List.of("T2", "T3"), audited.list(from(Ticket.class).orderBy(t -> t.id()).select(t -> t.id())));
        assertEquals(List.of("T1:a:1:2026-01-01T08:00:bob", "T2:b2:0:2026-01-01T08:00:bob", "T3:c2:0:2026-01-01T08:00:bob"),
            audited.list(from(Ticket.class).unfiltered().orderBy(t -> t.id())
                    .select(t -> cols(t.id(), t.title(), t.deleted(), t.createdAt(), t.updatedBy()))).stream()
                .map(r -> r.get("id") + ":" + r.get("title") + ":" + r.get("deleted") + ":" + r.get("createdAt") + ":" + r.get("updatedBy")).toList(),
            "the create time is written once and survives the upsert");
        assertEquals(1, audited.execute(deleteFrom(Ticket.class).physically().unfiltered().where(t -> t.id().eq("T1"))));
    }

    @Test
    void 具名过滤器与schema映射和按编码的枚举生效() {
        // 具名过滤器 tenant 只看租户 T9；表声明里的 schema APP 映射成 QUERY_IT；StatefulNote 把状态列按编码读成枚举。
        Database scoped = live.database(c -> c.filter("tenant", Tenanted.class, t -> t.tenant().eq("T9")).schema("APP", "QUERY_IT"));
        scoped.execute(deleteFrom(Note.class).unfiltered().all());
        scoped.execute(twoNotes());
        assertEquals(List.of(), scoped.list(from(Note.class).select(n -> n.id())), "the tenant filter hides tenant T1");
        assertEquals(List.of("N1", "N2"), scoped.list(from(Note.class).unfiltered("tenant").orderBy(n -> n.id()).select(n -> n.id())));
        assertEquals(List.of("N1", "N2"), scoped.list(from(AppNote.class).orderBy(n -> n.id()).select(n -> n.id())), "APP maps to QUERY_IT");
        assertEquals(List.of(NoteState.OPEN, NoteState.OPEN),
            scoped.list(from(StatefulNote.class).unfiltered("tenant").orderBy(n -> n.id()).select(n -> n.state())));
        assertEquals(1, scoped.execute(update(StatefulNote.class).unfiltered("tenant").set(n -> n.state().to(NoteState.CLOSED)).where(n -> n.id().eq("N1"))));
        assertEquals(List.of("closed", "open"), live.db().list(from(Note.class).orderBy(n -> n.id()).select(n -> n.status())));
    }
}

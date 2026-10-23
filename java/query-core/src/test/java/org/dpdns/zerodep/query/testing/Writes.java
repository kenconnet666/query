package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Generated;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Version;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.write.Delete;
import org.dpdns.zerodep.query.write.Insert;
import org.dpdns.zerodep.query.write.Update;

import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;

/** 写入场景的表和语句：单元测试、金样例和三库实库场景共用。读查询的表不参与，免得写入测试影响它们。 */
public final class Writes {
    /** 测试表所在的 schema。 */
    public static final String SCHEMA = "QUERY_IT";

    /** 只放表和语句，不能实例化。 */
    private Writes() { }

    /** 带租户列的表，全局过滤器按它匹配。 */
    public interface Tenanted extends Entity {
        /** 租户。 */
        @Column("TENANT") Col<String> tenant();
    }

    /** 便签：主键是文本，数量和状态用于更新的场景。 */
    @Table(schema = SCHEMA, value = "W_NOTE")
    public interface Note extends Tenanted {
        /** 编号。 */
        @Id @Column("ID") Col<String> id();

        /** 标题。 */
        @Column("TITLE") Col<String> title();

        /** 数量。 */
        @Column("QTY") Col<Integer> qty();

        /** 状态。 */
        @Column("STATUS") Col<String> status();
    }

    /** 文档：带乐观锁的版本列。 */
    @Table(schema = SCHEMA, value = "W_DOC")
    public interface Doc extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<String> id();

        /** 标题。 */
        @Column("TITLE") Col<String> title();

        /** 版本号。 */
        @Version @Column("VERSION") Col<Integer> version();
    }

    /** 文档的一行。 */
    public record DocRow(String id, String title, Integer version) { }

    /** 编号由数据库生成的表（IDENTITY / AUTO_INCREMENT）。 */
    @Table(schema = SCHEMA, value = "W_SEQ")
    public interface Seq extends Entity {
        /** 生成的编号。 */
        @Id @Generated @Column("ID") Col<Integer> id();

        /** 名称。 */
        @Column("NAME") Col<String> name();
    }

    /** 插入 {@link Seq} 的一行：不带编号。 */
    public record SeqRow(String name) { }

    /** 按主键改标题：版本号加一，版本不对时没有改到行。 */
    public static Update<Doc> renameDoc(DocRow row) { return update(Doc.class).byId(row); }

    /** 按主键和版本删除文档。 */
    public static Delete<Doc> deleteDoc(DocRow row) { return deleteFrom(Doc.class).byId(row); }

    /** 插入文档，主键已有时只更新标题。 */
    public static Insert<Doc> upsertDoc(DocRow row) { return insertInto(Doc.class).values(row).onConflict().update(d -> d.title()); }

    /** 插入文档，主键已有时保留原来的行。 */
    public static Insert<Doc> insertDocIfAbsent(DocRow row) { return insertInto(Doc.class).values(row).onConflict(d -> d.id()).doNothing(); }

    /** 插入便签的一行：属性按名字对应 {@link Note} 的列。 */
    public record NoteRow(String id, String tenant, String title, Integer qty, String status) { }

    /** 插入两行，第二行的标题为 NULL。 */
    public static Insert<Note> insertNotes() {
        return insertInto(Note.class).values(new NoteRow("N1", "T1", "first", 1, "open"), new NoteRow("N2", "T1", null, 2, "open"));
    }

    /** 把开着的便签数量加 10 并关闭："比较后更新"。 */
    public static Update<Note> closeOpenNote(String id) {
        return update(Note.class)
            .set(n -> n.qty().to(plus(n.qty(), val(10))))
            .set(n -> n.status().to("closed"))
            .where(n -> all(n.id().eq(id), n.status().eq("open")))
            .expect(1);
    }

    /** 删除关闭了的便签。 */
    public static Delete<Note> deleteClosed() {
        return deleteFrom(Note.class).where(n -> n.status().eq("closed"));
    }

    /** 把查询结果插入表里：复制 N1 为 N9。 */
    public static Insert<Note> copyNote() {
        return insertInto(Note.class).from(from(Note.class)
            .where(n -> n.id().eq("N1"))
            .select(n -> cols(val("N9").as("id"), n.tenant(), n.title(), n.qty(), n.status())));
    }

    /** 某个租户下全部便签的编号，按编号排序。 */
    public static List<String> ids(org.dpdns.zerodep.query.exec.Database db) {
        return db.list(from(Note.class).orderBy(n -> n.id()).select(n -> n.id()));
    }
}

package org.dpdns.zerodep.query.it.write;

import org.dpdns.zerodep.query.exec.UnexpectedRowCountException;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Writes.Doc;
import org.dpdns.zerodep.query.testing.Writes.DocBean;
import org.dpdns.zerodep.query.testing.Writes.DocRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.util.List;

import static org.dpdns.zerodep.query.Sql.all;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.deleteFrom;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.insertInto;
import static org.dpdns.zerodep.query.Sql.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 追踪范围：范围里读出的可变行登记快照，范围结束时只把改了的行按主键和版本写回，并把新版本写回对象。
 * 表是 {@code testing.Writes} 的 W_DOC（版本列做乐观锁）；每个测试开始时只有 D1（标题 a）和 D2（标题 b），版本都是 1。
 */
@LiveTest
@DisplayName("追踪范围")
class TrackingTest {
    @Parameter Live live;

    @BeforeParameterizedClassInvocation
    static void tables(Live live) {
        live.recreate(Doc.class);
    }

    @BeforeEach
    void twoDocs() {
        live.db().execute(deleteFrom(Doc.class).all());
        live.db().execute(insertInto(Doc.class).values(new DocRow("D1", "a", 1), new DocRow("D2", "b", 1)));
    }

    /** 一份文档读成可变行。 */
    private static Query<DocBean> doc(String id) { return from(Doc.class).where(d -> d.id().eq(id)).selectAs(DocBean.class); }

    /** 库里每份文档写成 编号:标题:版本。 */
    private List<String> docs() {
        return live.db().list(from(Doc.class).orderBy(d -> d.id()).select(d -> cols(d.id(), d.title(), d.version()))).stream()
            .map(r -> r.get("id") + ":" + r.get("title") + ":" + r.get("version")).toList();
    }

    @Test
    void 范围结束时只写回改了的行并把新版本写回对象() {
        DocBean d1 = live.db().tracked(t -> {
            List<DocBean> all = t.list(Doc.class, from(Doc.class).orderBy(d -> d.id()).selectAs(DocBean.class));
            all.get(0).setTitle("a2");
            return all.get(0);
        });
        assertEquals(List.of("D1:a2:2", "D2:b:1"), docs());
        assertEquals(2, d1.getVersion());
        live.db().tracking(t -> {
            DocBean again = t.one(Doc.class, doc("D1"));
            again.setTitle("a3");
            again.setTitle("a2");
        });
        assertEquals(List.of("D1:a2:2", "D2:b:1"), docs(), "a row changed back is not written");
    }

    @Test
    void 同一主键只登记一份且嵌套范围由最外层写回() {
        live.db().tracking(t -> {
            DocBean first = t.one(Doc.class, doc("D1"));
            first.setTitle("x");
            live.db().tracking(inner -> {
                DocBean same = inner.one(Doc.class, doc("D1"));
                assertSame(first, same);
                same.setTitle("y");
            });
            assertEquals(List.of("D1:a:1", "D2:b:1"), docs(), "the inner scope does not write");
        });
        assertEquals(List.of("D1:y:2", "D2:b:1"), docs());
    }

    @Test
    void 范围抛出异常或写回冲突时不写也不改对象的版本() {
        assertThrows(IllegalStateException.class, () -> live.db().tracking(t -> {
            t.one(Doc.class, doc("D1")).setTitle("lost");
            throw new IllegalStateException("abort");
        }));
        assertEquals(List.of("D1:a:1", "D2:b:1"), docs());
        // 范围里读出 D1 后，别处先改了它（版本变成 2），写回时版本对不上。
        DocBean[] held = new DocBean[1];
        assertThrows(UnexpectedRowCountException.class, () -> live.db().tracking(t -> {
            held[0] = t.one(Doc.class, doc("D1"));
            held[0].setTitle("mine");
            live.db().execute(update(Doc.class).byId(new DocRow("D1", "theirs", 1)));
        }));
        assertEquals(List.of("D1:theirs:2", "D2:b:1"), docs());
        assertEquals(1, held[0].getVersion(), "a failed write leaves the version as read");
    }
}

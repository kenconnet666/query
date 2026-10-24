package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.testing.TestDatabase;
import org.dpdns.zerodep.query.testing.Writes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("实体追踪")
class TrackerTest {
    private final TestDatabase db = new TestDatabase(c -> { });

    /** 编号为 id、标题为 title、版本为 1 的可变行。 */
    private static Writes.DocBean bean(String id, String title) {
        Writes.DocBean b = new Writes.DocBean();
        b.setId(id);
        b.setTitle(title);
        b.setVersion(1);
        return b;
    }

    @Test
    void 同一主键再次登记时返回先登记的对象() {
        db.tracking(t -> {
            Writes.DocBean first = t.track(Writes.Doc.class, bean("D1", "a"));
            assertSame(first, t.track(Writes.Doc.class, bean("D1", "b")));
            t.forget(first);
            Writes.DocBean other = bean("D1", "c");
            assertSame(other, t.track(Writes.Doc.class, other));
            t.forget(other);
        });
    }

    @Test
    void record和缺少主键的行不能登记() {
        db.tracking(t -> {
            assertThrows(IllegalArgumentException.class, () -> t.track(Writes.Doc.class, new Writes.DocRow("D1", "a", 1)));
            assertThrows(IllegalArgumentException.class, () -> t.track(Writes.Doc.class, bean(null, "a")));
            assertThrows(IllegalArgumentException.class, () -> t.track(Writes.Seq.class, bean("D1", "a")));
        });
    }

    @Test
    void 没有变化时范围结束不连库() {
        db.tracking(t -> {
            t.track(Writes.Doc.class, bean("D1", "a"));
        });
    }
}

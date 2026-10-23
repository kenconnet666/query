package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("原生SQL")
class NativeQueryTest {
    private final TestDatabase db = new TestDatabase(null);

    @Test
    void 命名参数替换为问号且集合展开() {
        Rendered r = db.sql("SELECT * FROM T WHERE A = :a AND B IN (:bs) AND C = :a")
            .bind("a", "x").bind("bs", List.of(1, 2, 3)).render();
        assertEquals("SELECT * FROM T WHERE A = ? AND B IN (?, ?, ?) AND C = ?", r.sql());
        assertEquals(List.of("x", 1, 2, 3, "x"), r.parameters());
    }

    @Test
    void 引号注释和类型转换里的冒号不是参数() {
        Rendered r = db.sql("SELECT ':no', \"x:y\", v::text -- :comment\nFROM T /* :block */ WHERE id = :id")
            .bind("id", 7).render();
        assertEquals("SELECT ':no', \"x:y\", v::text -- :comment\nFROM T /* :block */ WHERE id = ?", r.sql());
        assertEquals(List.of(7), r.parameters());
    }

    @Test
    void 未绑定或空集合的参数报错() {
        assertThrows(IllegalArgumentException.class, () -> db.sql("SELECT :missing").render());
        assertThrows(IllegalArgumentException.class, () -> db.sql("SELECT * FROM T WHERE A IN (:x)").bind("x", List.of()).render());
    }
}

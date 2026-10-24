package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Shards;
import org.dpdns.zerodep.query.testing.Shards.ShEvent;
import org.dpdns.zerodep.query.testing.Shards.ShEventRow;
import org.dpdns.zerodep.query.testing.Shards.ShOrder;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("同库分表")
class ShardingTest {
    private final TestDatabase db = new TestDatabase(Shards::configure);

    /** 语句里出现的物理表名，按出现顺序。 */
    private static List<String> tables(List<Rendered> statements) {
        return statements.stream().map(r -> r.sql().replaceAll("(?s).*?(SH_\\w+_\\d+).*", "$1")).toList();
    }

    @Test
    void 条件推不出分表时报错() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> db.render(from(ShEvent.class).where(x -> x.note().eq("a")).select(x -> x.id())));
        assertTrue(e.getMessage().contains("allShards()"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> db.render(from(ShEvent.class)
            .where(x -> any(x.day().eq(LocalDate.of(2026, 1, 3)), x.note().eq("a"))).select(x -> x.id())));
    }

    @Test
    void AND取交集OR取并集且范围外的月份不读() {
        String sql = db.render(from(ShEvent.class)
            .where(x -> all(x.day().ge(LocalDate.of(2025, 6, 1)), x.day().le(LocalDate.of(2026, 2, 1)), x.note().eq("a")))
            .select(x -> x.id())).sql();
        assertTrue(sql.contains("SH_EVENT_202601") && sql.contains("SH_EVENT_202602") && !sql.contains("SH_EVENT_202603"), sql);
        String none = db.render(from(ShEvent.class).where(x -> x.day().lt(LocalDate.of(2026, 1, 1))).select(x -> x.id())).sql();
        assertTrue(none.contains("WHERE 1 = 0"), none);
    }

    @Test
    void 一段SELECT里只能有一张分表() {
        assertThrows(IllegalStateException.class, () -> db.render(from(ShEvent.class)
            .join(ShOrder.class, (e, o) -> o.id().eq(e.id()))
            .allShards()
            .select((e, o) -> e.id())));
    }

    @Test
    void 子查询沿用外层的allShards() {
        String sql = db.render(from(Mara.class).allShards()
            .where(m -> exists(from(ShEvent.class).where(e -> e.note().eq(m.material())).selectOne()))
            .select(m -> m.material())).sql();
        assertTrue(sql.contains("SH_EVENT_202601") && sql.contains("SH_EVENT_202603"), sql);
    }

    @Test
    void onShards只读列出的表且不认识的表报错() {
        String sql = db.render(from(ShOrder.class).onShards("SH_ORDER_0").select(o -> o.id())).sql();
        assertTrue(sql.contains("SH_ORDER_0") && !sql.contains("SH_ORDER_1"), sql);
        assertThrows(IllegalArgumentException.class, () -> db.render(from(ShOrder.class).onShards("SH_ORDER_9").select(o -> o.id())));
    }

    @Test
    void 插入按行写到各自的分表() {
        List<Rendered> statements = db.render(insertInto(ShEvent.class).values(
            new ShEventRow(1L, LocalDate.of(2026, 1, 5), BigDecimal.ONE, "a"),
            new ShEventRow(2L, LocalDate.of(2026, 3, 5), BigDecimal.ONE, "b")));
        assertEquals(List.of("SH_EVENT_202601", "SH_EVENT_202603"), tables(statements));
        assertThrows(IllegalArgumentException.class, () -> db.render(insertInto(ShEvent.class).values(new ShEventRow(3L, LocalDate.of(2026, 5, 1), null, null))));
    }

    @Test
    void 按主键更新按行里的分表键选表且不能改分表键() {
        assertEquals(List.of("SH_EVENT_202602"), tables(db.render(update(ShEvent.class).byId(new ShEventRow(1L, LocalDate.of(2026, 2, 5), BigDecimal.TEN, "a")))));
        assertThrows(IllegalArgumentException.class, () -> db.render(update(ShEvent.class).set(e -> e.day().to(LocalDate.of(2026, 1, 1))).allShards().all()));
        assertThrows(IllegalArgumentException.class, () -> db.render(update(ShEvent.class)
            .byId(new ShEventRow(1L, LocalDate.of(2026, 2, 5), BigDecimal.TEN, "a"))
            .diffFrom(new ShEventRow(1L, LocalDate.of(2026, 1, 5), BigDecimal.TEN, "a"))));
    }

    @Test
    void 按条件删除每张分表一条() {
        assertEquals(List.of("SH_ORDER_0", "SH_ORDER_1"), tables(db.render(deleteFrom(ShOrder.class).where(o -> o.customer().in(List.of(2, 3))))));
    }

    @Test
    void 取模的键必须是整数且规则的键必须是表的列() {
        assertThrows(IllegalArgumentException.class, () -> ShardRule.modulo("customer", 2).route("SH_ORDER", "x"));
        assertThrows(IllegalArgumentException.class, () -> new TestDatabase(c -> c.shard(ShOrder.class, ShardRule.modulo("nope", 2))));
    }
}

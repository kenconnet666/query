package org.dpdns.zerodep.query.it.schema;

import org.dpdns.zerodep.query.ddl.SchemaPlan;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.testing.Shards;
import org.dpdns.zerodep.query.testing.Shards.ShEvent;
import org.dpdns.zerodep.query.testing.Shards.ShEventRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.all;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.count;
import static org.dpdns.zerodep.query.Sql.deleteFrom;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.insertInto;
import static org.dpdns.zerodep.query.Sql.sum;
import static org.dpdns.zerodep.query.Sql.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同库分表：SH_EVENT 按发生日分成 2026 年 1 到 3 月的 SH_EVENT_202601、_202602、_202603（声明与规则在 {@code testing.Shards}）。
 * 读写按条件里的分表键落到对应月份的表，没有分表键的语句要显式指定表或 {@code allShards()}。
 */
@LiveTest
@DisplayName("分表")
class ShardingTest {
    @Parameter Live live;

    @Test
    void 按条件读写各月的物理表() {
        Database sharded = live.database(Shards::configure);
        // 结构变更按规则给每个月建一张表；已经建过时计划为空。
        SchemaPlan plan = sharded.schema().plan(ShEvent.class);
        if (!plan.isEmpty()) {
            assertEquals(3, plan.changes().size(), plan::toString);
            plan.apply();
        }
        assertTrue(sharded.schema().check(ShEvent.class).isEmpty(), "every month has its table");
        sharded.execute(deleteFrom(ShEvent.class).allShards().all());
        sharded.execute(insertInto(ShEvent.class).values(
            new ShEventRow(1L, LocalDate.of(2026, 1, 5), new BigDecimal("1.50"), "a"),
            new ShEventRow(2L, LocalDate.of(2026, 1, 20), new BigDecimal("2.00"), "b"),
            new ShEventRow(3L, LocalDate.of(2026, 2, 3), new BigDecimal("4.00"), "a"),
            new ShEventRow(4L, LocalDate.of(2026, 3, 9), new BigDecimal("8.00"), "a")));
        long january = sharded.one(from(ShEvent.class).onShards("SH_EVENT_202601").select(e -> count()));
        long march = sharded.one(from(ShEvent.class).onShards("SH_EVENT_202603").select(e -> count()));
        assertEquals(List.of(2L, 1L), List.of(january, march), "rows land in the table of their month");
        // 日期范围 [1 月 1 日, 3 月 1 日) 只查 1 月和 2 月的表：备注 a 的是 1.50 + 4.00。
        assertEquals(0, new BigDecimal("5.50").compareTo(sharded.one(from(ShEvent.class)
            .where(e -> all(e.day().range(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1)), e.note().eq("a")))
            .select(e -> sum(e.amount())))));
        assertEquals(List.of(1L, 3L, 4L), sharded.list(from(ShEvent.class).allShards().where(e -> e.note().eq("a")).orderBy(e -> e.id()).select(e -> e.id())));
        assertEquals(1, sharded.execute(update(ShEvent.class).set(e -> e.note().to("c")).where(e -> e.day().eq(LocalDate.of(2026, 2, 3)))));
        assertEquals(1, sharded.execute(update(ShEvent.class).byId(new ShEventRow(4L, LocalDate.of(2026, 3, 9), new BigDecimal("9.00"), "a"))));
        assertEquals(2, sharded.execute(deleteFrom(ShEvent.class).where(e -> e.day().between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))));
        assertEquals(List.of("3:c:4.00", "4:a:9.00"), sharded.list(from(ShEvent.class).allShards().orderBy(e -> e.id())
                .select(e -> cols(e.id(), e.note(), e.amount()))).stream()
            .map(r -> r.get("id") + ":" + r.get("note") + ":" + ((BigDecimal) r.get("amount")).setScale(2)).toList());
        assertThrows(IllegalStateException.class, () -> sharded.list(from(ShEvent.class).where(e -> e.note().eq("a")).select(e -> e.id())),
            "without the shard key the query must name its tables");
    }
}

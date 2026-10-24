package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.ShardRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 同库分表的测试表，与 TS 的 fixtures/shards.ts 对应：按月分成 SH_EVENT_202601 到 SH_EVENT_202603 的事件，
 * 按客户取模分成 SH_ORDER_0、SH_ORDER_1 的订单。
 */
public final class Shards {
    private static final String SCHEMA = "QUERY_IT";

    private Shards() { }

    /** 按月分表的事件。 */
    @Table(schema = SCHEMA, value = "SH_EVENT")
    public interface ShEvent extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 发生日，分表键。 */
        @Column(value = "DAY", nullable = false) Col<LocalDate> day();

        /** 金额。 */
        @Column(value = "AMOUNT", precision = 10, scale = 2) Col<BigDecimal> amount();

        /** 备注。 */
        @Column(value = "NOTE", length = 20) Col<String> note();
    }

    /** 事件的一行。 */
    public record ShEventRow(Long id, LocalDate day, BigDecimal amount, String note) { }

    /** 按客户取模分表的订单。 */
    @Table(schema = SCHEMA, value = "SH_ORDER")
    public interface ShOrder extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 客户编号，分表键。 */
        @Column(value = "CUSTOMER", nullable = false) Col<Integer> customer();

        /** 金额。 */
        @Column(value = "TOTAL", precision = 10, scale = 2) Col<BigDecimal> total();
    }

    /** 订单的一行。 */
    public record ShOrderRow(Long id, Integer customer, BigDecimal total) { }

    /** 按月：2026 年 1 到 3 月。 */
    public static final ShardRule MONTHLY = ShardRule.monthly("day", YearMonth.of(2026, 1), YearMonth.of(2026, 3));

    /** 取模：两张表。 */
    public static final ShardRule MODULO = ShardRule.modulo("customer", 2);

    /** 在数据库实例上注册这两张分表。 */
    public static void configure(Database.Config c) {
        c.shard(ShEvent.class, MONTHLY).shard(ShOrder.class, MODULO);
    }
}

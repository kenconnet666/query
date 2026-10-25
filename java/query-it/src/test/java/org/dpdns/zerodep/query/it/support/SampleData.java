package org.dpdns.zerodep.query.it.support;

import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.EventRow;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Makt;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Mardh;
import org.dpdns.zerodep.query.testing.Erp.Mchbh;
import org.dpdns.zerodep.query.testing.Erp.Vbkd;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.dpdns.zerodep.query.Sql.insertInto;

/**
 * 读查询共用的样例数据：SAP 风格的几张表（声明在 {@code testing.Erp}），每个库在整轮测试里只建一次。
 * 写数据的测试不碰这些表，用自己的表；必须改它们的测试在事务里改完再回滚。
 *
 * <p>共享实例 {@code live.db()} 带全局过滤器，只看客户端（MANDT）100 的行，所以下面客户端 200 的行只有
 * {@code unfiltered()} 的查询才看得到。
 *
 * <pre>
 * MARA 物料主数据
 *   客户端  物料  零件号  制造商  物料类型
 *   100     M1    "P1 "   MK1     ZMAT      零件号带一个尾随空格
 *   100     M2    P2      MK2     ZMAT
 *   100     M3    P3      MK1     ZOTH      没有交货
 *   200     M1    X1      MK1     ZMAT      另一个客户端
 *   100     M4    P1      ""      ZOTH      制造商是空串；没有交货
 *
 * LIPS 交货行（按这个顺序插入，PostgreSQL 新表的扫描顺序就是它）
 *   客户端  交货单  物料  创建日期     数量  单位  销售订单
 *   100     D1      M1    2026-01-15     3    EA    S1
 *   100     D2      M1    2026-01-20     2    EA    S2
 *   100     D3      M2    2026-02-10     5    EA    S1
 *   100     D4      M2    2025-12-31     7    EA    S1
 *   100     D5      M1    2026-03-01     1    EA    S9        S9 没有销售订单数据（VBKD）
 *   200     D6      M1    2026-01-15   100    EA    S1
 *   创建日期按 SAP 的写法存成文本 yyyyMMdd，数量是 DECIMAL(13,3)。
 *
 * MAKT 物料描述                       VBKD 销售订单数据
 *   客户端  物料  语言  描述             客户端  销售订单
 *   100     M1    1     Pump             100     S1
 *   100     M2    2     Valve            100     S2
 *   200     M1    1     Other            200     S1
 *   所以客户端 100 里只有 M1 有语言 1 的描述。
 *
 * MARDH 物料月度库存                  MCHBH 批次月度库存
 *   客户端  物料  年度  月  数量         客户端  物料  年度  月  数量
 *   100     M1    2026  01   10          100     M1    2026  02   6
 *   100     M2    2026  01   NULL
 *   100     M1    2025  12    4
 *
 * EVENTS 真正的 DATE 和 TIMESTAMP 列（时区测试用）
 *   编号  日期         时间戳
 *   1     2024-03-31   2024-03-31 02:30:00   欧洲夏令时开始的那个凌晨
 *   2     2011-12-30   2011-12-30 00:00:00   萨摩亚跳过的那一天
 * </pre>
 */
public final class SampleData {
    /** 只放静态方法。 */
    private SampleData() { }

    /** MARA 的一行。 */
    public record MaraRow(String client, String material, String partNumber, String manufacturer, String materialType) { }

    /** LIPS 的一行。 */
    public record LipsRow(String client, String delivery, String material, LocalDate createdOn, BigDecimal quantity, String unit, String salesOrder) { }

    /** MAKT 的一行。 */
    public record MaktRow(String client, String material, String language, String text) { }

    /** VBKD 的一行。 */
    public record VbkdRow(String client, String salesOrder) { }

    /** MARDH、MCHBH 的一行。 */
    public record StockRow(String client, String material, String year, String period, BigDecimal quantity) { }

    /** 按声明重建这几张表，写入类注释里的行。 */
    static void create(Live live) {
        live.recreate(Mara.class, Lips.class, Makt.class, Vbkd.class, Mardh.class, Mchbh.class, Event.class);
        Database db = live.plain();
        db.execute(insertInto(Mara.class).values(
            new MaraRow("100", "M1", "P1 ", "MK1", "ZMAT"),
            new MaraRow("100", "M2", "P2", "MK2", "ZMAT"),
            new MaraRow("100", "M3", "P3", "MK1", "ZOTH"),
            new MaraRow("200", "M1", "X1", "MK1", "ZMAT"),
            new MaraRow("100", "M4", "P1", "", "ZOTH")));
        db.execute(insertInto(Lips.class).values(
            new LipsRow("100", "D1", "M1", LocalDate.of(2026, 1, 15), new BigDecimal("3.000"), "EA", "S1"),
            new LipsRow("100", "D2", "M1", LocalDate.of(2026, 1, 20), new BigDecimal("2.000"), "EA", "S2"),
            new LipsRow("100", "D3", "M2", LocalDate.of(2026, 2, 10), new BigDecimal("5.000"), "EA", "S1"),
            new LipsRow("100", "D4", "M2", LocalDate.of(2025, 12, 31), new BigDecimal("7.000"), "EA", "S1"),
            new LipsRow("100", "D5", "M1", LocalDate.of(2026, 3, 1), new BigDecimal("1.000"), "EA", "S9"),
            new LipsRow("200", "D6", "M1", LocalDate.of(2026, 1, 15), new BigDecimal("100.000"), "EA", "S1")));
        db.execute(insertInto(Makt.class).values(
            new MaktRow("100", "M1", "1", "Pump"),
            new MaktRow("100", "M2", "2", "Valve"),
            new MaktRow("200", "M1", "1", "Other")));
        db.execute(insertInto(Vbkd.class).values(new VbkdRow("100", "S1"), new VbkdRow("100", "S2"), new VbkdRow("200", "S1")));
        db.execute(insertInto(Mardh.class).values(
            new StockRow("100", "M1", "2026", "01", new BigDecimal("10.000")),
            new StockRow("100", "M2", "2026", "01", null),
            new StockRow("100", "M1", "2025", "12", new BigDecimal("4.000"))));
        db.execute(insertInto(Mchbh.class).values(new StockRow("100", "M1", "2026", "02", new BigDecimal("6.000"))));
        db.execute(insertInto(Event.class).values(
            new EventRow(1, LocalDate.of(2024, 3, 31), LocalDateTime.of(2024, 3, 31, 2, 30)),
            new EventRow(2, LocalDate.of(2011, 12, 30), LocalDateTime.of(2011, 12, 30, 0, 0))));
    }
}

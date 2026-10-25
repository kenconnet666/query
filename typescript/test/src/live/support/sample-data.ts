import { Decimal } from "decimal.js";
import { parseDate, parseDateTime } from "@internationalized/date";
import { insertInto } from "@zerodep-dev/query-core";
import { Event, Lips, Makt, Mara, Mardh, Mchbh, Vbkd } from "../../fixtures/erp.js";
import type { Live } from "./live.js";

/**
 * 读查询共用的样例数据：SAP 风格的几张表（声明在 fixtures/erp.ts），与 Java 的 SampleData 相同，每个套件开始时按声明重建。
 * 写数据的测试不碰这些表，用自己的表；必须改它们的测试在事务里改完再回滚。
 *
 * 共享实例 `live.db` 带全局过滤器，只看客户端（MANDT）100 的行，所以下面客户端 200 的行只有 `unfiltered()` 的查询才看得到。
 *
 * ```
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
 * ```
 */
export async function createSampleData(live: Live): Promise<void> {
  await live.recreate(Mara, Lips, Makt, Vbkd, Mardh, Mchbh, Event);
  const db = live.plain;
  await db.execute(
    insertInto(Mara).values(
      { client: "100", material: "M1", partNumber: "P1 ", manufacturer: "MK1", materialType: "ZMAT" },
      { client: "100", material: "M2", partNumber: "P2", manufacturer: "MK2", materialType: "ZMAT" },
      { client: "100", material: "M3", partNumber: "P3", manufacturer: "MK1", materialType: "ZOTH" },
      { client: "200", material: "M1", partNumber: "X1", manufacturer: "MK1", materialType: "ZMAT" },
      { client: "100", material: "M4", partNumber: "P1", manufacturer: "", materialType: "ZOTH" },
    ),
  );
  await db.execute(
    insertInto(Lips).values(
      { client: "100", delivery: "D1", material: "M1", createdOn: parseDate("2026-01-15"), quantity: new Decimal("3.000"), unit: "EA", salesOrder: "S1" },
      { client: "100", delivery: "D2", material: "M1", createdOn: parseDate("2026-01-20"), quantity: new Decimal("2.000"), unit: "EA", salesOrder: "S2" },
      { client: "100", delivery: "D3", material: "M2", createdOn: parseDate("2026-02-10"), quantity: new Decimal("5.000"), unit: "EA", salesOrder: "S1" },
      { client: "100", delivery: "D4", material: "M2", createdOn: parseDate("2025-12-31"), quantity: new Decimal("7.000"), unit: "EA", salesOrder: "S1" },
      { client: "100", delivery: "D5", material: "M1", createdOn: parseDate("2026-03-01"), quantity: new Decimal("1.000"), unit: "EA", salesOrder: "S9" },
      { client: "200", delivery: "D6", material: "M1", createdOn: parseDate("2026-01-15"), quantity: new Decimal("100.000"), unit: "EA", salesOrder: "S1" },
    ),
  );
  await db.execute(
    insertInto(Makt).values(
      { client: "100", material: "M1", language: "1", text: "Pump" },
      { client: "100", material: "M2", language: "2", text: "Valve" },
      { client: "200", material: "M1", language: "1", text: "Other" },
    ),
  );
  await db.execute(insertInto(Vbkd).values({ client: "100", salesOrder: "S1" }, { client: "100", salesOrder: "S2" }, { client: "200", salesOrder: "S1" }));
  await db.execute(
    insertInto(Mardh).values(
      { client: "100", material: "M1", year: "2026", period: "01", unrestricted: new Decimal("10.000") },
      { client: "100", material: "M2", year: "2026", period: "01", unrestricted: null },
      { client: "100", material: "M1", year: "2025", period: "12", unrestricted: new Decimal("4.000") },
    ),
  );
  await db.execute(insertInto(Mchbh).values({ client: "100", material: "M1", year: "2026", period: "02", batchUnrestricted: new Decimal("6.000") }));
  await db.execute(
    insertInto(Event).values(
      { id: 1, onDay: parseDate("2024-03-31"), atTime: parseDateTime("2024-03-31T02:30:00") },
      { id: 2, onDay: parseDate("2011-12-30"), atTime: parseDateTime("2011-12-30T00:00:00") },
    ),
  );
}

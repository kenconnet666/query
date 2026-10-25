import { Decimal } from "decimal.js";
import assert from "node:assert/strict";
import { it } from "node:test";
import { allOf, anyOf, bool, caseWhen, cte, from, keys, nullOf, text, values, type Expr, type Ref, type Table } from "@zerodep-dev/query-core";
import { Lips, Mara, Mardh, Mchbh, type PeriodStock } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * 连接、子查询与集合运算：RIGHT JOIN 和各种子查询比较、INTERSECT / EXCEPT / UNION 的求值顺序、泛型模板拼成的 UNION、
 * 常量行做 CTE。数据见 support/sample-data.ts；共享实例只看客户端 100。与 Java 的 JoinAndSetTest 对应。
 */
liveSuite("连接、子查询与集合运算", (live) => {
  it("RIGHT JOIN 保留右侧的行且子查询比较与 NULL 安全比较正确", async () => {
    // ON 里多了一个永远不成立的条件，所以右侧每一行都保留、左侧全是 NULL；再用 WHERE 里的五种比较筛掉一部分。
    const d2 = from(Lips).where((x) => x.delivery.eq("D2")).select((x) => x.quantity);
    const m2 = from(Lips).where((x) => x.material.eq("M2")).select((x) => x.quantity);
    const rows = await live.db.list(
      from(Mara)
        .rightJoin(Lips, (m, l) => {
          m.material.eq(l.material);
          m.materialType.eq("NONE");
        })
        .where((m, l) => {
          keys(l.delivery, l.material).in(from(Lips).where((x) => x.unit.eq("EA")).select((x) => ({ delivery: x.delivery, material: x.material })));
          l.quantity.ge(anyOf(d2));
          l.quantity.lt(allOf(m2));
          m.partNumber.isNotDistinctFrom(nullOf(text));
          l.unit.isDistinctFrom(m.partNumber);
        })
        .orderBy((_m, l) => l.delivery)
        .select((m, l) => ({ delivery: l.delivery, partNumber: m.partNumber, big: bool(() => l.quantity.gt(new Decimal("2"))) })),
    );
    assert.deepEqual(rows.map((r) => `${r.delivery}:${r.partNumber}:${r.big}`), ["D1:null:true", "D2:null:false"]);
  });

  it("交集与差集按调用顺序求值", async () => {
    const other = from(Mara).where((m) => m.materialType.eq("ZOTH")).select((m) => m.material); // M3、M4
    const delivered = from(Lips).select((l) => l.material); // M1、M2
    const zmat = from(Mara).where((m) => m.materialType.eq("ZMAT")).select((m) => m.material); // M1、M2
    const mk1 = from(Mara).where((m) => m.manufacturer.eq("MK1")).select((m) => m.material); // M1、M3
    const mk2 = from(Mara).where((m) => m.manufacturer.eq("MK2")).select((m) => m.material); // M2
    // ({M3, M4} ∪ {M1, M2}) ∩ {M1, M2} − ({M1, M3} − {M2})：按调用顺序加括号；照 PostgreSQL 的优先级不加括号会得到别的结果。
    assert.deepEqual(await live.db.list(other.union(delivered).intersect(zmat).except(mk1.except(mk2))), ["M2"]);
  });

  it("右侧的 UNION 保持原有分组", async () => {
    // D1 UNION ALL (D3 UNION D2)：右侧先去重成 {M2, M1}，再与 D1 的 M1 合起来，所以 M1 出现两次。
    const materialOf = (delivery: string) => from(Lips).where((l) => l.delivery.eq(delivery)).select((l) => l.material);
    assert.deepEqual((await live.db.list(materialOf("D1").unionAll(materialOf("D3").union(materialOf("D2"))))).sort(), ["M1", "M1", "M2"]);
  });

  it("泛型模板生成的 UNION 合并两张库存表", async () => {
    // MARDH 与 MCHBH 形状相同、数量字段不同：同一个模板函数套用在两张表上，再 UNION ALL。
    const stock = <E extends PeriodStock>(table: Table<E>, quantity: (h: Ref<E>) => Expr<Decimal>) =>
      from(table)
        .where((h) => h.year.eq("2026"))
        .select((h) => ({ material: h.material, year: h.year, period: h.period, quantity: quantity(h) }));
    const rows = await live.db.list(stock(Mardh, (h) => h.unrestricted.coalesce(new Decimal("0"))).unionAll(stock(Mchbh, (h) => h.batchUnrestricted.coalesce(new Decimal("0")))));
    assert.deepEqual(new Set(rows.map((r) => `${r.material}/${r.period}/${Number(r.quantity)}`)), new Set(["M1/01/10", "M2/01/0", "M1/02/6"]), "2026 年的三行；M2 的数量是 NULL，按 0 读");
  });

  it("常量行做 CTE 并按 CASE 表达式分组", async () => {
    // sizes 是写在查询里的码表；totals 按数量分档后合计。码表 LEFT JOIN 合计，没有交货的档位得 0。
    const size = (l: Ref<Lips>) => caseWhen(() => l.quantity.le(new Decimal("2")), "small").when(() => l.quantity.le(new Decimal("5")), "medium").otherwise("large");
    const sizes = cte(
      "sizes",
      values([
        { name: "small", ord: 1 },
        { name: "medium", ord: 2 },
        { name: "large", ord: 3 },
      ]),
    );
    const totals = cte(
      "totals",
      from(Lips)
        .groupBy((l) => size(l))
        .select((l) => ({ bucket: size(l), total: l.quantity.sum() })),
    );
    const rows = await live.db.list(
      from(sizes)
        .leftJoin(totals, (s, t) => t.bucket.eq(s.name))
        .orderBy((s) => s.ord)
        .select((s, t) => ({ name: s.name, total: t.total.coalesce(new Decimal("0")) })),
    );
    assert.deepEqual(rows.map((r) => `${r.name}=${Number(r.total)}`), ["small=3", "medium=8", "large=7"], "small：2、1；medium：3、5；large：7");
  });
});

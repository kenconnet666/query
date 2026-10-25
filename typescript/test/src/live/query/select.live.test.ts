import assert from "node:assert/strict";
import { it } from "node:test";
import { all, caseWhen, count, countDistinct, cte, exists, from, max, sum, TooManyRowsError, trim } from "@zerodep-dev/query-core";
import { LineVO, Lips, Makt, Mara, MaraView, Order, Vbkd } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * 查询与结果映射：CTE 和 EXISTS、全局过滤器、按名映射到结果类、嵌套结果、列转换、取一行的几种写法、按键装回别的查询的结果。
 * 数据见 support/sample-data.ts；共享实例 `live.db` 只看客户端 100。与 Java 的 SelectTest 对应。
 */
liveSuite("查询与结果映射", (live) => {
  /** 2026 年上半年的交货汇总，maker 为 null 时不按制造商筛选。 */
  const deliveries = (maker: string | null) => {
    // 先在 CTE 里挑出有效物料（ZMAT），零件号去掉首尾空格；skipEmpty 让值为 null 的条件不产生条件。
    const scope = cte(
      "material_scope",
      from(Mara)
        .skipEmpty()
        .where((m) => all(m.materialType.eq("ZMAT"), m.manufacturer.eq(maker)))
        .distinct()
        .select((m) => ({ client: m.client, material: m.material, partNumber: caseWhen(m.partNumber.ne(trim(m.partNumber)), trim(m.partNumber)).otherwise(m.partNumber) })),
    );
    return from(scope)
      .join(Lips, (s, l) => l.client.eq(s.client).and(l.material.eq(s.material)))
      .leftJoin(Makt, (s, _l, t) => t.material.eq(s.material).and(t.language.eq("1")))
      .where((_s, l) => all(l.createdOn.between("2026-01-01", "2026-06-30"), exists(from(Vbkd).where((v) => v.salesOrder.eq(l.salesOrder)))))
      .groupBy((s) => s.partNumber)
      .orderBy((s) => s.partNumber.asc())
      .select((s, l, t) => ({ partNumber: s.partNumber, name: max(t.text.coalesce(s.partNumber)), quantity: sum(l.quantity) }));
  };

  it("带 CTE 和 EXISTS 的交货汇总按零件号合计", async () => {
    const text = (rows: readonly { partNumber: string; name: string | null; quantity: string | null }[]) => rows.map((r) => `${r.partNumber}:${r.name}:${Number(r.quantity)}`);
    assert.deepEqual(text(await live.db.list(deliveries(null))), ["P1:Pump:5", "P2:P2:5"], "M1 的 D5 引用的 S9 没有销售订单数据，所以只算 D1、D2；M2 没有语言 1 的描述，名称取零件号；D4 在 2025 年");
    assert.deepEqual(text(await live.db.list(deliveries("MK2"))), ["P2:P2:5"]);
  });

  it("全局过滤器只读当前客户端且可以关闭", async () => {
    assert.equal(await live.db.one(from(Lips).select(() => count())), 5);
    assert.equal(await live.db.one(from(Lips).unfiltered().select(() => count())), 6, "客户端 200 的 D6 也算上");
  });

  it("按名映射到结果类并按 SAP 日期转换", async () => {
    // 投影的键对上结果类的字段；LEFT JOIN 的表上也有全局过滤条件，它进 ON 而不是 WHERE，所以没有描述的行照样保留。
    const rows = await live.db.list(
      from(Lips)
        .leftJoin(Makt, (l, t) => t.material.eq(l.material).and(t.language.eq("1")))
        .orderBy((l) => l.delivery.asc())
        .select((l, t) => ({ delivery: l.delivery, materialNo: l.material, createdOn: l.createdOn, text: t.text }))
        .into(LineVO),
    );
    assert.ok(rows[0] instanceof LineVO);
    assert.deepEqual(
      rows.map((r) => `${r.delivery}:${r.materialNo}:${r.createdOn}:${r.text}`),
      ["D1:M1:2026-01-15:Pump", "D2:M1:2026-01-20:Pump", "D3:M2:2026-02-10:null", "D4:M2:2025-12-31:null", "D5:M1:2026-03-01:Pump"],
    );
  });

  it("SAP 日期参数按转换器绑定", async () => {
    assert.deepEqual(await live.db.list(from(Lips).where((l) => l.createdOn.eq("2026-01-15")).select((l) => l.delivery)), ["D1"]);
  });

  it("嵌套结果和 JS 端计算", async () => {
    // map 在 JS 端把数量合计折成万单位放进嵌套的 amount。
    const rows = await live.db.list(
      from(Lips)
        .join(Mara, (l, m) => m.material.eq(l.material))
        .groupBy((_l, m) => m.partNumber)
        .orderBy((_l, m) => m.partNumber.asc())
        .select((l, m) => ({ partNumber: m.partNumber, quantity: sum(l.quantity) }))
        .map((r) => ({ partNumber: r.partNumber, amount: { quantity: r.quantity, wan: r.quantity === null ? 0 : Number(r.quantity) / 10_000 } })),
    );
    assert.deepEqual(rows.map((r) => `${r.partNumber.trim()}:${Number(r.amount.quantity)}`), ["P1:6", "P2:12"], "M4 的零件号也是 P1，但它没有交货，内连接里不出现");
    assert.ok(Math.abs(rows[0]!.amount.wan - 0.0006) < 1e-12);
  });

  it("类名是保留字的实体也能查询", async () => {
    // Order 映射到 LIPS，生成的别名 order 是保留字，要加引号；Order 不继承 ClientEntity，全局过滤器不管它。
    assert.deepEqual(await live.db.list(from(Order).where((o) => o.material.eq("M1")).orderBy((o) => o.delivery).select((o) => o.delivery)), ["D1", "D2", "D5", "D6"]);
  });

  it("可选的一行与唯一的一行", async () => {
    const deliveries = from(Lips).orderBy((l) => l.delivery).select((l) => l.delivery);
    assert.equal(await live.db.optional(from(Lips).where((l) => l.delivery.eq("D1")).select((l) => l.delivery)), "D1");
    assert.equal(await live.db.optional(from(Lips).where((l) => l.delivery.eq("none")).select((l) => l.delivery)), undefined);
    await assert.rejects(live.db.optional(deliveries), TooManyRowsError, "optional 最多一行");
    await assert.rejects(live.db.one(deliveries), TooManyRowsError, "one 必须正好一行");
  });

  it("列转换按转换后的值读取且可与原值并存", async () => {
    // MaraView 把零件号去空格（@trimmed）、空制造商读作 NULL（@blankAsNull），同时保留制造商的原值列。
    const rows = await live.db.list(
      from(MaraView)
        .groupBy((m) => m.partNumber)
        .orderBy((m) => m.partNumber)
        .select((m) => ({ part: m.partNumber, makers: countDistinct(m.maker), stored: countDistinct(m.storedMaker) })),
    );
    assert.deepEqual(rows.map((r) => `${r.part}:${r.makers}:${r.stored}`), ["P1:1:2", "P2:1:1", "P3:1:1"], "P1 组有 M1（MK1）和 M4（空串）：去掉空串后只有一个制造商");
    assert.deepEqual(await live.db.list(from(MaraView).where((m) => m.maker.isNull()).select((m) => m.material)), ["M4"]);
  });

  it("按键把另一个查询的结果装回行", async () => {
    // attach 一对一、attachMany 一对多；键为 null 的行和找不到的键都装回 null 或空数组。
    const rows = ["M1", "M2", "M3", "M9", null].map((material) => ({ material, partNumber: "unset" as string | null, deliveries: [] as string[] }));
    await live.db.attach(
      rows,
      (h) => h.material,
      (keys) => from(Mara).where((m) => m.material.in(keys)).select((m) => ({ material: m.material, partNumber: m.partNumber })),
      (r) => r.material,
      (h, r) => {
        h.partNumber = r === null ? null : r.partNumber.trim();
      },
    );
    await live.db.attachMany(
      rows,
      (h) => h.material,
      (keys) => from(Lips).where((l) => l.material.in(keys)).orderBy((l) => l.delivery).select((l) => ({ material: l.material, delivery: l.delivery })),
      (r) => r.material,
      (h, list) => {
        h.deliveries = list.map((r) => r.delivery);
      },
    );
    assert.deepEqual(rows.map((h) => `${h.material}:${h.partNumber}:${h.deliveries.join(",")}`), ["M1:P1:D1,D2,D5", "M2:P2:D3,D4", "M3:P3:", "M9:null:", "null:null:"]);
  });
});

import { Decimal } from "decimal.js";
import { parseDate } from "@internationalized/date";
import assert from "node:assert/strict";
import { it } from "node:test";
import { deleteFrom, from, update } from "@zerodep-dev/query-core";
import { Lips, Mara } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * 导航关系与对象树：多对一按需 LEFT JOIN、一对多的 EXISTS 和相关子查询、子数组与对象树的批量加载、分组连接，以及写语句的条件经过关系。
 * 关系声明在 fixtures/erp.ts：物料的 `deliveries`（一对多）和 `text`（语言 1 的描述），交货行的 `mara`、`salesData`。
 * 数据见 support/sample-data.ts：M1 有 D1、D2、D5 和描述 Pump，M2 有 D3、D4 但没有语言 1 的描述，M3、M4 没有交货。
 * 与 Java 的 RelationTest 对应。
 */
liveSuite("导航关系与对象树", (live) => {
  it("导航关系按需连接并给出正确结果", async () => {
    const rows = await live.db.list(
      from(Mara)
        .orderBy((m) => m.material)
        .select((m) => ({
          material: m.material,
          lines: m.deliveries.count(),
          total: m.deliveries.sum((l) => l.quantity),
          latest: m.deliveries.first((l) => l.createdOn.desc()).delivery,
          text: m.text.text,
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${Number(r.lines)}:${r.total === null ? null : Number(r.total)}:${r.latest}:${r.text}`), [
      "M1:3:6:D5:Pump",
      "M2:2:12:D3:null",
      "M3:0:null:null:null",
      "M4:0:null:null:null",
    ]);
    assert.deepEqual(await live.db.list(from(Lips).where((l) => l.mara.materialType.eq("ZMAT")).orderBy((l) => l.delivery).select((l) => l.delivery)), ["D1", "D2", "D3", "D4", "D5"]);
    assert.deepEqual(await live.db.list(from(Lips).where((l) => l.salesData.none()).select((l) => l.delivery)), ["D5"], "S9 没有销售订单数据");
    assert.deepEqual(await live.db.list(from(Mara).where((m) => {
      m.deliveries.any((l) => l.quantity.gt(new Decimal("2.5")));
      m.deliveries.count().ge(3);
    }).select((m) => m.material)), ["M1"]);
  });

  it("子数组与对象树用一条批量查询加载", async () => {
    // 父行一条语句，全部子行再一条（按父行的键批量取），不是每个父行一条。
    const events = await live.db.capture(async () => {
      const rows = await live.db.list(
        from(Mara)
          .orderBy((m) => m.material)
          .select((m) => ({ material: m.material, deliveries: m.deliveries.orderBy((l) => l.delivery).list((l) => l.delivery) })),
      );
      assert.deepEqual(rows.map((r) => `${r.material}:${r.deliveries.join(",")}`), ["M1:D1,D2,D5", "M2:D3,D4", "M3:", "M4:"]);
    });
    assert.equal(events.length, 2, "one statement for the parents and one for all children");
    const trees = await live.db.list(
      from(Mara)
        .orderBy((m) => m.material)
        .select((m) => ({ material: m.material, text: { text: m.text.text }, deliveries: m.deliveries.list((l) => ({ delivery: l.delivery, quantity: l.quantity })) })),
    );
    assert.deepEqual(trees.map((t) => `${t.material}:${t.text.text}:${t.deliveries.map((l) => `${l.delivery}/${Number(l.quantity)}`).sort().join(",")}`), [
      "M1:Pump:D1/3,D2/2,D5/1",
      "M2:null:D3/5,D4/7",
      "M3:null:",
      "M4:null:",
    ]);
  });

  it("子数组可以限定每个父行取最新的几行", async () => {
    const rows = await live.db.list(
      from(Mara)
        .orderBy((m) => m.material)
        .select((m) => ({ material: m.material, deliveries: m.deliveries.orderBy((l) => l.createdOn.desc()).limit(2).list((l) => l.delivery) })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.deliveries.join(",")}`), ["M1:D5,D2", "M2:D3,D4", "M3:", "M4:"]);
  });

  it("分组连接与相关子查询的结果相同", async () => {
    // groupJoin 把同一个一对多关系上的存在判断、行数、合计和带筛选的行数合成一个按连接键分组的派生表。
    const rows = await live.db.list(
      from(Mara)
        .groupJoin((m) => m.deliveries)
        .where((m) => m.deliveries.any((l) => l.unit.eq("EA")))
        .orderBy((m) => m.material)
        .select((m) => ({
          material: m.material,
          lines: m.deliveries.count(),
          total: m.deliveries.sum((l) => l.quantity),
          recent: m.deliveries.where((l) => l.createdOn.ge(parseDate("2026-01-01"))).count(),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.lines}:${Number(r.total)}:${r.recent}`), ["M1:3:6:3", "M2:2:12:1"]);
    assert.deepEqual(await live.db.list(from(Mara).groupJoin((m) => m.deliveries).where((m) => m.deliveries.none()).orderBy((m) => m.material).select((m) => m.material)), ["M3", "M4"]);
  });

  it("一对多上的去重计数与按顺序拼接", async () => {
    const rows = await live.db.list(
      from(Mara)
        .orderBy((m) => m.material)
        .select((m) => ({
          material: m.material,
          orders: m.deliveries.countDistinct((l) => l.salesOrder),
          deliveries: m.deliveries.orderBy((l) => l.delivery).stringAgg((l) => l.delivery, ","),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.orders}:${r.deliveries}`), ["M1:3:D1,D2,D5", "M2:1:D3,D4", "M3:0:null", "M4:0:null"], "M1 的销售订单 S1、S2、S9；M2 的都是 S1");
  });

  it("更新和删除的条件经过导航关系时只改到关联得上的行", async () => {
    // 在事务里执行再回滚，不改共享的样例数据；PostgreSQL 的 UPDATE 即使值不变也写出新的行版本，会打乱别的测试依赖的扫描顺序。
    await assert.rejects(
      live.db.transaction(async () => {
        // 影响的行数是匹配的行数：没有语言 1 描述的是 M2 的 D3、D4；制造商 MK1 的是 M1 的三行。
        assert.equal(await live.db.execute(update(Lips).set(() => ({ unit: "EA" })).where((l) => l.text.text.isNull())), 2);
        assert.equal(await live.db.execute(update(Lips).set(() => ({ unit: "EA" })).where((l) => l.mara.manufacturer.eq("MK1"))), 3);
        assert.equal(await live.db.execute(deleteFrom(Lips).where((l) => l.mara.materialType.eq("NONE"))), 0);
        throw new Error("roll back");
      }),
      /roll back/,
    );
  });
});

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { count, cte, date, from, max, nest, rowNumber, sum, values, type Query } from "@zerodep-dev/query-core";
import { DeliveryVO, LineVO, Lips, Mara } from "../erp.js";
import { deliveries, lines, partTotals } from "../scenarios.js";
import { FakeDriver, rows, TestDatabase } from "../support.js";

/** A database answering every statement with the given rows. */
function answering(columns: readonly string[], ...values: unknown[][]): { db: TestDatabase; driver: FakeDriver } {
  const driver = new FakeDriver(() => rows(columns, ...values));
  return { db: new TestDatabase({}, driver), driver };
}

describe("投影与映射", () => {
  it("结果按键分组为父项与子项并保持首次出现的顺序", () => {
    const lines = [
      { order: "B", item: "b1" },
      { order: "A", item: "a1" },
      { order: "B", item: "b2" },
      { order: "C", item: null },
    ];
    assert.deepEqual(
      nest(lines, (l) => l.order, (l) => l.item, (first, items) => ({ order: first.order, items })),
      [
        { order: "B", items: ["b1", "b2"] },
        { order: "A", items: ["a1"] },
        { order: "C", items: [] },
      ],
    );
  });

  it("对象投影的键就是列标签，结果按位置解码", async () => {
    const q = deliveries("2026-01-01", "2026-06-30", null);
    assert.deepEqual(q.labels, ["partNumber", "name", "quantity"]);
    const { db } = answering(["PARTNUMBER", "NAME", "QUANTITY"], ["P1", "Pump", "5.000"], ["P2", null, 5]);
    assert.deepEqual(await db.list(q), [
      { partNumber: "P1", name: "Pump", quantity: "5.000" },
      { partNumber: "P2", name: null, quantity: "5" },
    ]);
  });

  it("嵌套对象得到嵌套结果，标签用点连接", async () => {
    const q = from(Lips).select((l) => ({ id: l.delivery, stock: { quantity: sum(l.quantity), unit: max(l.unit) } }));
    assert.deepEqual(q.labels, ["id", "stock.quantity", "stock.unit"]);
    const { db } = answering([], ["D1", "3.000", "EA"]);
    assert.deepEqual(await db.list(q), [{ id: "D1", stock: { quantity: "3.000", unit: "EA" } }]);
  });

  it("展开引用选出实体的全部列", () => {
    const q = from(Lips).select((l) => ({ ...l }));
    assert.deepEqual(q.labels, ["client", "delivery", "material", "createdOn", "quantity", "unit", "salesOrder"]);
  });

  it("列的类型和转换器解码驱动返回的值", async () => {
    const { db } = answering([], ["D1", "M1", "20260115", "Pump"], ["D2", "M1", "00000000", null]);
    const result = await db.list(lines());
    assert.ok(result[0] instanceof LineVO);
    assert.equal(result[0]!.createdOn, "2026-01-15");
    assert.equal(result[1]!.createdOn, null, "an empty SAP date reads as null");
    assert.equal(result[0]!.materialNo, "M1");
  });

  it("JS 端计算用 map 套在结果上", async () => {
    const { db } = answering([], ["P1", "25000"]);
    const [total] = await db.list(partTotals());
    assert.deepEqual(total, { partNumber: "P1", amount: { quantity: "25000", wan: 2.5 } });
  });

  it("同一个查询可以映射成不同的类", async () => {
    const q = from(Lips)
      .groupBy((l) => l.material)
      .select((l) => ({ partNumber: l.material, name: max(l.unit), quantity: sum(l.quantity), extra: count() }));
    const { db } = answering([], ["M1", "EA", "6.000", 2]);
    const [vo] = await db.list(q.mapTo(DeliveryVO));
    assert.ok(vo instanceof DeliveryVO);
    assert.equal(vo.label, "M1:EA");
    assert.equal((vo as unknown as Record<string, unknown>).extra, undefined, "columns the class does not have are ignored");
    assert.deepEqual(await db.list(q.map((r) => `${r.partNumber}:${r.quantity}`)), ["M1:6.000"]);
  });

  it("单列查询直接返回值", async () => {
    const { db } = answering([], ["D1"], ["D2"]);
    assert.deepEqual(await db.list(from(Lips).selectValue((l) => l.delivery)), ["D1", "D2"]);
  });

  it("自动投影找不到或找到多个来源时立刻报错", () => {
    assert.throws(() => from(Lips).selectAs(DeliveryVO), /DeliveryVO\.partNumber has no same-named column/);
    class Both {
      material!: string;
    }
    assert.throws(
      () =>
        from(Lips)
          .join(Mara, (l, ma) => ma.material.eq(l.material))
          .selectAs(Both),
      /matches 2 sources/,
    );
  });

  it("只有扁平的对象投影能当作 CTE 或派生表", () => {
    assert.throws(() => cte("x", from(Lips).selectValue((l) => l.delivery)), /flat object projection/);
    assert.throws(() => from(Lips).select((l) => ({ a: { b: l.delivery } })).asTable(), /flat object projection/);
    const ranked = cte(
      "ranked",
      from(Lips).select((l) => ({ delivery: l.delivery, n: rowNumber().over((w) => w.orderBy(l.delivery)) })),
    );
    assert.ok(from(ranked).selectValue((r) => r.n));
  });

  it("UNION 的各段不能自带排序或分页，也不能先分页再合并", () => {
    const limited = from(Lips)
      .limit(1)
      .selectValue((l) => l.delivery);
    const plain = from(Lips).selectValue((l) => l.delivery);
    assert.throws(() => plain.unionAll(limited), /UNION arm/);
    assert.throws(() => limited.unionAll(plain), /UNION arm/);
    assert.throws(() => plain.limit(1).unionAll(plain), /UNION arm|after combining/);
    const a = from(Lips).select((l) => ({ x: l.delivery }));
    const b = from(Lips).select((l) => ({ y: l.delivery }));
    assert.throws(() => (a as unknown as Query<{ y: string }>).unionAll(b), /different columns/);
  });

  it("取单行时只收紧不放宽已有的 LIMIT", async () => {
    const { db, driver } = answering([], ["D1"]);
    await db.one(
      from(Lips)
        .limit(1)
        .selectValue((l) => l.delivery),
    );
    await db.first(from(Lips).selectValue((l) => l.delivery));
    await db.one(from(Lips).selectValue((l) => l.delivery));
    assert.deepEqual(
      driver.statements.map((s) => s.sql.split("\n").at(-1)),
      ["LIMIT 1", "LIMIT 1", "LIMIT 2"],
    );
  });

  it("one 在没有行或多于一行时报错", async () => {
    await assert.rejects(answering([]).db.one(from(Lips).selectValue((l) => l.delivery)), /got none/);
    await assert.rejects(answering([], ["a"], ["b"]).db.one(from(Lips).selectValue((l) => l.delivery)), /more than one/);
  });

  it("分页同时取当前页和总数，计数语句去掉排序", async () => {
    const driver = new FakeDriver((sql) => (sql.startsWith("SELECT COUNT(*)") ? rows(["COUNT"], ["5"]) : rows([], ["D3"], ["D4"])));
    const page = await new TestDatabase({}, driver).page(lines(), 2, 2);
    assert.deepEqual({ total: page.total, totalPages: page.totalPages, size: page.rows.length }, { total: 5, totalPages: 3, size: 2 });
    const countSql = driver.statements.find((s) => s.sql.startsWith("SELECT COUNT(*)"))!.sql;
    assert.ok(!countSql.includes("ORDER BY") && countSql.endsWith(") COUNTED"), countSql);
  });

  it("解码失败时报出列名", async () => {
    const { db } = answering([], ["D1", "M1", "2026-01-15", "Pump"]);
    await assert.rejects(db.list(from(Lips).selectValue((l) => l.quantity)), /Column 'quantity'/);
  });

  it("常量行按值推断列类型，也可以指定类型", async () => {
    const q = values([
      { code: "A", rate: 1, day: "2026-01-31" },
      { code: "B", rate: 1.5, day: "2026-02-28" },
    ], { day: date });
    const { db, driver } = answering([], ["A", "1", "2026-01-31"], ["B", "1.5", "2026-02-28"]);
    assert.deepEqual(await db.list(q), [
      { code: "A", rate: 1, day: "2026-01-31" },
      { code: "B", rate: 1.5, day: "2026-02-28" },
    ]);
    assert.ok(driver.statements[0]!.sql.includes("DATE '2026-01-31'"), driver.statements[0]!.sql);
  });

  it("SUM 等数值函数拒绝文本列", () => {
    assert.throws(() => from(Lips).selectValue((l) => sum(l.material)), /SUM needs a numeric expression, got text/);
  });

  it("没有驱动流式能力时逐行交付全部结果", async () => {
    const { db } = answering([], ["D1"], ["D2"]);
    const seen: string[] = [];
    for await (const d of db.stream(from(Lips).selectValue((l) => l.delivery))) seen.push(d);
    assert.deepEqual(seen, ["D1", "D2"]);
  });
});

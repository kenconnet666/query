import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Decimal } from "decimal.js";
import { Children, from } from "@zerodep-dev/query-core";
import { Lips, Mara } from "../fixtures/erp.js";
import { FakeDriver, rows, TestDatabase, TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

/** 每个物料和它按交货单排序的交货行。 */
const materialLines = () =>
  from(Mara)
    .orderBy((m) => m.material)
    .select((m) => ({
      material: m.material,
      lines: m.deliveries.orderBy((l) => l.delivery).list((l) => ({ delivery: l.delivery, quantity: l.quantity })),
    }));

describe("对象树与子数组", () => {
  it("父查询只多取连接键，子行用一条批量查询按键分给各父行", async () => {
    const q = materialLines();
    assert.equal(
      q.toSql(DIALECT).sql,
      `SELECT MARA.MATNR AS "material", MARA.MATNR AS "lines", MARA.MANDT AS "lines.2"
FROM QUERY_IT.MARA MARA
ORDER BY MARA.MATNR ASC`,
    );
    const driver = new FakeDriver((sql) =>
      sql.includes("FROM QUERY_IT.LIPS")
        ? rows(["value.delivery", "value.quantity", "key0", "key1"], ["D1", "3.000", "M1", "100"], ["D2", "2.000", "M1", "100"], ["D3", "5.000", "M2", "100"])
        : rows(["material", "lines", "lines.2"], ["M1", "M1", "100"], ["M2", "M2", "100"], ["M3", "M3", "100"]),
    );
    const result = await new TestDatabase({}, driver).list(q);
    assert.deepEqual(result, [
      { material: "M1", lines: [{ delivery: "D1", quantity: new Decimal("3.000") }, { delivery: "D2", quantity: new Decimal("2.000") }] },
      { material: "M2", lines: [{ delivery: "D3", quantity: new Decimal("5.000") }] },
      { material: "M3", lines: [] },
    ]);
    assert.equal(driver.statements.length, 2);
    assert.equal(
      driver.statements[1]!.sql,
      `SELECT LIPS.VBELN AS "value.delivery", LIPS.LFIMG AS "value.quantity", LIPS.MATNR AS "key0", LIPS.MANDT AS "key1"
FROM QUERY_IT.LIPS LIPS
WHERE ((LIPS.MATNR = ? AND LIPS.MANDT = ?) OR (LIPS.MATNR = ? AND LIPS.MANDT = ?) OR (LIPS.MATNR = ? AND LIPS.MANDT = ?))
ORDER BY LIPS.VBELN ASC`,
    );
    assert.deepEqual(driver.statements[1]!.parameters, ["M1", "100", "M2", "100", "M3", "100"]);
  });

  it("每个父行限定子行数时，批量查询按父键分区取前几行", async () => {
    const q = from(Mara).select((m) => ({ material: m.material, lines: m.deliveries.orderBy((l) => l.createdOn.desc()).limit(2).list((l) => l.delivery) }));
    const driver = new FakeDriver((sql) => (sql.includes("FROM QUERY_IT.LIPS") ? rows(["value", "key0", "key1"]) : rows(["material", "lines", "lines.2"], ["M1", "M1", "100"])));
    await new TestDatabase({}, driver).list(q);
    assert.equal(
      driver.statements[1]!.sql,
      `SELECT Q."value" AS "value", Q."key0" AS "key0", Q."key1" AS "key1"
FROM (
SELECT LIPS.VBELN AS "value", LIPS.MATNR AS "key0", LIPS.MANDT AS "key1", ROW_NUMBER() OVER (PARTITION BY LIPS.MATNR, LIPS.MANDT ORDER BY LIPS.ERDAT DESC) AS "_q4", LIPS.ERDAT AS "_o5"
FROM QUERY_IT.LIPS LIPS
WHERE LIPS.MATNR = ? AND LIPS.MANDT = ?
) Q
WHERE Q."_q4" <= ?
ORDER BY Q."_o5" DESC`,
    );
    assert.throws(() => from(Mara).select((m) => ({ n: m.deliveries.limit(2).count() })), /call orderBy first/);
  });

  it("单列键用 IN 且重复的键只查一次，键为 null 的父行得到空数组", async () => {
    const q = from(Lips).select((l) => ({ delivery: l.delivery, orders: l.salesData.list((v) => v.salesOrder) }));
    const driver = new FakeDriver((sql) =>
      sql.includes("FROM QUERY_IT.VBKD") ? rows(["value", "key0"], ["S1", "S1"]) : rows([], ["D1", "S1"], ["D2", "S1"], ["D3", null]),
    );
    const result = await new TestDatabase({}, driver).list(q);
    assert.ok(driver.statements[1]!.sql.endsWith("WHERE VBKD.VBELN IN (?)"), driver.statements[1]!.sql);
    assert.deepEqual(result, [
      { delivery: "D1", orders: ["S1"] },
      { delivery: "D2", orders: ["S1"] },
      { delivery: "D3", orders: [] },
    ]);
  });

  it("父行很多时按批查询子行", async () => {
    const q = from(Lips).select((l) => ({ delivery: l.delivery, orders: l.salesData.list((v) => v.salesOrder) }));
    const parents = Array.from({ length: Children.BATCH * 2 + 1 }, (_, i) => [`D${i}`, `S${i}`]);
    const driver = new FakeDriver((sql) => (sql.includes("FROM QUERY_IT.VBKD") ? rows([]) : rows([], ...parents)));
    assert.equal((await new TestDatabase({}, driver).list(q)).length, parents.length);
    assert.deepEqual(driver.statements.slice(1).map((s) => s.parameters.length), [500, 500, 1]);
  });

  it("子投影里还可以再有子数组", async () => {
    const q = from(Mara).select((m) => ({
      material: m.material,
      deliveries: m.deliveries.list((l) => ({ delivery: l.delivery, sales: l.salesData.list((v) => ({ order: v.salesOrder })) })),
    }));
    const driver = new FakeDriver((sql) => {
      if (sql.includes("FROM QUERY_IT.LIPS")) return rows([], ["D1", "S1", "M1", "100"]);
      if (sql.includes("FROM QUERY_IT.VBKD")) return rows([], ["S1", "S1"]);
      return rows([], ["M1", "M1", "100"]);
    });
    assert.deepEqual(await new TestDatabase({}, driver).list(q), [{ material: "M1", deliveries: [{ delivery: "D1", sales: [{ order: "S1" }] }] }]);
    assert.equal(driver.statements.length, 3);
  });

  it("子数组的键不受 only 影响，不能参与 UNION，流式读取时先读完全部行", async () => {
    const q = materialLines();
    assert.ok(q.only(["material"]).toSql(DIALECT).sql.startsWith('SELECT MARA.MATNR AS "material", MARA.MATNR AS "lines", MARA.MANDT AS "lines.2"'));
    assert.throws(() => from(Mara).select((m) => ({ material: m.material, lines: m.deliveries.list((l) => l.delivery) })).unionAll(from(Mara).select((m) => ({ material: m.material, lines: m.deliveries.list((l) => l.delivery) }))), /UNION/);
    const driver = new FakeDriver((sql) => (sql.includes("FROM QUERY_IT.LIPS") ? rows([]) : rows([], ["M1", "M1", "100"])));
    const streamed = [];
    for await (const row of new TestDatabase({}, driver).stream(q)) streamed.push(row);
    assert.deepEqual(streamed, [{ material: "M1", lines: [] }]);
  });
});

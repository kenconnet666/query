import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { all, count, filter, from, many, table, column, type Many } from "@zerodep-dev/query-core";
import { ClientEntity, Lips, Mara, SCHEMA } from "../fixtures/erp.js";
import { TestDatabase, TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

/** 一对多的连接条件里没有两张表之间的等值，first() 无法分组。 */
@table("MARA", { schema: SCHEMA })
class LooseMara {
  @column("MATNR") material!: string;
  @many(() => Lips, (_m, l) => l.unit.eq("EA")) units!: Many<Lips>;
}

describe("关系与导航", () => {
  it("多对一关系按需 LEFT JOIN 且同一关系只连接一次", () => {
    const r = from(Lips)
      .where((l) => l.mara.manufacturer.eq("MK1"))
      .orderBy((l) => l.mara.partNumber)
      .select((l) => ({ delivery: l.delivery, partNumber: l.mara.partNumber, text: l.text.text }))
      .toSql(DIALECT);
    assert.equal(
      r.sql,
      `SELECT LIPS.VBELN AS "delivery", MARA.MFRPN AS "partNumber", MAKT.MAKTX AS "text"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.MANDT = LIPS.MANDT AND MAKT.SPRAS = ?
WHERE MARA.MFRNR = ?
ORDER BY MARA.MFRPN ASC`,
    );
    assert.deepEqual(r.parameters, ["1", "MK1"]);
  });

  it("没有用到的关系不会出现在 SQL 里", () => {
    const none: string | null = null;
    const sql = from(Lips)
      .skipEmpty()
      .where((l) => l.mara.manufacturer.eq(none))
      .select((l) => l.delivery)
      .toSql(DIALECT).sql;
    assert.equal(sql, 'SELECT LIPS.VBELN AS "delivery"\nFROM QUERY_IT.LIPS LIPS');
  });

  it("关系可以继续导航且全局过滤器进入 ON", () => {
    const db = new TestDatabase({ filters: [filter(ClientEntity, (t) => t.client.eq("100"))] });
    const sql = db.render(from(Lips).select((l) => ({ text: l.mara.text.text }))).sql;
    assert.equal(
      sql,
      `SELECT MAKT.MAKTX AS "text"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MARA MARA ON MARA.MATNR = LIPS.MATNR AND MARA.MANDT = LIPS.MANDT AND MARA.MANDT = ?
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = MARA.MATNR AND MAKT.MANDT = MARA.MANDT AND MAKT.SPRAS = ? AND MAKT.MANDT = ?
WHERE LIPS.MANDT = ?`,
    );
  });

  it("一对多用 EXISTS 和相关子查询", () => {
    const sql = from(Mara)
      .where((m) => all(m.deliveries.any((l) => l.quantity.gt("1")), m.deliveries.where((l) => l.unit.eq("KG")).none()))
      .select((m) => ({ material: m.material, lines: m.deliveries.count(), total: m.deliveries.sum((l) => l.quantity) }))
      .toSql(DIALECT).sql;
    assert.equal(
      sql,
      `SELECT MARA.MATNR AS "material", (SELECT COUNT(*)
FROM QUERY_IT.LIPS LIPS
WHERE LIPS.MATNR = MARA.MATNR AND LIPS.MANDT = MARA.MANDT) AS "lines", (SELECT SUM(LIPS2.LFIMG)
FROM QUERY_IT.LIPS LIPS2
WHERE LIPS2.MATNR = MARA.MATNR AND LIPS2.MANDT = MARA.MANDT) AS "total"
FROM QUERY_IT.MARA MARA
WHERE EXISTS (SELECT 1
FROM QUERY_IT.LIPS LIPS3
WHERE LIPS3.MATNR = MARA.MATNR AND LIPS3.MANDT = MARA.MANDT AND LIPS3.LFIMG > ?) AND NOT EXISTS (SELECT 1
FROM QUERY_IT.LIPS LIPS4
WHERE LIPS4.MATNR = MARA.MATNR AND LIPS4.MANDT = MARA.MANDT AND LIPS4.VRKME = ?)`,
    );
  });

  it("groupJoin 把同一关系的运算合成一个分组派生表，没用到时不连接", () => {
    const sql = from(Mara)
      .groupJoin((m) => m.deliveries)
      .where((m) => m.deliveries.where((l) => l.unit.eq("KG")).none())
      .select((m) => ({ material: m.material, largest: m.deliveries.max((l) => l.quantity) }))
      .toSql(DIALECT).sql;
    assert.equal(
      sql,
      `SELECT MARA.MATNR AS "material", MARA_G."c1" AS "largest"
FROM QUERY_IT.MARA MARA
LEFT JOIN (
SELECT LIPS.MATNR AS "k1", LIPS.MANDT AS "k2", MAX(LIPS.LFIMG) AS "c1", COUNT(CASE WHEN LIPS.VRKME = 'KG' THEN 1 END) AS "c2"
FROM QUERY_IT.LIPS LIPS
GROUP BY LIPS.MATNR, LIPS.MANDT
) MARA_G ON MARA_G."k1" = MARA.MATNR AND MARA_G."k2" = MARA.MANDT
WHERE COALESCE(MARA_G."c2", 0) = 0`,
    );
    assert.equal(
      from(Mara).groupJoin((m) => m.deliveries).select((m) => m.material).toSql(DIALECT).sql,
      from(Mara).select((m) => m.material).toSql(DIALECT).sql,
    );
  });

  it("first 取每组第一行并可继续取列", () => {
    const sql = from(Mara)
      .select((m) => ({ material: m.material, latest: m.deliveries.first((l) => l.createdOn.desc()).delivery }))
      .toSql(DIALECT).sql;
    assert.equal(
      sql,
      `SELECT MARA.MATNR AS "material", LIPS_FIRST."delivery" AS "latest"
FROM QUERY_IT.MARA MARA
LEFT JOIN (SELECT LIPS.MANDT AS "client", LIPS.ERDAT AS "createdOn", LIPS.VBELN AS "delivery", LIPS.MATNR AS "material", ` +
        `LIPS.LFIMG AS "quantity", LIPS.VGBEL AS "salesOrder", LIPS.VRKME AS "unit", ` +
        `ROW_NUMBER() OVER (PARTITION BY LIPS.MATNR, LIPS.MANDT ORDER BY LIPS.ERDAT DESC) AS "rn__"
FROM QUERY_IT.LIPS LIPS) LIPS_FIRST ON LIPS_FIRST."material" = MARA.MATNR AND LIPS_FIRST."client" = MARA.MANDT ` +
        `AND LIPS_FIRST."rn__" = 1`,
    );
  });

  it("first 需要连接两张表的等值条件", () => {
    assert.throws(() => from(LooseMara).select((m) => m.units.first((l) => l.createdOn).delivery), /first\(\) needs an equality/);
  });

  it("任意组合的连接条件也按需连接，一对多可以只按一列关联", () => {
    const sql = from(Lips)
      .where((l) => l.salesData.any())
      .select((l) => l.anyText.text)
      .toSql(DIALECT).sql;
    assert.ok(sql.includes("LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MANDT = LIPS.MANDT AND MAKT.MATNR = LIPS.MATNR"), sql);
    assert.ok(sql.includes("WHERE EXISTS (SELECT 1\nFROM QUERY_IT.VBKD VBKD\nWHERE VBKD.VBELN = LIPS.VGBEL)"), sql);
  });

  it("展开引用和对象投影都略过关系", () => {
    const spread = from(Lips).where((l) => l.mara.materialType.eq("ZMAT")).select((l) => ({ ...l, n: count() }));
    assert.deepEqual(spread.labels, ["client", "delivery", "material", "createdOn", "quantity", "unit", "salesOrder", "n"]);
    const explicit = from(Mara).select((m) => ({ material: m.material, deliveries: m.deliveries, text: m.text }));
    assert.deepEqual(explicit.labels, ["material"]);
  });
});

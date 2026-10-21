import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  all,
  any,
  call,
  caseWhen,
  count,
  Dialect,
  exists,
  filter,
  from,
  FunctionDef,
  grouping,
  ifPresent,
  onlyIf,
  scalar,
  sql,
  text,
  values,
  type Clause,
  type Cond,
  type From,
  type Ref,
} from "@query/core";
import { ClientEntity, Lips, Mara } from "../erp.js";
import { deliveries, latestDeliveries, lines, stock } from "../scenarios.js";
import { TestDatabase, TestDialect } from "../support.js";

const DIALECT = new TestDialect();
const clientFilter = filter(ClientEntity, (r) => r.client.eq("100"));

class OtherDialect extends Dialect {
  readonly name = "other";

  protected isBare(): boolean {
    return false;
  }

  foldCase(identifier: string): string {
    return identifier;
  }
}

const where = (cond: (m: Ref<Mara>) => Cond) =>
  from(Mara)
    .where(cond)
    .selectValue((m) => m.material)
    .toSql(DIALECT).sql;

describe("渲染", () => {
  it("CTE 被提升到最外层且 EXISTS 子查询不带别名", () => {
    const r = deliveries("2026-01-01", "2026-06-30", null).toSql(DIALECT);
    assert.equal(
      r.sql,
      `WITH "material_scope" AS (
SELECT DISTINCT MARA.MANDT AS "client", MARA.MATNR AS "material", CASE WHEN MARA.MFRPN <> TRIM(MARA.MFRPN) THEN TRIM(MARA.MFRPN) ELSE MARA.MFRPN END AS "partNumber"
FROM QUERY_IT.MARA MARA
WHERE MARA.MTART = ?
)
SELECT MATERIAL_SCOPE."partNumber" AS "partNumber", MAX(COALESCE(MAKT.MAKTX, MATERIAL_SCOPE."partNumber")) AS "name", SUM(LIPS.LFIMG) AS "quantity"
FROM "material_scope" MATERIAL_SCOPE
JOIN QUERY_IT.LIPS LIPS ON LIPS.MANDT = MATERIAL_SCOPE."client" AND LIPS.MATNR = MATERIAL_SCOPE."material"
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = MATERIAL_SCOPE."material" AND MAKT.SPRAS = ?
WHERE LIPS.ERDAT BETWEEN ? AND ? AND EXISTS (SELECT 1
FROM QUERY_IT.VBKD VBKD
WHERE VBKD.VBELN = LIPS.VGBEL)
GROUP BY MATERIAL_SCOPE."partNumber"
ORDER BY MATERIAL_SCOPE."partNumber" ASC`,
    );
    assert.deepEqual(r.parameters, ["ZMAT", "1", "20260101", "20260630"]);
  });

  it("全局过滤器进入 FROM 表的 WHERE 和连接表的 ON", () => {
    const r = new TestDatabase({ filters: [clientFilter] }).render(lines());
    assert.equal(
      r.sql,
      `SELECT LIPS.VBELN AS "delivery", LIPS.MATNR AS "materialNo", LIPS.ERDAT AS "createdOn", MAKT.MAKTX AS "text"
FROM QUERY_IT.LIPS LIPS
LEFT JOIN QUERY_IT.MAKT MAKT ON MAKT.MATNR = LIPS.MATNR AND MAKT.SPRAS = ? AND MAKT.MANDT = ?
WHERE LIPS.MANDT = ?
ORDER BY LIPS.VBELN ASC`,
    );
    assert.deepEqual(r.parameters, ["1", "100", "100"]);
  });

  it("全局过滤器也作用于 CTE 和 EXISTS 子查询里的表", () => {
    const sqlText = new TestDatabase({ filters: [clientFilter] }).render(deliveries("2026-01-01", "2026-06-30", "MK1")).sql;
    assert.ok(sqlText.includes("WHERE MARA.MANDT = ? AND MARA.MTART = ? AND MARA.MFRNR = ?"), sqlText);
    assert.ok(sqlText.includes("WHERE VBKD.MANDT = ? AND VBKD.VBELN = LIPS.VGBEL"), sqlText);
  });

  it("关闭过滤器后不再追加条件", () => {
    const r = new TestDatabase({ filters: [clientFilter] }).render(
      from(Lips)
        .unfiltered()
        .selectValue(() => count()),
    );
    assert.equal(r.sql, 'SELECT COUNT(*) AS "value"\nFROM QUERY_IT.LIPS LIPS');
  });

  it("泛型模板生成的 UNION 只在第一段写别名", () => {
    assert.equal(
      stock("2026").toSql(DIALECT).sql,
      `SELECT MARDH.MATNR AS "material", MARDH.LFGJA AS "year", MARDH.LFMON AS "period", COALESCE(MARDH.LABST, 0) AS "quantity"
FROM QUERY_IT.MARDH MARDH
WHERE MARDH.LFGJA = ?
UNION ALL
SELECT MCHBH.MATNR, MCHBH.LFGJA, MCHBH.LFMON, COALESCE(MCHBH.CLABS, 0)
FROM QUERY_IT.MCHBH MCHBH
WHERE MCHBH.LFGJA = ?`,
    );
  });

  it("UNION 按键名对齐各段的列", () => {
    const a = from(Mara).select((m) => ({ x: m.material, y: m.partNumber }));
    const b = from(Mara).select((m) => ({ y: m.manufacturer, x: m.materialType }));
    assert.ok(a.unionAll(b).toSql(DIALECT).sql.includes("SELECT MARA2.MTART, MARA2.MFRNR"));
  });

  it("自连接和关联子查询的别名在整条语句内唯一", () => {
    const r = from(Mara)
      .join(Mara, (a, b) => b.partNumber.eq(a.partNumber))
      .where((a, b) => exists(from(Mara).where((c) => c.material.eq(b.material)).selectOne()))
      .selectValue((a) => a.material)
      .toSql(DIALECT);
    assert.equal(
      r.sql,
      `SELECT MARA.MATNR AS "material"
FROM QUERY_IT.MARA MARA
JOIN QUERY_IT.MARA MARA2 ON MARA2.MFRPN = MARA.MFRPN
WHERE EXISTS (SELECT 1
FROM QUERY_IT.MARA MARA3
WHERE MARA3.MATNR = MARA2.MATNR)`,
    );
  });

  it("窗口函数和取排名第一的行", () => {
    const s = latestDeliveries().toSql(DIALECT).sql;
    assert.ok(s.includes('ROW_NUMBER() OVER (PARTITION BY LIPS.MATNR ORDER BY LIPS.ERDAT DESC, LIPS.VBELN DESC) AS "rank"'), s);
    assert.ok(s.endsWith('WHERE RANKED_DELIVERY."rank" = ?\nORDER BY RANKED_DELIVERY."material" ASC'), s);
  });

  it("IN 列表超过方言上限时拆成多个 IN", () => {
    const small = new TestDialect(3);
    const inSql = from(Mara)
      .where((m) => m.material.in(["1", "2", "3", "4", "5", "6", "7"]))
      .selectValue((m) => m.material)
      .toSql(small).sql;
    assert.ok(inSql.endsWith("WHERE (MARA.MATNR IN (?, ?, ?) OR MARA.MATNR IN (?, ?, ?) OR MARA.MATNR IN (?))"), inSql);
    const notInSql = from(Mara)
      .where((m) => m.material.notIn(["1", "2", "3", "4"]))
      .selectValue((m) => m.material)
      .toSql(small).sql;
    assert.ok(notInSql.endsWith("WHERE (MARA.MATNR NOT IN (?, ?, ?) AND MARA.MATNR NOT IN (?))"), notInSql);
  });

  it("空 IN 恒假而空 NOT IN 恒真", () => {
    assert.ok(where((m) => m.material.in([])).endsWith("WHERE 1 = 0"));
    assert.ok(where((m) => m.material.notIn([])).endsWith("WHERE 1 = 1"));
  });

  it("可选条件为空时整体省略", () => {
    const s = where((m) =>
      all(
        m.material.eqIfPresent(null),
        m.material.inIfPresent([]),
        onlyIf(false, () => m.material.eq("x")),
        ifPresent(undefined, () => m.material.eq("x")),
      ),
    );
    assert.equal(s, 'SELECT MARA.MATNR AS "material"\nFROM QUERY_IT.MARA MARA');
  });

  it("OR 的每个分支都加括号", () => {
    const s = where((m) => any(m.material.eq("A"), m.partNumber.eq("B").and(m.manufacturer.eq("C"))));
    assert.ok(s.endsWith("WHERE ((MARA.MATNR = ?) OR (MARA.MFRPN = ? AND MARA.MFRNR = ?))"), s);
  });

  it("分页的 LIMIT 和 OFFSET", () => {
    const s = from(Mara)
      .orderBy((m) => m.material.asc().nullsLast())
      .selectValue((m) => m.material)
      .limit(10)
      .offset(20)
      .toSql(DIALECT).sql;
    assert.ok(s.endsWith("ORDER BY MARA.MATNR ASC NULLS LAST\nLIMIT 10 OFFSET 20"), s);
  });

  it("ROLLUP 一次列出全部分组键，不能与普通分组混用", () => {
    const q = from(Mara)
      .groupByRollup((m) => [m.material, m.manufacturer])
      .select((m) => ({ material: m.material, level: grouping(m.material) }));
    assert.ok(q.toSql(DIALECT).sql.endsWith("GROUP BY ROLLUP(MARA.MATNR, MARA.MFRNR)"));
    assert.throws(() => from(Mara).groupByRollup((m) => m.material).groupBy((m) => m.manufacturer), /already lists every grouping key/);
    assert.throws(() => from(Mara).groupBy((m) => m.material).groupByRollup((m) => m.manufacturer), /cannot follow groupBy/);
    assert.throws(() => from(Mara).groupByRollup(() => []), /at least one key/);
  });

  it("方言专属子句在别的方言上拒绝渲染", () => {
    const other: Clause = { position: "end", dialect: OtherDialect, render: (w) => void w.sql("OTHER") };
    const q = from(Mara)
      .with(other)
      .selectValue((m) => m.material);
    assert.throws(() => q.toSql(DIALECT), /OtherDialect/);
  });

  it("方言专属函数在别的方言上拒绝渲染", () => {
    const onlyOther = FunctionDef.only(OtherDialect, "OTHER_FN");
    const q = from(Mara).selectValue((m) => call(onlyOther, text, m.material));
    assert.throws(() => q.toSql(DIALECT), /OTHER_FN is a OtherDialect function/);
  });

  it("数据库实例可以覆盖函数渲染", () => {
    const onlyOther = FunctionDef.only(OtherDialect, "OTHER_FN");
    const db = new TestDatabase({ functions: new Map([[onlyOther, (w, args) => void w.sql("MINE(").join(args, ", ").sql(")")]]) });
    const s = db.render(from(Mara).selectValue((m) => call(onlyOther, text, m.material))).sql;
    assert.ok(s.startsWith("SELECT MINE(MARA.MATNR)"), s);
  });

  it("同一个查询在同一个数据库实例上只渲染一次", () => {
    const db = new TestDatabase();
    const q = from(Mara).selectValue((m) => m.material);
    assert.equal(db.render(q), db.render(q));
    assert.notEqual(db.render(q), new TestDatabase().render(q));
  });

  it("SELECT 列表里的标量子查询仍然绑定 WHERE 参数", () => {
    const r = from(Mara)
      .select((m) => ({
        n: scalar(
          from(Lips)
            .where((l) => l.material.eq("M1"))
            .selectValue(() => count()),
        ),
        material: m.material.coalesce("none"),
      }))
      .toSql(DIALECT);
    assert.equal(
      r.sql,
      `SELECT (SELECT COUNT(*)
FROM QUERY_IT.LIPS LIPS
WHERE LIPS.MATNR = ?) AS "n", COALESCE(MARA.MATNR, 'none') AS "material"
FROM QUERY_IT.MARA MARA`,
    );
    assert.deepEqual(r.parameters, ["M1"]);
  });

  it("DISTINCT 查询的排序表达式里的常量也写成字面量", () => {
    const r = from(Mara)
      .distinct()
      .orderBy((m) => m.material.coalesce("~"))
      .selectValue((m) => m.material.coalesce("~"))
      .toSql(DIALECT);
    assert.ok(r.sql.endsWith("ORDER BY COALESCE(MARA.MATNR, '~') ASC"), r.sql);
    assert.deepEqual(r.parameters, []);
  });

  it("常量行每行一个字面量 SELECT 并用 UNION ALL 连接", () => {
    const sqlText = values([
      { code: "A", sort: 1 },
      { code: "B", sort: 2 },
    ]).toSql(DIALECT).sql;
    assert.equal(sqlText, `SELECT 'A' AS "code", 1 AS "sort"\nUNION ALL\nSELECT 'B', 2`);
    assert.throws(() => values([]), /at least one row/);
    assert.throws(() => values([{ a: "x" }, { a: "y", b: "z" }] as { a: string }[]), /keys the first row lacks: b/);
    assert.throws(() => values([{ a: null as unknown as string }]), /no value for 'a'/);
  });

  it("CASE 与类型化原生片段，SELECT 列表里的常量写成字面量", () => {
    const s = from(Mara)
      .selectValue((m) =>
        caseWhen(m.materialType.eq("ZMAT"), "material")
          .when(m.materialType.eq("ZOTH"), "other")
          .otherwise(sql(text)`LOWER(${m.materialType})`),
      )
      .toSql(DIALECT).sql;
    assert.ok(s.startsWith("SELECT CASE WHEN MARA.MTART = 'ZMAT' THEN 'material' WHEN MARA.MTART = 'ZOTH' THEN 'other' ELSE LOWER(MARA.MTART) END"), s);
  });

  it("可复用的片段是作用域到作用域的泛型函数", () => {
    const ofClient =
      (client: string) =>
      <T extends readonly [Ref<Lips>, ...unknown[]]>(scope: From<T>): From<T> =>
        scope.where((...[l]) => l.client.eq(client));
    const r = from(Lips)
      .join(Mara, (l, ma) => ma.material.eq(l.material))
      .pipe(ofClient("100"))
      .selectValue((l, ma) => ma.partNumber)
      .toSql(DIALECT);
    assert.ok(r.sql.endsWith("WHERE LIPS.MANDT = ?"), r.sql);
    assert.deepEqual(r.parameters, ["100"]);
  });

  it("SQL 片段里的普通值成为参数", () => {
    const r = from(Mara)
      .where((m) => sql(text)`SUBSTRING(${m.material}, ${1}, ${2})`.eq("M1"))
      .selectOne()
      .toSql(DIALECT);
    assert.ok(r.sql.endsWith("WHERE SUBSTRING(MARA.MATNR, ?, ?) = ?"), r.sql);
    assert.deepEqual(r.parameters, [1, 2, "M1"]);
  });
});

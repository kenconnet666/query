import assert from "node:assert/strict";
import { it } from "node:test";
import { cte, decimal, from, identifier, sql, sqlCond, sqlTable, text, val, values } from "@zerodep-dev/query-core";
import { Lips, Mara } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/** 会把字面量提前结束、开启注释或者像参数的文本。 */
const HOSTILE = ["O'Brien", "back\\slash", "\\'; DROP TABLE x; --", "ends with \\", "-- not a comment", "/* nor this */", "? :name $1 $$", "中文 Ünïcødé"];

/**
 * 原生 SQL、字面量与参数：整条的原生 SQL 带命名参数、查询里嵌入的原生片段，以及会把字面量提前结束的文本
 * 写成字面量和绑成参数时都原样往返。数据见 support/sample-data.ts。与 Java 的 NativeSqlTest 对应。
 */
liveSuite("原生 SQL、字面量与参数", (live) => {
  it("整条原生 SQL 的命名参数和数组展开", async () => {
    // 手写 SQL 里的表名、列名按本库的方言加引号；:ids 是数组，展开成两个参数。
    const q = (identifier: string) => live.quote(identifier);
    const rows = await live.db
      .sql(`SELECT ${q("VBELN")} AS delivery FROM ${live.table("LIPS")} WHERE ${q("MANDT")} = :client AND ${q("VBELN")} IN (:ids) ORDER BY ${q("VBELN")}`)
      .bind("client", "100")
      .bind("ids", ["D1", "D3"])
      .values<string>();
    assert.deepEqual(rows, ["D1", "D3"]);
  });

  it("查询里嵌入原生的数据源条件和表达式", async (t) => {
    if (live.is("sqlite", "sqlite-wasm")) return t.skip("the hand-written fragment calls LPAD, which SQLite lacks");
    // sqlTable 里的插值是标识符（按方言加引号）或数组参数；sql、sqlCond 混用表达式和值。原生 SQL 不经过全局过滤器，所以合计含客户端 200 的 D6。
    const id = identifier;
    const totals = sqlTable({ material: text, total: decimal })`SELECT ${id("MATNR")} AS ${id("material")}, SUM(${id("LFIMG")}) AS ${id("total")} FROM ${id("QUERY_IT")}.${id("LIPS")} WHERE ${id("VRKME")} IN (${["EA", "KG"]}) GROUP BY ${id("MATNR")}`;
    const rows = await live.db.list(
      from(totals)
        .join(Mara, (t, m) => m.material.eq(t.material))
        .where((_t, m) => sqlCond`${m.materialType} <> ${"ZOTH"}`)
        .orderBy((t) => t.material)
        .select((t, m) => ({ material: t.material, padded: sql(text)`LPAD(${m.material}, ${10}, '0')`, total: t.total })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.padded}:${Number(r.total)}`), ["M1:00000000M1:106", "M2:00000000M2:12"]);
  });

  it("带引号、反斜杠和注释符的文本作为字面量和参数都原样往返", async () => {
    const texts = cte("texts", values(HOSTILE.map((value, ord) => ({ value, ord }))));
    assert.deepEqual(await live.db.list(from(texts).orderBy((t) => t.ord).select((t) => t.value)), HOSTILE, "values(...) writes every row as literals");
    for (const text of HOSTILE) {
      assert.equal(await live.db.one(from(Lips).limit(1).select(() => val(text))), text, "a SELECT-list constant is written as a literal");
      assert.deepEqual(await live.db.list(from(texts).where((t) => t.value.eq(text)).select((t) => t.value)), [text], "a WHERE value is bound as a parameter");
    }
  });
});

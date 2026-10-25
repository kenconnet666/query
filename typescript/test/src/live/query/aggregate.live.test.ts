import assert from "node:assert/strict";
import { it } from "node:test";
import { avgDistinct, count, from, grouping, stddevPop, stddevSamp, stringAgg, sum, sumDistinct, varPop, varSamp } from "@zerodep-dev/query-core";
import { Lips } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * 分组与聚合：ROLLUP 的小计与总计、带条件的聚合、去重聚合与统计、按用户的选择去掉分组维度。
 * 数据见 support/sample-data.ts：客户端 100 的交货里 M1 有 D1、D2、D5（3、2、1），M2 有 D3、D4（5、7），单位都是 EA。
 * 与 Java 的 AggregateTest 对应。
 */
liveSuite("分组与聚合", (live) => {
  it("ROLLUP 给出小计与总计", async () => {
    const rollup = from(Lips)
      .groupByRollup((l) => [l.material, l.unit])
      .orderBy((l) => [grouping(l.material), l.material, grouping(l.unit)])
      .select((l) => ({ material: l.material, unit: l.unit, total: sum(l.quantity), level: grouping(l.material) }));
    if (live.is("sqlite", "sqlite-wasm")) {
      await assert.rejects(live.db.list(rollup), /ROLLUP/, "SQLite 没有 ROLLUP，渲染时报错");
      return;
    }
    const rows = await live.db.list(rollup);
    assert.deepEqual(rows.map((r) => `${r.material}/${r.unit}/${Number(r.total)}/${r.level}`), ["M1/EA/6/0", "M1/null/6/0", "M2/EA/12/0", "M2/null/12/0", "null/null/18/1"]);
  });

  it("条件聚合只聚合满足条件的行且文本按指定顺序拼接", async () => {
    // recent：2026 年以后的数量合计（M2 的 D4 在 2025 年）；big：数量大于 2 的行数；交货单倒序拼接。
    const rows = await live.db.list(
      from(Lips)
        .groupBy((l) => l.material)
        .orderBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          recent: sum(l.quantity).filter(l.createdOn.ge("2026-01-01")),
          big: count().filter(l.quantity.gt("2")),
          deliveries: stringAgg(l.delivery, ",", l.delivery.desc()),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${Number(r.recent)}:${r.big}:${r.deliveries}`), ["M1:6:1:D5,D2,D1", "M2:5:2:D4,D3"]);
  });

  it("去重聚合与标准差方差", async () => {
    const rows = await live.db.list(
      from(Lips)
        .groupBy((l) => l.material)
        .orderBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          distinctTotal: sumDistinct(l.quantity),
          distinctAverage: avgDistinct(l.quantity),
          sampleDeviation: stddevSamp(l.quantity),
          populationDeviation: stddevPop(l.quantity),
          sampleVariance: varSamp(l.quantity),
          populationVariance: varPop(l.quantity),
        })),
    );
    const f = (v: number | null) => v!.toFixed(4);
    assert.deepEqual(
      rows.map((r) => `${r.material}:${Number(r.distinctTotal)}:${Number(r.distinctAverage)}:${f(r.sampleDeviation)}:${f(r.populationDeviation)}:${f(r.sampleVariance)}:${f(r.populationVariance)}`),
      ["M1:6:2:1.0000:0.8165:1.0000:0.6667", "M2:12:6:1.4142:1.0000:2.0000:1.0000"],
    );
  });

  it("去掉分组维度后按用户的排序串汇总", async () => {
    // 报表允许用户隐藏列：exclude 去掉单位和零件号，它们的分组键、排序和导航带来的连接都随之去掉；sortBy 接用户的排序串。
    const rows = await live.db.list(
      from(Lips)
        .groupBy((l) => [l.material, l.unit, l.mara.partNumber])
        .orderBy((l) => [l.material.asc(), l.unit.asc()])
        .select((l) => ({ material: l.material, unit: l.unit, partNumber: l.mara.partNumber, total: sum(l.quantity) }))
        .exclude("unit", "partNumber")
        .sortBy("total desc, material"),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.unit}:${r.partNumber}:${Number(r.total)}`), ["M2:null:null:12", "M1:null:null:6"]);
  });
});

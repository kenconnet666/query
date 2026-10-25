import assert from "node:assert/strict";
import { it } from "node:test";
import { Bound, cte, cumeDist, firstValue, from, lag, lastValue, nthValue, ntile, percentRank, rowNumber } from "@zerodep-dev/query-core";
import { Lips } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * 窗口函数：每组取最新的行（CTE 加 ROW_NUMBER，或 qualify）、首行末行上一行与分组号、相对排名与累计分布。
 * 数据见 support/sample-data.ts：M1 的交货 D1、D2、D5（3、2、1，D5 最新），M2 的 D3、D4（5、7，D3 最新）。与 Java 的 WindowTest 对应。
 */
liveSuite("窗口函数", (live) => {
  it("在 CTE 里排名后取每个物料最新的交货", async () => {
    const ranked = cte(
      "ranked_delivery",
      from(Lips).select((l) => ({
        material: l.material,
        delivery: l.delivery,
        rank: rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc(), l.delivery.desc())),
      })),
    );
    assert.deepEqual(await live.db.list(from(ranked).where((x) => x.rank.eq(1)).orderBy((x) => x.material.asc()).select((x) => x.delivery)), ["D5", "D3"]);
  });

  it("窗口条件取每组最新的两行", async () => {
    // qualify 把窗口条件包成派生表；排序用的创建日期不在结果里，作为额外的列带出来。
    const rows = await live.db.list(
      from(Lips)
        .qualify((l) => rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc())).le(2))
        .orderBy((l) => [l.material, l.createdOn.desc()])
        .select((l) => ({ material: l.material, delivery: l.delivery })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.delivery}`), ["M1:D5", "M1:D2", "M2:D3", "M2:D4"]);
  });

  it("窗口里的首行末行上一行与分组号", async () => {
    // 末行要整个分区作为窗口帧；上一行没有时取默认值 0；ntile(2) 把五行按交货单分成两组（3 + 2）。
    const rows = await live.db.list(
      from(Lips)
        .orderBy((l) => l.delivery)
        .select((l) => ({
          delivery: l.delivery,
          first: firstValue(l.quantity).over((w) => w.partitionBy(l.material).orderBy(l.delivery)),
          last: lastValue(l.quantity).over((w) => w.partitionBy(l.material).orderBy(l.delivery).rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())),
          previous: lag(l.quantity, 1, "0").over((w) => w.partitionBy(l.material).orderBy(l.delivery)),
          half: ntile(2).over((w) => w.orderBy(l.delivery)),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.delivery}:${Number(r.first)}:${Number(r.last)}:${Number(r.previous)}:${r.half}`), ["D1:3:1:0:1", "D2:3:1:3:1", "D3:5:7:0:1", "D4:5:7:5:2", "D5:3:1:2:2"]);
  });

  it("相对排名累计分布与第 n 行", async () => {
    const rows = await live.db.list(
      from(Lips)
        .orderBy((l) => l.delivery)
        .select((l) => ({
          delivery: l.delivery,
          percentRank: percentRank().over((w) => w.orderBy(l.quantity)),
          cumeDist: cumeDist().over((w) => w.partitionBy(l.material).orderBy(l.quantity)),
          second: nthValue(l.delivery, 2).over((w) => w.partitionBy(l.material).orderBy(l.delivery).rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.delivery}:${r.percentRank.toFixed(2)}:${r.cumeDist.toFixed(2)}:${r.second}`), ["D1:0.50:1.00:D2", "D2:0.25:0.67:D2", "D3:0.75:0.50:D4", "D4:1.00:1.00:D4", "D5:0.00:0.33:D2"]);
  });
});

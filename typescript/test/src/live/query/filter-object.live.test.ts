import assert from "node:assert/strict";
import { it } from "node:test";
import { from, where } from "@zerodep-dev/query-core";
import { Lips, Mara } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/** 交货查询的请求参数：每个非空字段是一个条件，关键字同时在零件号和描述里找。 */
class DeliverySearch {
  material?: string[];
  @where("ge", "createdOn") from?: string;
  @where("lt", "createdOn") to?: string;
  @where("contains", "mara.partNumber", "text.text") keyword?: string;
  @where("ne") unit?: string;
}

/** 物料查询的请求参数：两个字段走同一个一对多关系。 */
class MaterialSearch {
  @where("eq", "deliveries.unit") unit?: string;
  @where("ge", "deliveries.quantity") minQuantity?: string;
  materialType?: string;
}

/**
 * 条件对象：请求参数对象的每个非空字段是一个条件，字段名或 `@where` 指向列或关系上的列。数据见 support/sample-data.ts。
 * 与 Java 的 FilterObjectTest 对应。
 */
liveSuite("条件对象", (live) => {
  const deliveries = (values: Partial<DeliverySearch>) => live.db.list(from(Lips).where(Object.assign(new DeliverySearch(), values)).orderBy((l) => l.delivery).select((l) => l.delivery));
  const materials = (values: Partial<MaterialSearch>) => live.db.list(from(Mara).where(Object.assign(new MaterialSearch(), values)).orderBy((m) => m.material).select((m) => m.material));

  it("非空字段才产生条件且通配符按普通字符匹配", async () => {
    // 关键字 P 出现在 M1、M2 的零件号里；D4 在 2025 年被 from 挡掉。
    assert.deepEqual(await deliveries({ from: "2026-01-01", keyword: "P" }), ["D1", "D2", "D3", "D5"]);
    assert.deepEqual(await deliveries({ material: ["M1"], from: "2026-03-01" }), ["D5"]);
    for (const wildcard of ["%", "_", "P%", "!"]) assert.deepEqual(await deliveries({ keyword: wildcard }), [], `LIKE 的通配符和转义符照字面找：${wildcard}`);
  });

  it("经过一对多关系的几个字段由同一个子行满足", async () => {
    // 两个字段合并进同一个 EXISTS：要有一张交货行既是 EA、数量又不少于 5，只有 M2（D3、D4）。
    assert.deepEqual(await materials({ unit: "EA", minQuantity: "5" }), ["M2"]);
    assert.deepEqual(await materials({ unit: "EA" }), ["M1", "M2"]);
    assert.deepEqual(await materials({ materialType: " " }), ["M1", "M2", "M3", "M4"], "只有空白的文本当作没填");
  });
});

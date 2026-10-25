import { Decimal } from "decimal.js";
import assert from "node:assert/strict";
import { it } from "node:test";
import { count, from, percentileCont, percentileDisc, type Ref, sqlite } from "@zerodep-dev/query-sqlite";
import { Lips } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * SQLite 专属的写法（`@zerodep-dev/query-sqlite`）和它的类型行为：区分大小写的 GLOB、中位数与百分位数，小数按数值比较。
 * node:sqlite 和 SQLite WASM 两个驱动都跑。数据见 support/sample-data.ts。与 Java 的 SqliteTest 对应。
 */
liveSuite("SQLite 专属写法", { only: ["sqlite", "sqlite-wasm"] }, (live) => {
  it("GLOB 区分大小写而 LIKE 不区分", async () => {
    const n = async (where: (l: Ref<Lips>) => void) => Number(await live.db.one(from(Lips).unfiltered().where(where).select(() => count())));
    assert.deepEqual([await n((l) => l.unit.like("ea")), await n((l) => sqlite(l.unit).glob("ea")), await n((l) => sqlite(l.unit).glob("E?"))], [6, 0, 6], "LIKE ignores ASCII case on SQLite, GLOB does not");
  });

  it("中位数与百分位数", async () => {
    // M1 的数量是 3、2、1。
    const r = await live.db.one(from(Lips).where((l) => l.material.eq("M1")).select((l) => ({ middle: sqlite(l.quantity).median(), p50: percentileCont(0.5, l.quantity), top: percentileDisc(1, l.quantity) })));
    assert.deepEqual([r.middle, r.p50, Number(r.top)], [2, 2, 3]);
  });

  it("小数按数值比较", async () => {
    assert.deepEqual(await live.db.list(from(Lips).unfiltered().where((l) => l.quantity.gt(new Decimal("9.5"))).select((l) => l.delivery)), ["D6"], "NUMERIC affinity compares as numbers, not text");
  });
});

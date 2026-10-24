import assert from "node:assert/strict";
import { mkdirSync, rmSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { it } from "node:test";
import { fileURLToPath } from "node:url";
import { count, from, glob, like, median, nodeSqliteDriver, percentileCont, percentileDisc, type Cond, type Ref } from "@zerodep-dev/query-sqlite";
import { Lips } from "../fixtures/erp.js";
import { liveScenarios, sqliteTarget } from "./live.js";

/** node:sqlite 上的共享场景：库是 test/dist 下的两个文件，把 QUERY_IT 库 ATTACH 进来；不需要连接变量，随单元测试一起跑。 */
const dir = fileURLToPath(new URL("../sqlite-it/", import.meta.url));

liveScenarios(
  {
    ...sqliteTarget,
    name: "SQLITE",
    async connect() {
      rmSync(dir, { recursive: true, force: true });
      mkdirSync(dir, { recursive: true });
      const db = new DatabaseSync(dir + "main.db");
      db.exec(`ATTACH DATABASE '${(dir + "query_it.db").replaceAll("'", "''")}' AS QUERY_IT; PRAGMA foreign_keys = ON`);
      return { driver: nodeSqliteDriver(db), close: async () => db.close() };
    },
  },
  (live) => {
    it("GLOB 区分大小写而 LIKE 不区分", async () => {
      const { db } = live();
      const n = async (where: (l: Ref<Lips>) => Cond) => Number(await db.one(from(Lips).unfiltered().where(where).select(() => count())));
      assert.deepEqual([await n((l) => like(l.unit, "ea")), await n((l) => glob(l.unit, "ea")), await n((l) => glob(l.unit, "E?"))], [6, 0, 6]);
    });

    it("中位数与百分位数", async () => {
      const r = await live().db.one(from(Lips).where((l) => l.material.eq("M1")).select((l) => ({ middle: median(l.quantity), p50: percentileCont(0.5, l.quantity), top: percentileDisc(1, l.quantity) })));
      assert.deepEqual([r.middle, r.p50, Number(r.top)], [2, 2, 3]);
    });

    it("小数按数值比较", async () => {
      assert.deepEqual(await live().db.list(from(Lips).unfiltered().where((l) => l.quantity.gt("9.5")).select((l) => l.delivery)), ["D6"]);
    });
  },
);

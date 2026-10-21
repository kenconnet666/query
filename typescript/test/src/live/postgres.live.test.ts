import assert from "node:assert/strict";
import { it } from "node:test";
import pg from "pg";
import { count, cube, distinctOn, filterWhere, from, grouping, groupingSets, ilike, pg as pgOps, pgDriver, PostgresDatabase, stringAgg, sum } from "@query/postgres";
import { Lips } from "../erp.js";
import { liveScenarios } from "./live.js";

liveScenarios(
  {
    name: "PG",
    async connect(config) {
      const pool = new pg.Pool({ host: config.host, port: config.port, database: config.database, user: config.user, password: config.password, max: 4 });
      return { driver: pgDriver(pool), close: () => pool.end() };
    },
    database: (driver, options) => new PostgresDatabase(driver, options),
    text: (length) => `VARCHAR(${length})`,
  },
  (live) => {
    it("DISTINCT ON 取每个物料最新的交货", async () => {
      const rows = await live().db.list(
        from(Lips)
          .with((l) => distinctOn(l.material))
          .orderBy((l) => [l.material.asc(), l.createdOn.desc()])
          .selectValue((l) => l.delivery),
      );
      assert.deepEqual(rows, ["D5", "D3"]);
    });

    it("聚合 FILTER 与 ILIKE 以及 IS DISTINCT FROM", async () => {
      const { db } = live();
      assert.equal(Number(await db.one(from(Lips).selectValue((l) => filterWhere(sum(l.quantity), l.material.eq("M1"))))), 6);
      assert.equal(
        await db.one(
          from(Lips)
            .where((l) => ilike(l.unit, "ea"))
            .selectValue(() => count()),
        ),
        5,
      );
      assert.equal(
        await db.one(
          from(Lips)
            .where((l) => pgOps(l.material).isDistinctFrom("M1"))
            .selectValue(() => count()),
        ),
        2,
      );
    });

    it("带排序的 STRING_AGG", async () => {
      const rows = await live().db.list(
        from(Lips)
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({ material: l.material, deliveries: stringAgg(l.delivery, ",", l.delivery.asc()) }))
          .map((r) => `${r.material}=${r.deliveries}`),
      );
      assert.deepEqual(rows, ["M1=D1,D2,D5", "M2=D3,D4"]);
    });

    it("分组集合与 CUBE", async () => {
      const { db } = live();
      const sets = await db.list(
        from(Lips)
          .groupBy((l) => groupingSets([l.material], []))
          .orderBy((l) => [grouping(l.material), l.material])
          .select((l) => ({ material: l.material, total: sum(l.quantity) })),
      );
      assert.deepEqual(
        sets.map((r) => `${r.material}=${Number(r.total)}`),
        ["M1=6", "M2=12", "null=18"],
      );
      const cells = await db.list(
        from(Lips)
          .groupBy((l) => cube(l.material, l.unit))
          .selectValue(() => count()),
      );
      assert.equal(cells.length, 6, "material x unit, material, unit and the grand total");
    });
  },
);

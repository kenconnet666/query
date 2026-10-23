import assert from "node:assert/strict";
import { it } from "node:test";
import pg from "pg";
import { count, cube, decimal, distinctOn, filterWhere, from, greatest, grouping, groupingSets, ilike, least, lit, nullOf, pg as pgOps, pgDriver, PostgresDatabase, stringAgg, sum } from "@zerodep-dev/query-postgres";
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
    slowStatement: "SELECT pg_sleep(3)",
  },
  (live) => {
    it("关闭 standard_conforming_strings 的会话里含反斜杠的字面量仍原样往返", async () => {
      const { config } = live();
      const client = new pg.Client({ host: config.host, port: config.port, database: config.database, user: config.user, password: config.password, options: "-c standard_conforming_strings=off" });
      await client.connect();
      try {
        const legacy = new PostgresDatabase(pgDriver(client));
        for (const text of ["back\\slash", "\\'; SELECT 1; --", "ends with \\"]) {
          assert.equal(await legacy.one(from(Lips).limit(1).select(() => lit(text))), text);
        }
      } finally {
        await client.end();
      }
    });

    it("DISTINCT ON 取每个物料最新的交货", async () => {
      const rows = await live().db.list(
        from(Lips)
          .with((l) => distinctOn(l.material))
          .orderBy((l) => [l.material.asc(), l.createdOn.desc()])
          .select((l) => l.delivery),
      );
      assert.deepEqual(rows, ["D5", "D3"]);
    });

    it("聚合 FILTER 与 ILIKE 以及 IS DISTINCT FROM", async () => {
      const { db } = live();
      assert.equal(Number(await db.one(from(Lips).select((l) => filterWhere(sum(l.quantity), l.material.eq("M1"))))), 6);
      assert.equal(
        await db.one(
          from(Lips)
            .where((l) => ilike(l.unit, "ea"))
            .select(() => count()),
        ),
        5,
      );
      assert.equal(
        await db.one(
          from(Lips)
            .where((l) => pgOps(l.material).isDistinctFrom("M1"))
            .select(() => count()),
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
          .select(() => count()),
      );
      assert.equal(cells.length, 6, "material x unit, material, unit and the grand total");
    });

    it("原生 SQL 不在美元引号里找参数", async () => {
      const rows = await live().db.sql("SELECT $$:not_a_parameter$$ || $tag$ it's :also $tag$ || :x AS v").bind("x", "!").values();
      assert.deepEqual(rows, [":not_a_parameter it's :also !"]);
    });

    it("GREATEST 与 LEAST 跳过 NULL", async () => {
      const { db } = live();
      const rows = await db.list(
        from(Lips)
          .orderBy((l) => l.delivery)
          .select((l) => ({ delivery: l.delivery, high: greatest(l.quantity, "4"), low: least(l.quantity, "4") })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.delivery}:${Number(r.high)}:${Number(r.low)}`),
        ["D1:4:3", "D2:4:2", "D3:5:4", "D4:7:4", "D5:4:1"],
      );
      const withNull = await db.one(
        from(Lips)
          .where((l) => l.delivery.eq("D1"))
          .select((l) => greatest(l.quantity, nullOf(decimal))),
      );
      assert.equal(Number(withNull), 3);
    });
  },
);

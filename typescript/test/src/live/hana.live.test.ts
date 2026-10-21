import assert from "node:assert/strict";
import { it } from "node:test";
import hana from "@sap/hana-client";
import { addDays, addMonths, date, daysBetween, from, hanaDriver, HanaDatabase, hint, lit, stringAgg, toVarchar } from "@query/hana";
import { Lips } from "../erp.js";
import { liveScenarios } from "./live.js";

liveScenarios(
  {
    name: "HANA",
    async connect(config) {
      const pool = hana.createPool({ serverNode: `${config.host}:${config.port}`, uid: config.user, pwd: config.password }, { maxConnectedOrPooled: 4 });
      return { driver: hanaDriver(pool), close: async () => pool.clear() };
    },
    database: (driver, options) => new HanaDatabase(driver, options),
    text: (length) => `NVARCHAR(${length})`,
  },
  (live) => {
    it("HANA 日期函数在实库上可用", async () => {
      const { db } = live();
      const day = lit("2026-01-01", date);
      const one = from(Lips).limit(1);
      assert.equal(await db.one(one.selectValue(() => daysBetween(day, addMonths(day, 1)))), 31);
      assert.equal(await db.one(one.selectValue(() => toVarchar(addDays(day, 4), "YYYYMMDD"))), "20260105");
    });

    it("带排序的 STRING_AGG 与提示子句", async () => {
      const rows = await live().db.list(
        from(Lips)
          .with(hint("NO_CS_JOIN"))
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({ material: l.material, deliveries: stringAgg(l.delivery, ",", l.delivery.asc()) }))
          .map((r) => `${r.material}=${r.deliveries}`),
      );
      assert.deepEqual(rows, ["M1=D1,D2,D5", "M2=D3,D4"]);
    });
  },
);

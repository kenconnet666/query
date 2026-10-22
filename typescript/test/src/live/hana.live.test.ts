import assert from "node:assert/strict";
import { it } from "node:test";
import hana from "@sap/hana-client";
import { addDays, addMonths, count, cube, date, daysBetween, decimal, from, greatest, grouping, groupingSets, hanaDriver, HanaDatabase, hint, least, lit, nullOf, stringAgg, substring, sum, toInteger, toVarchar } from "@zerodep-dev/query-hana";
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
    // HANA SQL has no sleep function; SQLScript's built-in SQLSCRIPT_SYNC library does.
    setup: [
      "-DROP PROCEDURE QUERY_IT.SLOW_ROW",
      "CREATE PROCEDURE QUERY_IT.SLOW_ROW(IN SECONDS INTEGER) READS SQL DATA AS BEGIN USING SQLSCRIPT_SYNC AS SYNCLIB; " +
        "CALL SYNCLIB:SLEEP_SECONDS(:SECONDS); SELECT 1 AS X FROM DUMMY; END",
    ],
    slowStatement: "CALL QUERY_IT.SLOW_ROW(3)",
  },
  (live) => {
    it("超时按整秒交给客户端，短于超时的语句照常完成", async () => {
      const start = Date.now();
      await live().db.sql("CALL QUERY_IT.SLOW_ROW(1)").timeout(2500).list();
      assert.ok(Date.now() - start >= 1000, "the procedure slept its second");
    });

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

    it("GREATEST 与 LEAST 遇到 NULL 时返回 NULL", async () => {
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
          .selectValue((l) => greatest(l.quantity, nullOf(decimal))),
      );
      assert.equal(withNull, null);
    });

    it("TO_INTEGER 把文本转成整数", async () => {
      const numbers = await live().db.list(
        from(Lips)
          .orderBy((l) => l.delivery)
          .selectValue((l) => toInteger(substring(l.delivery, 2))),
      );
      assert.deepEqual(numbers, [1, 2, 3, 4, 5]);
    });
  },
);

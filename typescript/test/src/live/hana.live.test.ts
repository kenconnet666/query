import assert from "node:assert/strict";
import { it } from "node:test";
import hana from "@sap/hana-client";
import { addDays, addMonths, count, deleteFrom, insertInto, QueryExecutionError, updateJoin, cube, date, daysBetween, decimal, from, greatest, grouping, groupingSets, hanaDriver, HanaDatabase, hint, least, lit, nullOf, stringAgg, substring, sum, toInteger, toVarchar } from "@zerodep-dev/query-hana";
import * as hanax from "@zerodep-dev/query-hana";
import { Lips, Mara } from "../fixtures/erp.js";
import * as writes from "../fixtures/writes.js";
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
    it("联表更新一行匹配多行时整条语句报错且没有行被改", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Note).all());
      await db.execute(deleteFrom(writes.Doc).all());
      await db.execute(writes.insertNotes());
      await db.execute(insertInto(writes.Doc).values({ id: "D1", title: "first", version: 1 }, { id: "D2", title: "first", version: 1 }));
      // N1 的标题 first 对上两份文档；PostgreSQL、MySQL 会任取一行，HANA 的 MERGE 报 689
      await assert.rejects(
        db.execute(
          updateJoin(writes.Note, writes.Doc, (n, d) => n.title.eq(d.title))
            .set((_n, d) => ({ status: d.id }))
            .all(),
        ),
        QueryExecutionError,
      );
      assert.deepEqual(
        await db.list(
          from(writes.Note)
            .orderBy((n) => n.id)
            .select((n) => n.status),
        ),
        ["open", "open"],
      );
    });

    it("超时按整秒交给客户端，短于超时的语句照常完成", async () => {
      const start = Date.now();
      await live().db.sql("CALL QUERY_IT.SLOW_ROW(1)").timeout(2500).list();
      assert.ok(Date.now() - start >= 1000, "the procedure slept its second");
    });

    it("HANA 日期函数在实库上可用", async () => {
      const { db } = live();
      const day = lit("2026-01-01", date);
      const one = from(Lips).limit(1);
      assert.equal(await db.one(one.select(() => daysBetween(day, addMonths(day, 1)))), 31);
      assert.equal(await db.one(one.select(() => toVarchar(addDays(day, 4), "YYYYMMDD"))), "20260105");
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
          .select(() => count()),
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
          .select((l) => greatest(l.quantity, nullOf(decimal))),
      );
      assert.equal(withNull, null);
    });

    it("TO_INTEGER 把文本转成整数", async () => {
      const numbers = await live().db.list(
        from(Lips)
          .orderBy((l) => l.delivery)
          .select((l) => toInteger(substring(l.delivery, 2))),
      );
      assert.deepEqual(numbers, [1, 2, 3, 4, 5]);
    });

    it("FULL JOIN 保留两侧没有匹配的行且有全局过滤条件时报错", async () => {
      const { db } = live();
      const rows = await db.list(from(Lips).unfiltered().fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l, m) => ({ delivery: l.delivery, material: m.material })));
      assert.equal(rows.length, 12);
      assert.equal(rows.filter((r) => r.delivery === null).length, 2);
      await assert.rejects(db.list(from(Lips).fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l) => l.delivery)), /FULL JOIN cannot keep/);
    });

    it("中位数与窗口式的百分位数", async () => {
      const { db } = live();
      const windows = await db.list(
        from(Lips)
          .distinct()
          .orderBy((l) => l.material)
          .select((l) => ({
            material: l.material,
            median: hanax.percentileCont(0.5, l.quantity).over((w) => w.partitionBy(l.material)),
            lower: hanax.percentileDisc(0.5, l.quantity).over((w) => w.partitionBy(l.material)),
          })),
      );
      assert.deepEqual(windows.map((r) => `${r.material}:${r.median}:${Number(r.lower)}`), ["M1:2:2", "M2:6:5"]);
      const grouped = await db.list(
        from(Lips)
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({ material: l.material, median: hanax.median(l.quantity), self: hanax.corr(l.quantity, l.quantity) })),
      );
      assert.deepEqual(grouped.map((r) => `${r.material}:${r.median}:${Math.round(r.self!)}`), ["M1:2:1", "M2:6:1"]);
    });

    it("HANA 没有 INTERSECT ALL", async () => {
      const q = from(Lips).select((l) => l.material);
      await assert.rejects(live().db.list(q.combine("INTERSECT ALL", q)), /HANA has no INTERSECT ALL/);
    });
  },
);

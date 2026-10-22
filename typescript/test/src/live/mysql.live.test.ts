import assert from "node:assert/strict";
import { it } from "node:test";
import mysql from "mysql2/promise";
import { date, dateDiff, decimal, from, greatest, groupConcat, least, lit, maxExecutionTime, MysqlDatabase, mysqlDriver, nullOf } from "@zerodep-dev/query-mysql";
import { Lips } from "../erp.js";
import { liveScenarios } from "./live.js";

liveScenarios(
  {
    name: "MYSQL",
    async connect(config) {
      const pool = mysql.createPool({
        host: config.host,
        port: config.port,
        ...(config.database === undefined ? {} : { database: config.database }),
        user: config.user,
        password: config.password,
        connectionLimit: 4,
        // Unicode identifiers and text, as the Java URL's defaults.
        charset: "utf8mb4",
      });
      return { driver: mysqlDriver(pool), close: () => pool.end() };
    },
    database: (driver, options) => new MysqlDatabase(driver, options),
    text: (length) => `VARCHAR(${length})`,
    // SLEEP() interrupted by max_execution_time returns 1 instead of failing, so this counts a billion rows instead.
    slowStatement: "SELECT COUNT(*) FROM (SELECT 1 FROM information_schema.columns LIMIT 1000) a, (SELECT 1 FROM information_schema.columns LIMIT 1000) b, (SELECT 1 FROM information_schema.columns LIMIT 1000) c",
    q: (identifier) => `\`${identifier}\``,
    // TIMESTAMP converts through the session time zone and ends in 2038; DATETIME is a plain local value.
    timestamp: "DATETIME(6)",
  },
  (live) => {
    it("MySQL 日期函数与 GROUP_CONCAT", async () => {
      const { db } = live();
      const days = await db.one(
        from(Lips)
          .limit(1)
          .selectValue(() => dateDiff(lit("2026-02-01", date), lit("2026-01-01", date))),
      );
      assert.equal(days, 31);
      const rows = await db.list(
        from(Lips)
          .with(maxExecutionTime(5000))
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({ material: l.material, deliveries: groupConcat(l.delivery, ",", l.delivery.asc()) }))
          .map((r) => `${r.material}=${r.deliveries}`),
      );
      assert.deepEqual(rows, ["M1=D1,D2,D5", "M2=D3,D4"]);
    });

    it("原生 SQL 不在反引号和反斜杠转义的字符串里找参数", async () => {
      const rows = await live().db.sql("SELECT CONCAT('it\\'s :no', :x) AS `v:w`").bind("x", "!").values();
      assert.deepEqual(rows, ["it's :no!"]);
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
  },
);

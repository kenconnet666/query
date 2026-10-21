import assert from "node:assert/strict";
import { it } from "node:test";
import mysql from "mysql2/promise";
import { date, dateDiff, from, groupConcat, lit, maxExecutionTime, MysqlDatabase, mysqlDriver } from "@query/mysql";
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
  },
);

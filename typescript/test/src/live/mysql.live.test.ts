import assert from "node:assert/strict";
import { it } from "node:test";
import mysql from "mysql2/promise";
import { date, daysBetween, decimal, from, greatest, least, stringAgg, lit, maxExecutionTime, MysqlDatabase, mysqlDriver, nullOf } from "@zerodep-dev/query-mysql";
import * as mysqlx from "@zerodep-dev/query-mysql";
import { Bound, count, plus } from "@zerodep-dev/query-mysql";
import { Event, Lips, Mara } from "../fixtures/erp.js";
import { Article } from "../fixtures/dialect-tables.js";
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
    setup: articleTable(),
    q: (identifier) => `\`${identifier}\``,
    // TIMESTAMP converts through the session time zone and ends in 2038; DATETIME is a plain local value.
    timestamp: "DATETIME(6)",
    identity: "INT AUTO_INCREMENT PRIMARY KEY",
  },
  (live) => {
    it("DATEDIFF 与 GROUP_CONCAT", async () => {
      const { db } = live();
      const days = await db.one(
        from(Lips)
          .limit(1)
          .select(() => daysBetween(lit("2026-01-01", date), lit("2026-02-01", date))),
      );
      assert.equal(days, 31);
      const rows = await db.list(
        from(Lips)
          .with(maxExecutionTime(5000))
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({ material: l.material, deliveries: stringAgg(l.delivery, ",", l.delivery.asc()) }))
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
          .select((l) => greatest(l.quantity, nullOf(decimal))),
      );
      assert.equal(withNull, null);
    });

    it("INTERSECT ALL 与 EXCEPT ALL 按次数保留重复的行", async () => {
      const { db } = live();
      const each = from(Lips).where((l) => l.unit.eq("EA")).select((l) => l.material);
      const master = from(Mara).select((m) => m.material);
      assert.deepEqual((await db.list(mysqlx.intersectAll(each, master))).sort(), ["M1", "M2"]);
      assert.deepEqual((await db.list(mysqlx.exceptAll(each, master))).sort(), ["M1", "M1", "M2"]);
    });

    it("RANGE 窗口帧按排序值的距离取行", async () => {
      const rows = await live().db.list(
        from(Event)
          .orderBy((e) => e.id)
          .select((e) => ({
            id: e.id,
            lastWeek: count().over((w) => w.orderBy(e.onDay).frame(mysqlx.range(mysqlx.daysPreceding(7), Bound.currentRow()))),
            near: count().over((w) => w.orderBy(e.id).frame(mysqlx.range(Bound.preceding(1), Bound.following(0)))),
          })),
      );
      assert.deepEqual(rows.map((r) => `${r.id}:${r.lastWeek}:${r.near}`), ["1:1:1", "2:1:2"]);
    });

    it("递归 CTE 逐层展开到终止条件", async () => {
      const steps = mysqlx.recursiveCte(
        "steps",
        from(Lips).where((l) => l.delivery.eq("D1")).select((l) => ({ delivery: l.delivery, depth: lit(1) })),
        (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: plus(s.depth, lit(1)) })),
      );
      assert.deepEqual(await live().db.list(from(steps).orderBy((s) => s.depth).select((s) => s.depth)), [1, 2, 3]);
    });

    it("共享锁与只锁部分表的加锁读", async () => {
      const { db } = live();
      await db.transaction(async () => {
        assert.equal((await db.list(from(Lips).join(Mara, (l, m) => m.material.eq(l.material)).with((l) => mysqlx.forUpdateOf("skipLocked", l)).select((l) => l.delivery))).length, 5);
        assert.equal((await db.list(from(Lips).with(mysqlx.forShare("nowait")).select((l) => l.delivery))).length, 5);
      });
    });

    it("MySQL 没有 FULL JOIN", async () => {
      await assert.rejects(live().db.list(from(Lips).unfiltered().fullJoin(Mara, (l, m) => m.material.eq(l.material)).select((l) => l.delivery)), /has no FULL JOIN/);
    });

    it("优化器提示合并进一个注释", async () => {
      const rows = await live().db.list(
        from(Lips).join(Mara, (l, m) => m.material.eq(l.material)).with(() => mysqlx.maxExecutionTime(5000)).with((l, m) => mysqlx.noIndex(m)).orderBy((l) => l.delivery).select((l) => l.delivery),
      );
      assert.deepEqual(rows, ["D1", "D2", "D3", "D4", "D5"]);
    });

    it("JSON 的包含与路径文本", async () => {
      const rows = await live().db.list(from(Article).where((a) => mysqlx.jsonContains(a.doc, '{"status":"open"}')).orderBy((a) => a.id).select((a) => ({ id: a.id, owner: mysqlx.jsonText(a.doc, "$.owner.name") })));
      assert.deepEqual(rows.map((r) => `${r.id}:${r.owner}`), ["1:ann", "3:null"]);
    });

    it("全文检索的自然语言与布尔模式", async () => {
      const { db } = live();
      assert.deepEqual(await db.list(from(Article).where((a) => mysqlx.match("pump", a.title, a.body)).select((a) => a.id)), [1]);
      assert.deepEqual(await db.list(from(Article).where((a) => mysqlx.matchBoolean("+valve", a.title, a.body)).orderBy((a) => a.id).select((a) => a.id)), [2, 3]);
      assert.deepEqual(await db.list(from(Article).where((a) => mysqlx.matchBoolean("+valve -found", a.title, a.body)).select((a) => a.id)), [2]);
      assert.equal((await db.list(from(Article).orderBy((a) => [mysqlx.matchScore("pump", a.title, a.body).desc(), a.id]).select((a) => a.id)))[0], 1);
    });
  },
);

/** 建 JSON 和全文检索用的表。 */
function articleTable(): string[] {
  return [
    "-DROP TABLE `QUERY_IT`.`MY_ARTICLE`",
    "CREATE TABLE `QUERY_IT`.`MY_ARTICLE` (`ID` INTEGER, `DOC` JSON, `TITLE` VARCHAR(100), `BODY` TEXT, FULLTEXT ft_article (`TITLE`, `BODY`)) ENGINE=InnoDB",
    `INSERT INTO \`QUERY_IT\`.\`MY_ARTICLE\` VALUES (1, '{"status":"open","owner":{"name":"ann"}}', 'pump manual', 'the pump is running')`,
    `INSERT INTO \`QUERY_IT\`.\`MY_ARTICLE\` VALUES (2, '{"status":"closed"}', 'valve notes', 'valve without leak')`,
    `INSERT INTO \`QUERY_IT\`.\`MY_ARTICLE\` VALUES (3, '{"status":"open"}', 'valve report', 'valve leak found')`,
  ];
}

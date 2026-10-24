import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import {
  caseWhen,
  count,
  countDistinct,
  cte,
  deleteFrom,
  filter,
  forUpdate,
  from,
  inMemoryCache,
  insertInto,
  left,
  negate,
  plus,
  replace,
  right,
  stringAgg,
  substring,
  sum,
  update,
  val,
  values,
  QueryExecutionError,
  UnexpectedRowCountError,
  type Database,
  type DatabaseOptions,
  type Driver,
  type QueryEvent,
  type Ref,
} from "@zerodep-dev/query-core";
import { ClientEntity, Event, LineVO, Lips, Mara, MaraView } from "../fixtures/erp.js";
import { dateFunctions, numberTextFunctions, timeFunctions, DeliverySearch, groupJoin, filterMany, MaterialSearch, manyText, qualify, windowValues, dynamicColumns, filterObject, floorCeil, nativeSql, reservedAlias, rollup, simpleCase, textPositions, unionGrouping } from "../fixtures/spec-cases.js";
import { deliveries, latestDeliveries, lines, partTotals, stock } from "../fixtures/scenarios.js";
import * as wf from "../fixtures/workflow.js";
import * as writes from "../fixtures/writes.js";

/**
 * Connection settings for live tests, from the JDBC-style QUERY_{NAME}_URL / _USER / _PASSWORD the
 * Java tests use. Tests are skipped when the variables are absent; CI also sets QUERY_REQUIRE_LIVE,
 * which turns a missing variable into a failure instead of a silent skip.
 */
export interface LiveConfig {
  readonly host: string;
  readonly port: number;
  readonly database: string | undefined;
  readonly user: string;
  readonly password: string;
}

function liveConfig(name: string): LiveConfig | string {
  const url = process.env[`QUERY_${name}_URL`];
  const user = process.env[`QUERY_${name}_USER`];
  const password = process.env[`QUERY_${name}_PASSWORD`];
  const message = `QUERY_${name}_URL/_USER/_PASSWORD not set`;
  if (url === undefined || user === undefined || password === undefined) {
    if (process.env.QUERY_REQUIRE_LIVE !== undefined) throw new Error(`${message} but QUERY_REQUIRE_LIVE is`);
    return `${message}; skipping live ${name} tests`;
  }
  const parsed = new URL(url.replace(/^jdbc:/, ""));
  const database = parsed.pathname.replace(/^\//, "");
  return { host: parsed.hostname, port: Number(parsed.port), database: database === "" ? undefined : database, user, password };
}

/** Text that would end a string literal early, open a comment or look like a parameter. */
const HOSTILE = ["O'Brien", "back\\slash", "\\'; DROP TABLE x; --", "ends with \\", "-- not a comment", "/* nor this */", "? :name $1 $$", "中文 Ünïcødé"];

/** One dialect's side of the shared scenarios: how to connect, and its DDL spelling. */
export interface LiveTarget {
  /** The QUERY_{NAME}_ prefix and the suite name. */
  readonly name: string;
  connect(config: LiveConfig): Promise<{ driver: Driver; close(): Promise<void> }>;
  database(driver: Driver, options: DatabaseOptions): Database;
  /** The type for bounded Unicode text. */
  text(length: number): string;
  /** How this dialect quotes an identifier in hand-written DDL and native SQL. */
  q?(identifier: string): string;
  /** The column type for a local date-time without time zone conversion. */
  timestamp?: string;
  /** The column type of a key the database generates on insert; IDENTITY by default. */
  identity?: string;
  /** A hand-written statement that runs for several seconds before returning a row. */
  readonly slowStatement: string;
  /** Statements run after the shared tables exist; one starting with "-" may fail. */
  readonly setup?: readonly string[];
}

export interface LiveContext {
  readonly config: LiveConfig;
  readonly db: Database;
  readonly driver: Driver;
  q(identifier: string): string;
  table(name: string): string;
}

/** The same scenarios on every dialect's real database, as Java's LiveScenarios. */
export function liveScenarios(target: LiveTarget, extra?: (live: () => LiveContext) => void): void {
  const config = liveConfig(target.name);
  describe(`${target.name} 实库场景`, typeof config === "string" ? { skip: config } : {}, () => {
    let context: LiveContext | undefined;
    let close: (() => Promise<void>) | undefined;
    const live = () => context ?? assert.fail("live database not connected");

    before(async () => {
      const connection = await target.connect(config as LiveConfig);
      close = connection.close;
      const driver = connection.driver;
      const q = target.q ?? ((id: string) => `"${id}"`);
      const t = (name: string) => `${q("QUERY_IT")}.${q(name)}`;
      const c = (name: string, length: number) => `${q(name)} ${target.text(length)}`;
      const create = (name: string, ...columns: string[]) => `CREATE TABLE ${t(name)} (${columns.join(", ")})`;
      const insert = (name: string, values: string) => `INSERT INTO ${t(name)} VALUES (${values})`;
      const ts = target.timestamp ?? "TIMESTAMP";
      const statements = [
        `-CREATE SCHEMA ${q("QUERY_IT")}`,
        ...["MARA", "LIPS", "MAKT", "VBKD", "MARDH", "MCHBH", "EVENTS", "WF_PROCDEF", "WF_PROCINST", "WF_TASK", "WF_LINK", "WF_VARIABLE", "WF_HI_TASK", "W_NOTE", "W_DOC", "W_SEQ", "W_TICKET", "W_CODE"].map(
          (name) => `-DROP TABLE ${t(name)}`,
        ),
        create("MARA", c("MANDT", 3), c("MATNR", 40), c("MFRPN", 40), c("MFRNR", 10), c("MTART", 4)),
        create("LIPS", c("MANDT", 3), c("VBELN", 10), c("MATNR", 40), c("ERDAT", 8), `${q("LFIMG")} DECIMAL(13,3)`, c("VRKME", 3), c("VGBEL", 10)),
        create("MAKT", c("MANDT", 3), c("MATNR", 40), c("SPRAS", 1), c("MAKTX", 40)),
        create("VBKD", c("MANDT", 3), c("VBELN", 10)),
        create("MARDH", c("MANDT", 3), c("MATNR", 40), c("LFGJA", 4), c("LFMON", 2), `${q("LABST")} DECIMAL(13,3)`),
        create("MCHBH", c("MANDT", 3), c("MATNR", 40), c("LFGJA", 4), c("LFMON", 2), `${q("CLABS")} DECIMAL(13,3)`),
        create("EVENTS", `${q("ID")} INTEGER`, `${q("ON_DAY")} DATE`, `${q("AT_TIME")} ${target.timestamp ?? "TIMESTAMP"}`),
        insert("MARA", "'100', 'M1', 'P1 ', 'MK1', 'ZMAT'"),
        insert("MARA", "'100', 'M2', 'P2', 'MK2', 'ZMAT'"),
        insert("MARA", "'100', 'M3', 'P3', 'MK1', 'ZOTH'"),
        insert("MARA", "'200', 'M1', 'X1', 'MK1', 'ZMAT'"),
        insert("MARA", "'100', 'M4', 'P1', '', 'ZOTH'"),
        insert("MAKT", "'100', 'M1', '1', 'Pump'"),
        insert("MAKT", "'100', 'M2', '2', 'Valve'"),
        insert("MAKT", "'200', 'M1', '1', 'Other'"),
        insert("LIPS", "'100', 'D1', 'M1', '20260115', 3.000, 'EA', 'S1'"),
        insert("LIPS", "'100', 'D2', 'M1', '20260120', 2.000, 'EA', 'S2'"),
        insert("LIPS", "'100', 'D3', 'M2', '20260210', 5.000, 'EA', 'S1'"),
        insert("LIPS", "'100', 'D4', 'M2', '20251231', 7.000, 'EA', 'S1'"),
        insert("LIPS", "'100', 'D5', 'M1', '20260301', 1.000, 'EA', 'S9'"),
        insert("LIPS", "'200', 'D6', 'M1', '20260115', 100.000, 'EA', 'S1'"),
        insert("VBKD", "'100', 'S1'"),
        insert("VBKD", "'100', 'S2'"),
        insert("VBKD", "'200', 'S1'"),
        insert("MARDH", "'100', 'M1', '2026', '01', 10.000"),
        insert("MARDH", "'100', 'M2', '2026', '01', NULL"),
        insert("MARDH", "'100', 'M1', '2025', '12', 4.000"),
        insert("MCHBH", "'100', 'M1', '2026', '02', 6.000"),
        insert("EVENTS", "1, DATE '2024-03-31', TIMESTAMP '2024-03-31 02:30:00'"),
        insert("EVENTS", "2, DATE '2011-12-30', TIMESTAMP '2011-12-30 00:00:00'"),
        // 流程引擎场景的表和数据，见 fixtures/workflow.ts
        create("WF_PROCDEF", c("ID", 10), c("DEF_KEY", 20), c("NAME", 20), `${q("VERSION")} INTEGER`),
        create("WF_PROCINST", c("ID", 10), c("DEF_ID", 10), c("BUSINESS_KEY", 20), c("START_USER", 20), `${q("START_TIME")} ${ts}`, `${q("END_TIME")} ${ts}`, `${q("DURATION")} BIGINT`),
        create("WF_TASK", c("ID", 10), c("NAME", 20), c("ASSIGNEE", 20), c("PROC_INST_ID", 10), c("DEF_ID", 10), `${q("CREATE_TIME")} ${ts}`),
        create("WF_LINK", c("ID", 10), c("TASK_ID", 10), c("KIND", 20), c("USER_ID", 20), c("GROUP_ID", 20)),
        create("WF_VARIABLE", c("ID", 10), c("PROC_INST_ID", 10), c("NAME", 20), c("TEXT_VALUE", 40), `${q("LONG_VALUE")} BIGINT`),
        create("WF_HI_TASK", c("ID", 10), c("NAME", 20), c("ASSIGNEE", 20), c("PROC_INST_ID", 10), `${q("START_TIME")} ${ts}`, `${q("END_TIME")} ${ts}`, `${q("DURATION")} BIGINT`),
        insert("WF_PROCDEF", "'D1', 'leave', 'Leave', 1"),
        insert("WF_PROCDEF", "'D2', 'leave', 'Leave', 2"),
        insert("WF_PROCDEF", "'D3', 'expense', 'Expense', 1"),
        insert("WF_PROCINST", "'P1', 'D2', 'BK-1', 'alice', TIMESTAMP '2026-01-10 09:00:00', NULL, NULL"),
        insert("WF_PROCINST", "'P2', 'D2', 'BK-2', 'bob', TIMESTAMP '2026-01-11 09:00:00', NULL, NULL"),
        insert("WF_PROCINST", "'P3', 'D3', 'BK-3', 'alice', TIMESTAMP '2026-01-05 09:00:00', TIMESTAMP '2026-01-12 10:00:00', 7200000"),
        insert("WF_PROCINST", "'P4', 'D1', 'BK-4', 'alice', TIMESTAMP '2025-12-01 09:00:00', TIMESTAMP '2025-12-01 10:00:00', 3600000"),
        insert("WF_VARIABLE", "'V1', 'P1', 'amount', NULL, 1500"),
        insert("WF_VARIABLE", "'V2', 'P1', 'dept', 'X', NULL"),
        insert("WF_VARIABLE", "'V3', 'P2', 'amount', NULL, 500"),
        insert("WF_VARIABLE", "'V4', 'P2', 'dept', 'X', NULL"),
        insert("WF_VARIABLE", "'V5', 'P3', 'amount', NULL, 2000"),
        insert("WF_VARIABLE", "'V6', 'P3', 'dept', 'Y', NULL"),
        insert("WF_TASK", "'T1', 'approve', NULL, 'P1', 'D2', TIMESTAMP '2026-01-10 09:00:00'"),
        insert("WF_TASK", "'T2', 'approve', 'alice', 'P2', 'D2', TIMESTAMP '2026-01-11 09:00:00'"),
        insert("WF_TASK", "'T3', 'review', NULL, 'P2', 'D2', TIMESTAMP '2026-01-11 10:00:00'"),
        insert("WF_TASK", "'T4', 'pay', 'carol', 'P1', 'D2', TIMESTAMP '2026-01-10 11:00:00'"),
        insert("WF_LINK", "'L1', 'T1', 'candidate', 'bob', NULL"),
        insert("WF_LINK", "'L2', 'T1', 'candidate', NULL, 'managers'"),
        insert("WF_LINK", "'L3', 'T3', 'candidate', NULL, 'finance'"),
        insert("WF_LINK", "'L4', 'T4', 'candidate', 'alice', NULL"),
        insert("WF_LINK", "'L5', 'T3', 'participant', 'alice', NULL"),
        insert("WF_HI_TASK", "'H1', 'approve', 'alice', 'P3', TIMESTAMP '2026-01-05 09:00:00', TIMESTAMP '2026-01-11 09:00:00', 3600000"),
        insert("WF_HI_TASK", "'H2', 'pay', 'alice', 'P3', TIMESTAMP '2026-01-11 09:00:00', TIMESTAMP '2026-01-12 10:00:00', 5400000"),
        insert("WF_HI_TASK", "'H3', 'approve', 'bob', 'P4', TIMESTAMP '2025-12-01 09:00:00', TIMESTAMP '2025-12-01 09:10:00', 600000"),
        insert("WF_HI_TASK", "'H4', 'approve', 'alice', 'P1', TIMESTAMP '2026-01-10 09:00:00', NULL, NULL"),
        create("W_NOTE", c("ID", 10), c("TENANT", 10), c("TITLE", 40), `${q("QTY")} INTEGER`, c("STATUS", 10)),
        create("W_DOC", `${c("ID", 10)} PRIMARY KEY`, c("TITLE", 40), `${q("VERSION")} INTEGER`, `${c("SOURCE", 10)} DEFAULT 'db'`),
        create("W_SEQ", `${q("ID")} ${target.identity ?? "INTEGER GENERATED BY DEFAULT AS IDENTITY"}`, c("NAME", 20)),
        create("W_CODE", `${c("ID", 10)} PRIMARY KEY`, c("CODE", 10), `${q("DELETED")} BIGINT`, `UNIQUE (${q("CODE")}, ${q("DELETED")})`),
        create("W_TICKET", `${c("ID", 10)} PRIMARY KEY`, c("TITLE", 40), `${q("DELETED")} INTEGER`, `${q("CREATED_AT")} ${ts}`, `${q("UPDATED_AT")} ${ts}`, c("UPDATED_BY", 20)),
        ...(target.setup ?? []),
      ];
      // A statement starting with "-" may fail, e.g. dropping what does not exist.
      for (const statement of statements) {
        const optional = statement.startsWith("-");
        try {
          await driver.query(optional ? statement.slice(1) : statement, []);
        } catch (e) {
          if (!optional) throw new Error(`${e instanceof Error ? e.message : String(e)}\n${statement}`, { cause: e });
        }
      }
      const db = target.database(driver, { filters: [filter(ClientEntity, (r) => r.client.eq("100"))] });
      context = { config: config as LiveConfig, db, driver, q, table: t };
    });

    after(async () => {
      await close?.();
    });

    it("带 CTE 和 EXISTS 的交货汇总在实库上返回正确结果", async () => {
      const { db } = live();
      const rows = await db.list(deliveries("2026-01-01", "2026-06-30", null));
      assert.deepEqual(
        rows.map((r) => [r.partNumber, r.name, Number(r.quantity)]),
        [
          ["P1", "Pump", 5],
          ["P2", "P2", 5],
        ],
      );
      const mk2 = await db.list(deliveries("2026-01-01", "2026-06-30", "MK2"));
      assert.deepEqual(
        mk2.map((r) => r.partNumber),
        ["P2"],
      );
    });

    it("全局过滤器只读当前客户端且可以关闭", async () => {
      const { db } = live();
      assert.equal(await db.one(from(Lips).select(() => count())), 5);
      assert.equal(
        await db.one(
          from(Lips)
            .unfiltered()
            .select(() => count()),
        ),
        6,
      );
    });

    it("泛型模板生成的 UNION 合并两张库存表", async () => {
      const rows = await live().db.list(stock("2026"));
      assert.deepEqual(new Set(rows.map((r) => `${r.material}/${r.period}/${Number(r.quantity)}`)), new Set(["M1/01/10", "M2/01/0", "M1/02/6"]));
    });

    it("窗口函数取每个物料最新的交货", async () => {
      assert.deepEqual(await live().db.list(latestDeliveries()), ["D5", "D3"]);
    });

    it("自动投影按同名字段和 mapFrom 映射并转换 SAP 日期", async () => {
      const rows = await live().db.list(lines());
      assert.deepEqual(
        rows.map((r) => r.delivery),
        ["D1", "D2", "D3", "D4", "D5"],
      );
      const first = rows[0]!;
      assert.ok(first instanceof LineVO);
      assert.equal(first.materialNo, "M1");
      assert.equal(first.createdOn, "2026-01-15");
      assert.equal(first.text, "Pump");
      assert.equal(rows[2]!.text, null, "LEFT JOIN keeps rows without text even with a filter on the joined table");
    });

    it("SAP 日期参数按转换器绑定", async () => {
      const rows = await live().db.list(
        from(Lips)
          .where((l) => l.createdOn.eq("2026-01-15"))
          .select((l) => l.delivery),
      );
      assert.deepEqual(rows, ["D1"]);
    });

    it("嵌套结果和 JS 端计算", async () => {
      const rows = await live().db.list(partTotals());
      assert.equal(rows.length, 2);
      assert.equal(rows[0]!.partNumber.trim(), "P1");
      assert.equal(Number(rows[0]!.amount.quantity), 6);
      assert.ok(Math.abs(rows[0]!.amount.wan - 0.0006) < 1e-12);
    });

    it("分页返回当前页和总数", async () => {
      const page = await live().db.page(lines(), 2, 2);
      assert.equal(page.total, 5);
      assert.equal(page.totalPages, 3);
      assert.deepEqual(
        page.rows.map((r) => r.delivery),
        ["D3", "D4"],
      );
    });

    it("流式读取逐行处理", async () => {
      const seen: string[] = [];
      await live().db.forEach(lines(), (row) => void seen.push(row.delivery));
      assert.deepEqual(seen, ["D1", "D2", "D3", "D4", "D5"]);
      for await (const row of live().db.stream(lines())) {
        assert.equal(row.delivery, "D1");
        break; // stopping early must release the cursor
      }
      assert.equal(await live().db.count(lines()), 5);
    });

    it("原生 SQL 的命名参数和数组展开", async () => {
      const { db, q, table } = live();
      const rows = await db
        .sql(`SELECT ${q("VBELN")} AS delivery FROM ${table("LIPS")} WHERE ${q("MANDT")} = :client AND ${q("VBELN")} IN (:ids) ORDER BY ${q("VBELN")}`)
        .bind("client", "100")
        .bind("ids", ["D1", "D3"])
        .values<string>();
      assert.deepEqual(rows, ["D1", "D3"]);
    });

    it("结果缓存命中时不再查询数据库", async () => {
      const { driver } = live();
      const events: QueryEvent[] = [];
      const cached = target.database(driver, { cache: inMemoryCache(), listener: (e) => void events.push(e) });
      const q = from(Lips)
        .select(() => count())
        .cached(60_000);
      assert.equal(await cached.one(q), 6);
      assert.equal(await cached.one(q), 6);
      assert.deepEqual(
        events.map((e) => e.cached),
        [false, true],
      );
    });

    it("日期和时间戳不受进程时区影响", async () => {
      const { db } = live();
      const original = process.env.TZ;
      try {
        for (const zone of ["Europe/Berlin", "Pacific/Apia", "Asia/Shanghai"]) {
          process.env.TZ = zone;
          const events = await db.list(
            from(Event)
              .orderBy((e) => e.id)
              .selectAs(Event),
          );
          assert.equal(events[0]!.onDay, "2024-03-31", zone);
          assert.equal(events[0]!.atTime, "2024-03-31 02:30:00", zone);
          assert.equal(events[1]!.onDay, "2011-12-30", zone);
          const byDay = from(Event)
            .where((e) => e.onDay.eq("2011-12-30"))
            .select((e) => e.id);
          assert.deepEqual(await db.list(byDay), [2], zone);
          const byTime = from(Event)
            .where((e) => e.atTime.eq("2024-03-31 02:30:00"))
            .select((e) => e.id);
          assert.deepEqual(await db.list(byTime), [1], zone);
        }
      } finally {
        if (original === undefined) delete process.env.TZ;
        else process.env.TZ = original;
      }
    });

    it("常量行与带常量的分组在实库上可执行", async () => {
      const size = (l: Ref<Lips>) => caseWhen(l.quantity.le("2"), "small").when(l.quantity.le("5"), "medium").otherwise("large");
      const sizes = cte(
        "sizes",
        values([
          { name: "small", ord: 1 },
          { name: "medium", ord: 2 },
          { name: "large", ord: 3 },
        ]),
      );
      const totals = cte(
        "totals",
        from(Lips)
          .groupBy((l) => size(l))
          .select((l) => ({ bucket: size(l), total: sum(l.quantity) })),
      );
      const rows = await live().db.list(
        from(sizes)
          .leftJoin(totals, (s, t) => t.bucket.eq(s.name))
          .orderBy((s) => s.ord)
          .select((s, t) => ({ name: s.name, total: t.total.coalesce("0") })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.name}=${Number(r.total)}`),
        ["small=3", "medium=8", "large=7"],
      );
    });

    it("文本函数取负与多项求和以及按子串分组", async () => {
      const { db } = live();
      const [pieces] = await db.list(
        from(Lips)
          .where((l) => l.delivery.eq("D1"))
          .select((l) => ({
            prefix: left(l.delivery, 1),
            tail: substring(l.material, 2),
            mid: substring(l.unit, 1, 1),
            suffix: right(l.salesOrder, 1),
            cleaned: replace(l.unit, "A", "-"),
            negative: negate(l.quantity),
            twice: plus([l.quantity, l.quantity]),
          })),
      );
      assert.deepEqual({ ...pieces!, negative: Number(pieces!.negative), twice: Number(pieces!.twice) }, {
        prefix: "D",
        tail: "1",
        mid: "E",
        suffix: "1",
        cleaned: "E-",
        negative: -3,
        twice: 6,
      });
      const prefixes = await db.list(
        from(Lips)
          .groupBy((l) => substring(l.delivery, 1, 1))
          .select((l) => ({ prefix: substring(l.delivery, 1, 1), n: count() })),
      );
      assert.deepEqual(prefixes, [{ prefix: "D", n: 5 }]);
    });

    it("列转换按转换后的值读取且可与原值并存", async () => {
      const { db } = live();
      const rows = await db.list(
        from(MaraView)
          .groupBy((m) => m.partNumber)
          .orderBy((m) => m.partNumber)
          .select((m) => ({ part: m.partNumber, makers: countDistinct(m.maker), stored: countDistinct(m.storedMaker) })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.part}:${r.makers}:${r.stored}`),
        ["P1:1:2", "P2:1:1", "P3:1:1"],
      );
      const blank = await db.list(
        from(MaraView)
          .where((m) => m.maker.isNull())
          .select((m) => m.material),
      );
      assert.deepEqual(blank, ["M4"]);
    });

    it("ROLLUP 给出小计与总计", async () => {
      const rows = await live().db.list(rollup());
      assert.deepEqual(
        rows.map((r) => `${r.material}/${r.unit}/${Number(r.total)}/${r.level}`),
        ["M1/EA/6/0", "M1/null/6/0", "M2/EA/12/0", "M2/null/12/0", "null/null/18/1"],
      );
    });

    it("查询出错、映射出错或提前结束流时都归还连接", { timeout: 30_000 }, async () => {
      const { db } = live();
      const deliveries = from(Lips)
        .orderBy((l) => l.delivery)
        .select((l) => l.delivery);
      const failsOnD3 = deliveries.map((d) => {
        if (d === "D3") throw new Error("mapping failed");
        return d;
      });
      // The pools hold four connections: a leak in any of these would exhaust them before the last query.
      for (let i = 0; i < 5; i++) {
        await assert.rejects(db.sql("SELECT * FROM NO_SUCH_TABLE").list());
        await assert.rejects(db.list(failsOnD3), /mapping failed/);
        for await (const d of db.stream(deliveries)) {
          assert.equal(d, "D1");
          break;
        }
        await assert.rejects(async () => {
          for await (const d of db.stream(failsOnD3)) assert.ok(d);
        }, /mapping failed/);
        await assert.rejects(db.forEach(deliveries, () => Promise.reject(new Error("consumer failed"))), /consumer failed/);
      }
      assert.equal((await db.list(deliveries)).length, 5);
    });

    it("类名是保留字的实体也能查询", async () => {
      assert.deepEqual(await live().db.list(reservedAlias()), ["D1", "D2", "D5", "D6"]);
    });

    it("按 LOCATE 找到的位置截取文本", async () => {
      const rows = await live().db.list(textPositions());
      assert.deepEqual(
        rows.map((r) => `${r.text}:${r.comma}:${r.head}:${r.tail}`),
        ["05:0:05:null", "21,32:3:21:32"],
      );
    });

    it("简单 CASE 与多参数 COALESCE", async () => {
      const rows = await live().db.list(simpleCase());
      assert.deepEqual(
        rows.map((r) => `${r.material}:${r.kind}:${r.maker}`),
        ["M1:material:MK1", "M2:material:MK2", "M3:other:MK1", "M4:other:P1"],
      );
    });

    it("FLOOR 与 CEIL", async () => {
      const rows = await live().db.list(floorCeil());
      assert.deepEqual(
        rows.map((r) => `${r.delivery}:${Number(r.down)}:${Number(r.up)}`),
        ["D1:1:2", "D2:1:1", "D3:2:3", "D4:3:4", "D5:0:1"],
      );
    });

    it("导航关系在实库上按需连接并给出正确结果", async () => {
      const { db } = live();
      const rows = await db.list(
        from(Mara)
          .orderBy((m) => m.material)
          .select((m) => ({
            material: m.material,
            lines: m.deliveries.count(),
            total: m.deliveries.sum((l) => l.quantity),
            latest: m.deliveries.first((l) => l.createdOn.desc()).delivery,
            text: m.text.text,
          })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.material}:${Number(r.lines)}:${r.total === null ? null : Number(r.total)}:${r.latest}:${r.text}`),
        ["M1:3:6:D5:Pump", "M2:2:12:D3:null", "M3:0:null:null:null", "M4:0:null:null:null"],
      );
      assert.deepEqual(
        await db.list(
          from(Lips)
            .where((l) => l.mara.materialType.eq("ZMAT"))
            .orderBy((l) => l.delivery)
            .select((l) => l.delivery),
        ),
        ["D1", "D2", "D3", "D4", "D5"],
      );
      assert.deepEqual(await db.list(from(Lips).where((l) => l.salesData.none()).select((l) => l.delivery)), ["D5"]);
      assert.deepEqual(
        await db.list(
          from(Mara)
            .where((m) => m.deliveries.any((l) => l.quantity.gt("2.5")).and(m.deliveries.count().ge(3)))
            .select((m) => m.material),
        ),
        ["M1"],
      );
    });

    it("子数组与对象树在实库上用一条批量查询加载", async () => {
      const { db } = live();
      const events = await db.capture(async () => {
        const rows = await db.list(
          from(Mara)
            .orderBy((m) => m.material)
            .select((m) => ({ material: m.material, deliveries: m.deliveries.orderBy((l) => l.delivery).list((l) => l.delivery) })),
        );
        assert.deepEqual(
          rows.map((r) => `${r.material}:${r.deliveries.join(",")}`),
          ["M1:D1,D2,D5", "M2:D3,D4", "M3:", "M4:"],
        );
      });
      assert.equal(events.length, 2, "one statement for the parents and one for all children");
      const trees = await db.list(
        from(Mara)
          .orderBy((m) => m.material)
          .select((m) => ({
            material: m.material,
            text: { text: m.text.text },
            deliveries: m.deliveries.list((l) => ({ delivery: l.delivery, quantity: l.quantity })),
          })),
      );
      assert.deepEqual(
        trees.map((t) => `${t.material}:${t.text.text}:${t.deliveries.map((l) => `${l.delivery}/${Number(l.quantity)}`).sort().join(",")}`),
        ["M1:Pump:D1/3,D2/2,D5/1", "M2:null:D3/5,D4/7", "M3:null:", "M4:null:"],
      );
    });

    it("子数组可以限定每个父行取最新的几行", async () => {
      const rows = await live().db.list(
        from(Mara)
          .orderBy((m) => m.material)
          .select((m) => ({ material: m.material, deliveries: m.deliveries.orderBy((l) => l.createdOn.desc()).limit(2).list((l) => l.delivery) })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.material}:${r.deliveries.join(",")}`),
        ["M1:D5,D2", "M2:D3,D4", "M3:", "M4:"],
      );
    });

    it("条件对象经过一对多关系时同一个子行满足全部字段", async () => {
      const { db } = live();
      const search = (s: Partial<MaterialSearch>) => db.list(filterMany(Object.assign(new MaterialSearch(), s)));
      assert.deepEqual(await search({ unit: "EA", minQuantity: "5" }), ["M2"]);
      assert.deepEqual(await search({ unit: "EA" }), ["M1", "M2"]);
      assert.deepEqual(await search({ materialType: " " }), ["M1", "M2", "M3", "M4"]);
    });

    it("分组连接与相关子查询的结果相同", async () => {
      const { db } = live();
      const rows = await db.list(groupJoin());
      assert.deepEqual(
        rows.map((r) => `${r.material}:${r.lines}:${Number(r.total)}:${r.recent}`),
        ["M1:3:6:3", "M2:2:12:1"],
      );
      const empty = await db.list(
        from(Mara)
          .groupJoin((m) => m.deliveries)
          .where((m) => m.deliveries.none())
          .orderBy((m) => m.material)
          .select((m) => m.material),
      );
      assert.deepEqual(empty, ["M3", "M4"]);
    });

    it("条件对象在实库上筛选且通配符按普通字符匹配", async () => {
      const { db } = live();
      const find = (values: Partial<DeliverySearch>) => db.list(filterObject(Object.assign(new DeliverySearch(), values)));
      assert.deepEqual(await find({ from: "2026-01-01", keyword: "P" }), ["D1", "D2", "D3", "D5"]);
      assert.deepEqual(await find({ material: ["M1"], from: "2026-03-01" }), ["D5"]);
      for (const wildcard of ["%", "_", "P%", "!"]) assert.deepEqual(await find({ keyword: wildcard }), [], wildcard);
    });

    it("原生 SQL 片段在实库上可执行", async () => {
      const rows = await live().db.list(nativeSql());
      assert.deepEqual(
        rows.map((r) => `${r.material}:${r.padded}:${Number(r.total)}`),
        ["M1:00000000M1:106", "M2:00000000M2:12"],
      );
    });

    it("排除分组维度后按排序串汇总", async () => {
      const rows = await live().db.list(dynamicColumns());
      assert.deepEqual(
        rows.map((r) => `${r.material}:${r.unit}:${r.partNumber}:${Number(r.total)}`),
        ["M2:null:null:12", "M1:null:null:6"],
      );
    });

    it("日期部分与日期加减在三个库上结果相同", async () => {
      const days = (Date.UTC(2024, 3, 30) - Date.UTC(2011, 11, 30)) / 86_400_000;
      const rows = await live().db.list(dateFunctions());
      assert.deepEqual(
        rows.map((r) => `${r.id}:${r.y}:${r.m}:${r.d}:${r.plusDays}:${r.minusMonth}:${r.plusYear}:${r.days}`),
        ["1:2024:3:31:2024-04-03:2024-02-29:2025-03-31:30", `2:2011:12:30:2012-01-02:2011-11-30:2012-12-30:${days}`],
      );
    });

    it("时间部分与加减小时分钟秒和月初在三个库上结果相同", async () => {
      const rows = await live().db.list(timeFunctions());
      assert.deepEqual(
        rows.map((r) => `${r.id}:${r.h}:${r.mi}:${r.s}:${r.plusHours}:${r.minusMinutes}:${r.plusSeconds}:${r.monthStart}`),
        ["1:2:30:0:2024-04-01 03:30:00:2024-03-31 01:59:00:2024-03-31 02:30:59:2024-03-01", "2:0:0:0:2011-12-31 01:00:00:2011-12-29 23:29:00:2011-12-30 00:00:59:2011-12-01"],
      );
    });

    it("补齐、余数、乘方、开方与符号在三个库上结果相同", async () => {
      const r = await live().db.one(numberTextFunctions());
      assert.equal(r.padded, "000M1");
      assert.equal(r.unit, "EA**");
      assert.equal(Number(r.remainder), 1);
      assert.ok(Math.abs(r.squared! - 9) < 1e-9, String(r.squared));
      assert.ok(Math.abs(r.root! - Math.sqrt(3)) < 1e-9, String(r.root));
      assert.equal(r.sign, -1);
    });

    it("条件聚合只聚合满足条件的行且文本按指定顺序拼接", async () => {
      const rows = await live().db.list(
        from(Lips)
          .groupBy((l) => l.material)
          .orderBy((l) => l.material)
          .select((l) => ({
            material: l.material,
            recent: sum(l.quantity).filter(l.createdOn.ge("2026-01-01")),
            big: count().filter(l.quantity.gt("2")),
            deliveries: stringAgg(l.delivery, ",", l.delivery.desc()),
          })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.material}:${Number(r.recent)}:${r.big}:${r.deliveries}`),
        ["M1:6:1:D5,D2,D1", "M2:5:2:D4,D3"],
      );
    });

    it("窗口条件取每组最新的两行", async () => {
      const rows = await live().db.list(qualify());
      assert.deepEqual(
        rows.map((r) => `${r.material}:${r.delivery}`),
        ["M1:D5", "M1:D2", "M2:D3", "M2:D4"],
      );
    });

    it("窗口里的首行、末行、上一行与分组号", async () => {
      const rows = await live().db.list(windowValues());
      assert.deepEqual(
        rows.map((r) => `${r.delivery}:${Number(r.first)}:${Number(r.last)}:${Number(r.previous)}:${r.half}`),
        ["D1:3:1:0:1", "D2:3:1:3:1", "D3:5:7:0:1", "D4:5:7:5:2", "D5:3:1:2:2"],
      );
    });

    it("一对多上的去重计数与按顺序拼接", async () => {
      const rows = await live().db.list(manyText());
      assert.deepEqual(
        rows.map((r) => `${r.material}:${r.orders}:${r.deliveries}`),
        ["M1:3:D1,D2,D5", "M2:1:D3,D4", "M3:0:null", "M4:0:null"],
      );
    });

    it("已知总数的分页不再执行 COUNT", async () => {
      const page = await live().db.page(from(Lips).orderBy((l) => l.delivery).select((l) => l.delivery), 2, 2, 5);
      assert.deepEqual([page.rows, page.total, page.totalPages], [["D3", "D4"], 5, 3]);
    });

    it("流程引擎的待办包括自己办理的和自己或所在组是候选的任务", async () => {
      const { db } = live();
      const ids = (rows: readonly { id: string; process: string | null; businessKey: string | null }[]) => rows.map((r) => `${r.id}/${r.process}/${r.businessKey}`);
      assert.deepEqual(ids(await db.list(wf.todo("alice", ["managers"], new wf.TodoSearch()))), ["T2/leave/BK-2", "T1/leave/BK-1"]);
      assert.deepEqual(ids(await db.list(wf.todo("bob", ["finance"], new wf.TodoSearch()))), ["T3/leave/BK-2", "T1/leave/BK-1"]);
      const search = Object.assign(new wf.TodoSearch(), { process: "leave", name: "app", createdFrom: "2026-01-11 00:00:00" });
      assert.deepEqual(ids(await db.list(wf.todo("alice", ["managers"], search))), ["T2/leave/BK-2"]);
      const page = await db.page(wf.todo("bob", ["finance"], new wf.TodoSearch()), 1, 1);
      assert.deepEqual([page.total, page.rows.length], [2, 1]);
    });

    it("流程引擎按变量筛选实例的三种写法结果相同", async () => {
      const { db } = live();
      for (const q of [wf.instancesByVariables(), wf.instancesByVariablesGrouped(), wf.instancesByVariablesJoined()]) assert.deepEqual(await db.list(q), ["P1"]);
    });

    it("流程定义的最新版本两种写法结果相同", async () => {
      const { db } = live();
      assert.deepEqual(await db.list(wf.latestDefinitions()), ["D3", "D2"]);
      assert.deepEqual(await db.list(wf.latestDefinitionsRanked()), ["D3", "D2"]);
    });

    it("流程引擎的已办与统计", async () => {
      const { db } = live();
      assert.deepEqual((await db.list(wf.done("alice"))).map((r) => `${r.id}:${r.name}:${r.duration}`), ["H2:pay:5400000", "H1:approve:3600000"]);
      assert.deepEqual(
        (await db.list(wf.definitionStatistics())).map(
          (r) => `${r.id}:${r.defKey}:${r.version}:${r.running}:${r.finished}:${r.avgDuration === null ? null : Number(r.avgDuration)}`,
        ),
        ["D1:leave:1:0:1:3600000", "D2:leave:2:2:0:null", "D3:expense:1:0:1:7200000"],
      );
      assert.deepEqual((await db.list(wf.elapsed())).map((r) => `${r.id}:${r.seconds}`), ["H1:518400", "H2:90000", "H3:600", "H4:null"]);
      assert.deepEqual(
        (await db.list(wf.workload())).map((r) => `${r.assignee}:${r.tasks}:${r.totalDuration}:${r.slow}`),
        ["alice:2:9000000:1", "bob:1:600000:0"],
      );
    });

    it("插入、更新、删除在实库上按期望的行数执行", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Note).all());
      assert.equal(await db.execute(writes.insertNotes()), 2);
      assert.equal(await db.execute(writes.closeOpenNote("N1")), 1);
      const stale = await db.execute(writes.closeOpenNote("N1")).then(() => undefined, (e: unknown) => e);
      assert.ok(stale instanceof UnexpectedRowCountError && stale.actual === 0, String(stale));
      const rows = await db.list(
        from(writes.Note)
          .orderBy((n) => n.id)
          .select((n) => ({ id: n.id, title: n.title, qty: n.qty, status: n.status })),
      );
      assert.deepEqual(rows.map((r) => `${r.id}:${r.title}:${r.qty}:${r.status}`), ["N1:first:11:closed", "N2:null:2:open"]);
      assert.equal(await db.execute(writes.copyNote()), 1);
      assert.equal(await db.execute(writes.deleteClosed()), 2);
      assert.deepEqual(await writes.ids(db), ["N2"]);
    });

    it("按主键更新和删除时版本不对就报乐观锁冲突", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Doc).all());
      await db.execute(insertInto(writes.Doc).values({ id: "D1", title: "a", version: 1 }));
      assert.equal(await db.execute(writes.renameDoc({ id: "D1", title: "b", version: 1 })), 1);
      const rows = await db.list(from(writes.Doc).select((d) => ({ title: d.title, version: d.version })));
      assert.deepEqual(rows.map((r) => `${r.title}:${r.version}`), ["b:2"]);
      await assert.rejects(db.execute(writes.renameDoc({ id: "D1", title: "c", version: 1 })), UnexpectedRowCountError);
      await assert.rejects(db.execute(writes.deleteDoc({ id: "D1", version: 1 })), UnexpectedRowCountError);
      assert.equal(await db.execute(writes.deleteDoc({ id: "D1", version: 2 })), 1);
    });

    it("具名过滤器与 schema 映射在实库上生效", async () => {
      const scoped = target.database(live().driver, { filters: [filter("tenant", writes.Tenanted, (t) => t.tenant.eq("T9"))], schemas: { APP: "QUERY_IT" } });
      await scoped.execute(deleteFrom(writes.Note).unfiltered().all());
      await scoped.execute(writes.insertNotes());
      assert.deepEqual(await scoped.list(from(writes.Note).select((n) => n.id)), [], "the tenant filter hides tenant T1");
      assert.deepEqual(
        await scoped.list(
          from(writes.Note)
            .unfiltered("tenant")
            .orderBy((n) => n.id)
            .select((n) => n.id),
        ),
        ["N1", "N2"],
      );
      assert.deepEqual(
        await scoped.list(
          from(writes.AppNote)
            .orderBy((n) => n.id)
            .select((n) => n.id),
        ),
        ["N1", "N2"],
        "APP maps to QUERY_IT",
      );
    });

    it("逻辑删除记毫秒数时同一个编码删除后可以再建", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Code).physically().unfiltered().all());
      await db.execute(insertInto(writes.Code).values({ id: "C1", code: "A" }));
      await assert.rejects(db.execute(insertInto(writes.Code).values({ id: "C2", code: "A" })), QueryExecutionError, "the unique key stops a second live row with the same code");
      assert.equal(await db.execute(deleteFrom(writes.Code).where((c) => c.code.eq("A"))), 1);
      await db.execute(insertInto(writes.Code).values({ id: "C2", code: "A" }));
      assert.deepEqual(await db.list(from(writes.Code).select((c) => c.id)), ["C2"]);
      assert.equal(await db.count(from(writes.Code).withDeleted().select((c) => c.id)), 2);
    });

    it("差异更新只写变了的列，只读列保留数据库默认值", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Doc).all());
      await db.execute(insertInto(writes.Doc).values({ id: "D1", title: "a", version: 1, source: "app" }));
      assert.equal(
        await db.execute(update(writes.Doc).byId({ id: "D1", title: "a", version: 1 }).diffFrom({ id: "D1", title: "a", version: 1 })),
        0,
        "nothing changed, so nothing runs and the version stays",
      );
      assert.equal(
        await db.execute(update(writes.Doc).byId({ id: "D1", title: "b", version: 1, source: "app" }).diffFrom({ id: "D1", title: "a", version: 1, source: "db" })),
        1,
      );
      const rows = await db.list(from(writes.Doc).select((d) => ({ title: d.title, version: d.version, source: d.source })));
      assert.deepEqual(rows.map((r) => `${r.title}:${r.version}:${r.source}`), ["b:2:db"]);
      assert.equal(
        await db.exists(
          from(writes.Doc)
            .where((d) => d.id.eq("D1"))
            .select((d) => d.id),
        ),
        true,
      );
      assert.equal(
        await db.exists(
          from(writes.Doc)
            .where((d) => d.id.eq("D9"))
            .select((d) => d.id),
        ),
        false,
      );
    });

    it("插入后按行的顺序取回数据库生成的主键", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Seq).all());
      const keys = await db.insertKeys<number>(insertInto(writes.Seq).values({ name: "a" }, { name: "b" }));
      assert.equal(keys.length, 2);
      assert.equal(keys[1], keys[0]! + 1);
      const rows = await db.list(
        from(writes.Seq)
          .orderBy((s) => s.id)
          .select((s) => ({ id: s.id, name: s.name })),
      );
      assert.deepEqual(rows.map((r) => `${r.id}:${r.name}`), [`${keys[0]}:a`, `${keys[1]}:b`]);
    });

    it("插入或更新在冲突时更新指定的列或保留原来的行", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Doc).all());
      await db.execute(writes.upsertDoc({ id: "D1", title: "a", version: 1 }));
      await db.execute(writes.upsertDoc({ id: "D1", title: "b", version: 9 }));
      await db.execute(writes.insertDocIfAbsent({ id: "D1", title: "c", version: 5 }));
      await db.execute(writes.insertDocIfAbsent({ id: "D2", title: "d", version: 1 }));
      const rows = await db.list(
        from(writes.Doc)
          .orderBy((d) => d.id)
          .select((d) => ({ id: d.id, title: d.title, version: d.version })),
      );
      assert.deepEqual(rows.map((r) => `${r.id}:${r.title}:${r.version}`), ["D1:b:1", "D2:d:1"]);
    });

    it("在事务里加锁领取最早的待认领任务", async () => {
      const { db } = live();
      assert.deepEqual(await db.transaction(() => db.list(wf.claimable())), ["T1"]);
      const locked = await db.transaction(() =>
        db.list(
          from(wf.Task)
            .where((t) => t.assignee.isNull())
            .orderBy((t) => t.id)
            .with(forUpdate("nowait"))
            .select((t) => t.id),
        ),
      );
      assert.deepEqual(locked, ["T1", "T3"]);
    });

    it("更新和删除的条件经过导航关系时只改到关联得上的行", async () => {
      const { db } = live();
      // 在事务里执行再回滚：PostgreSQL 的 UPDATE 即使值不变也写出新的行版本，会打乱别的测试依赖的扫描顺序
      await assert.rejects(
        db.transaction(async () => {
          // 影响的行数是匹配的行数；没有物料描述的 M2 只有 D3、D4
          assert.equal(await db.execute(update(Lips).set(() => ({ unit: "EA" })).where((l) => l.text.text.isNull())), 2);
          assert.equal(await db.execute(update(Lips).set(() => ({ unit: "EA" })).where((l) => l.mara.manufacturer.eq("MK1"))), 3);
          assert.equal(await db.execute(deleteFrom(Lips).where((l) => l.mara.materialType.eq("NONE"))), 0);
          throw new Error("roll back");
        }),
        /roll back/,
      );
    });

    it("逻辑删除与审计填充在实库上按预期写入和过滤", async () => {
      const audited = target.database(live().driver, { fills: writes.audit });
      await audited.execute(deleteFrom(writes.Ticket).physically().unfiltered().all());
      await audited.execute(insertInto(writes.Ticket).values({ id: "T1", title: "a" }, { id: "T2", title: "b" }));
      await audited.execute(
        update(writes.Ticket)
          .set(() => ({ title: "b2" }))
          .where((t) => t.id.eq("T2")),
      );
      assert.equal(await audited.execute(writes.deleteTicket("T1")), 1);
      assert.equal(await audited.execute(writes.deleteTicket("T1")), 0, "a deleted row is no longer visible");
      await audited.execute(insertInto(writes.Ticket).values({ id: "T3", title: "c" }).onConflict().update());
      await audited.execute(insertInto(writes.Ticket).values({ id: "T3", title: "c2" }).onConflict().update());
      assert.deepEqual(
        await audited.list(
          from(writes.Ticket)
            .orderBy((t) => t.id)
            .select((t) => t.id),
        ),
        ["T2", "T3"],
      );
      const rows = await audited.list(
        from(writes.Ticket)
          .unfiltered()
          .orderBy((t) => t.id)
          .select((t) => ({ id: t.id, title: t.title, deleted: t.deleted, createdAt: t.createdAt, updatedBy: t.updatedBy })),
      );
      assert.deepEqual(
        rows.map((r) => `${r.id}:${r.title}:${r.deleted}:${r.createdAt}:${r.updatedBy}`),
        ["T1:a:1:2026-01-01 08:00:00:bob", "T2:b2:0:2026-01-01 08:00:00:bob", "T3:c2:0:2026-01-01 08:00:00:bob"],
      );
      assert.equal(
        await audited.execute(
          deleteFrom(writes.Ticket)
            .physically()
            .unfiltered()
            .where((t) => t.id.eq("T1")),
        ),
        1,
      );
    });

    it("事务里出错时写入整体回滚", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Note).all());
      await assert.rejects(
        db.transaction(async () => {
          await db.execute(writes.insertNotes());
          assert.deepEqual(await writes.ids(db), ["N1", "N2"], "the transaction sees its own rows");
          throw new Error("roll back");
        }),
        /roll back/,
      );
      assert.deepEqual(await writes.ids(db), []);
      await db.transaction(() => db.execute(writes.insertNotes()));
      assert.deepEqual(await writes.ids(db), ["N1", "N2"]);
    });

    it("多行插入在一个事务里执行", async () => {
      const { db } = live();
      await db.execute(deleteFrom(writes.Note).all());
      const rows = Array.from({ length: 120 }, (_, i) => ({ id: `B${i}`, tenant: "T1", title: "bulk", qty: i, status: "open" }));
      assert.equal(await db.execute(insertInto(writes.Note).values(...rows)), 120);
      assert.equal(await db.count(from(writes.Note).select((n) => n.id)), 120);
      assert.equal(await db.execute(deleteFrom(writes.Note).where((n) => n.title.eq("bulk"))), 120);
    });

    it("右侧的 UNION 保持原有分组", async () => {
      assert.deepEqual((await live().db.list(unionGrouping())).sort(), ["M1", "M1", "M2"]);
    });

    it("带引号、反斜杠和注释符的文本作为字面量和参数都原样往返", async () => {
      const { db } = live();
      const texts = cte("texts", values(HOSTILE.map((value, ord) => ({ value, ord }))));
      assert.deepEqual(await db.list(from(texts).orderBy((t) => t.ord).select((t) => t.value)), HOSTILE, "values(...) writes every row as literals");
      for (const text of HOSTILE) {
        assert.equal(await db.one(from(Lips).limit(1).select(() => val(text))), text, "a SELECT-list constant is written as a literal");
        assert.deepEqual(await db.list(from(texts).where((t) => t.value.eq(text)).select((t) => t.value)), [text], "a WHERE value is bound as a parameter");
      }
    });

    it("超过超时的语句在数据库端被取消且连接仍可用", async () => {
      const { db, driver } = live();
      const start = Date.now();
      const timedOut = (e: unknown) => e instanceof QueryExecutionError && e.timedOut;
      await assert.rejects(db.sql(target.slowStatement).timeout(1000).list(), timedOut);
      const limited = target.database(driver, { queryTimeoutMs: 1000 });
      await assert.rejects(limited.sql(target.slowStatement).list(), timedOut);
      await assert.rejects(db.sql(`SELECT * FROM ${live().table("NO_SUCH_TABLE")}`).list(), (e: unknown) => e instanceof QueryExecutionError && !e.timedOut);
      const elapsed = Date.now() - start;
      assert.ok(elapsed < 5000, `both statements were cancelled after about a second instead of running to the end: ${elapsed} ms`);
      assert.equal(await limited.one(from(Lips).where((l) => l.client.eq("100")).select(() => count())), 5);
      const streamed: string[] = [];
      for await (const d of limited.stream(from(Lips).where((l) => l.client.eq("100")).select((l) => l.delivery).timeout(5000))) streamed.push(d);
      assert.equal(streamed.length, 5, "a stream applies its timeout and still reads every row");
    });

    extra?.(live);
  });
}

import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import {
  caseWhen,
  count,
  countDistinct,
  cte,
  filter,
  from,
  inMemoryCache,
  left,
  negate,
  plus,
  replace,
  right,
  stringAgg,
  substring,
  sum,
  val,
  values,
  QueryExecutionError,
  type Database,
  type DatabaseOptions,
  type Driver,
  type QueryEvent,
  type Ref,
} from "@zerodep-dev/query-core";
import { ClientEntity, Event, LineVO, Lips, Mara, MaraView } from "../fixtures/erp.js";
import { dateFunctions, DeliverySearch, groupJoin, filterMany, MaterialSearch, manyText, qualify, windowValues, dynamicColumns, filterObject, floorCeil, nativeSql, reservedAlias, rollup, simpleCase, textPositions, unionGrouping } from "../fixtures/spec-cases.js";
import { deliveries, latestDeliveries, lines, partTotals, stock } from "../fixtures/scenarios.js";

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
      const statements = [
        `-CREATE SCHEMA ${q("QUERY_IT")}`,
        ...["MARA", "LIPS", "MAKT", "VBKD", "MARDH", "MCHBH", "EVENTS"].map((name) => `-DROP TABLE ${t(name)}`),
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

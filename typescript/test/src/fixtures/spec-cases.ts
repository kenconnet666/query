import { Decimal } from "decimal.js";
import { CalendarDate, parseDate, parseDateTime } from "@internationalized/date";
import {
  all,
  any,
  Bound,
  caseOf,
  caseWhen,
  CastType,
  count,
  cte,
  date,
  decimal,
  identifier,
  denseRank,
  exists,
  from,
  grouping,
  lag,
  lead,
  lit,
  not,
  notExists,
  rank,
  rowNumber,
  scalar,
  sql,
  sqlCond,
  sqlTable,
  text,
  timestamp,
  val,
  where,
  values,
  type Query,
  type Ref,
} from "@zerodep-dev/query-core";
import { firstValue, lastValue, ntile } from "@zerodep-dev/query-core";
import { cube as hanaCube, greatest as hanaGreatest, groupingSets as hanaGroupingSets, hint, least as hanaLeast, hana } from "@zerodep-dev/query-hana";
import { greatest as mysqlGreatest, least as mysqlLeast, maxExecutionTime, mysql } from "@zerodep-dev/query-mysql";
import { cube as pgCube, distinctOn, greatest as pgGreatest, groupingSets as pgGroupingSets, least as pgLeast, postgres } from "@zerodep-dev/query-postgres";
import * as pgx from "@zerodep-dev/query-postgres";
import * as mysqlx from "@zerodep-dev/query-mysql";
import * as hanax from "@zerodep-dev/query-hana";
import { percentileCont as sqlitePercentileCont, percentileDisc as sqlitePercentileDisc, sqlite } from "@zerodep-dev/query-sqlite";
import { Article, LipsByUnit, orgSource, SalesView, Tagged } from "./dialect-tables.js";
import { Event, Lips, Makt, Mara, MaraView, Order, Vbkd } from "./erp.js";
import { deliveries, latestDeliveries, lines, stock } from "./scenarios.js";
import * as wf from "./workflow.js";
import * as writes from "./writes.js";
import { ShEvent, ShOrder } from "./shards.js";
import { deleteFrom, insertInto, update, type Write } from "@zerodep-dev/query-core";
import { fromAsOf, inventory } from "@zerodep-dev/query-polardb";
import { allOf, anyOf, bool, cumeDist, keys, nthValue, percentRank } from "@zerodep-dev/query-core";

/**
 * The golden-SQL cases of spec/render, built the TypeScript way. The Java tests build the same
 * cases (SpecCases.java and the dialect modules' spec tests); both must render every
 * spec/render/<case>.<dialect>.sql file exactly.
 */
export interface SpecCase {
  /** Rendered by a database instance with the client filter of the live scenarios. */
  readonly filtered: boolean;
  /** A query, or a write that renders one statement. */
  readonly query: () => Query<unknown> | Write;
}

const plain = (query: () => Query<unknown> | Write): SpecCase => ({ filtered: false, query });
const DAY = new CalendarDate(2026, 1, 31);

export const sharedCases: Readonly<Record<string, SpecCase>> = {
  deliveries: plain(() => deliveries(parseDate("2026-01-01"), parseDate("2026-06-30"), null)),
  "deliveries-filtered": { filtered: true, query: () => deliveries(parseDate("2026-01-01"), parseDate("2026-06-30"), "MK1") },
  "lines-filtered": { filtered: true, query: () => lines() },
  stock: plain(() => stock("2026")),
  "latest-deliveries": plain(() => latestDeliveries()),
  "self-join": plain(() =>
    from(Mara)
      .join(Mara, (a, b) => b.partNumber.eq(a.partNumber))
      .where((a, b) => exists(from(Mara).where((c) => c.material.eq(b.material))))
      .select((a) => a.material),
  ),
  paging: plain(() =>
    from(Mara)
      .orderBy((m) => [m.material.asc().nullsLast(), m.partNumber.desc().nullsFirst()])
      .select((m) => m.material)
      .limit(10)
      .offset(20),
  ),
  "offset-only": plain(() =>
    from(Mara)
      .orderBy((m) => m.material.asc())
      .select((m) => m.material)
      .offset(5),
  ),
  conditions: plain(() =>
    from(Mara)
      .skipEmpty()
      .where((m) => {
        any(() => {
          m.material.eq("A");
          all(() => {
            m.partNumber.ne("B");
            m.manufacturer.isNull();
          });
        });
        not(() => m.materialType.in(["ZMAT", "ZOTH"]));
        m.material.notIn(["X"]);
        m.partNumber.like("P%");
        m.manufacturer.notLike("%X");
        m.material.between("A", "Z");
        m.manufacturer.isNotNull();
        m.material.gt("0");
        m.material.ge("1");
        m.material.lt("9");
        m.material.le("8");
        m.material.eq(null);
        m.material.like(null);
        m.material.between("A", null);
      })
      .select((m) => m.material),
  ),
  "case-template": plain(() =>
    from(Mara).select((m) =>
      caseWhen(() => m.materialType.eq("ZMAT"), "material")
        .when(() => m.materialType.eq("ZOTH"), "other")
        .otherwise(sql(text)`LOWER(${m.materialType})`),
    ),
  ),
  literals: plain(() =>
    from(Mara).select(() => ({
      quote: lit("O'Brien"),
      unicode: lit("Größe"),
      backslash: lit("a\\b"),
      day: lit(DAY),
      moment: lit(parseDateTime("2026-01-31T08:15:30")),
      integer: lit(42),
      decimal: lit(new Decimal("12.5")),
      flag: lit(true),
    })),
  ),
  functions: plain(() =>
    from(Lips).select((l) => ({
      upper: l.material.upper(),
      lower: l.unit.lower(),
      trimmed: l.delivery.trim(),
      joined: l.material.concat(val("-"), l.unit),
      fallback: l.unit.coalesce("EA"),
      emptyUnit: l.unit.nullif(""),
      total: l.quantity.plus(l.quantity),
      difference: l.quantity.minus(val(1)),
      product: l.quantity.times(val(2)),
      ratio: l.quantity.divide(val(3)),
      magnitude: l.quantity.abs(),
      rounded: l.quantity.round(1),
      asText: l.quantity.cast(CastType.text(20)),
      asDecimal: l.material.cast(CastType.decimal(15, 3)),
      asInteger: l.quantity.cast(CastType.integer()),
      asDouble: l.quantity.cast(CastType.double()),
      asDate: l.salesOrder.cast(CastType.date()),
      asTimestamp: l.salesOrder.cast(CastType.timestamp()),
    })),
  ),
  aggregates: plain(() =>
    from(Lips)
      .groupBy((l) => l.material)
      .having((l) => count().gt(1))
      .select((l) => ({
        material: l.material,
        lines: count(),
        orders: l.salesOrder.countDistinct(),
        average: l.quantity.avg(),
        smallest: l.quantity.min(),
        largest: l.quantity.max(),
        total: l.quantity.sum(),
      })),
  ),
  windows: plain(() =>
    from(Lips).select((l) => ({
      delivery: l.delivery,
      position: rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.delivery.asc())),
      ranking: rank().over((w) => w.orderBy(l.quantity.desc())),
      dense: denseRank().over((w) => w.orderBy(l.quantity.desc())),
      previous: lag(l.delivery, 1).over((w) => w.orderBy(l.delivery.asc())),
      next: lead(l.delivery, 2).over((w) => w.orderBy(l.delivery.asc())),
      running: l.quantity.sum().over((w) => w.partitionBy(l.material).orderBy(l.delivery.asc()).rows(Bound.unboundedPreceding(), Bound.currentRow())),
      moving: l.quantity.avg().over((w) => w.orderBy(l.delivery.asc()).rows(Bound.preceding(2), Bound.following(1))),
    })),
  ),
  values: plain(() => {
    const categories = cte(
      "category_def",
      values([
        { zfl: "1", name: "自制件", sortNo: 1 },
        { zfl: "2", name: "供应商件", sortNo: 2 },
      ]),
    );
    return from(categories)
      .orderBy((c) => c.sortNo)
      .select((c) => ({ zfl: c.zfl, name: c.name }));
  }),
  "text-functions": plain(() =>
    from(Lips).select((l) => ({
      prefix: l.delivery.left(2),
      suffix: l.delivery.right(3),
      middle: l.material.substring(2, 3),
      tail: l.material.substring(2),
      cleaned: l.unit.replace("-", ""),
      negative: l.quantity.negate(),
      periods: l.quantity.plus(l.quantity.coalesce(new Decimal(0))),
    })),
  ),
  "grouped-constants": plain(() => {
    const bucket = (l: Ref<Lips>) => caseWhen(() => l.quantity.le(new Decimal(2)), "small").when(() => l.quantity.le(new Decimal(5)), "medium").otherwise("large");
    return from(Lips)
      .where((l) => l.unit.eq("EA"))
      .groupBy((l) => [bucket(l), l.delivery.substring(1, 1)])
      .having(() => count().gt(1))
      .orderBy((l) => bucket(l))
      .select((l) => ({
        bucket: bucket(l),
        prefix: l.delivery.substring(1, 1),
        lines: count(),
        total: caseWhen(() => l.unit.eq("EA"), l.quantity).otherwise(new Decimal(0)).sum(),
      }));
  }),
  "column-transforms": plain(() =>
    from(MaraView)
      .where((m) => {
        m.maker.isNotNull();
        m.storedMaker.ne("");
      })
      .groupBy((m) => m.partNumber)
      .select((m) => ({ partNumber: m.partNumber, makers: m.maker.countDistinct() })),
  ),
  rollup: plain(() => rollup()),
  "union-grouping": plain(() => unionGrouping()),
  "text-positions": plain(() => textPositions()),
  "simple-case": plain(() => simpleCase()),
  "floor-ceil": plain(() => floorCeil()),
  "reserved-alias": plain(() => reservedAlias()),
  "dynamic-columns": plain(() => dynamicColumns()),
  "filter-object": plain(() => filterObject(Object.assign(new DeliverySearch(), { material: ["M1", "M2"], from: "2026-01-01", keyword: "50%", unit: "KG" }))),
  "native-sql": plain(() => nativeSql()),
  "filter-many": { filtered: true, query: () => filterMany(Object.assign(new MaterialSearch(), { unit: "EA", minQuantity: "5", materialType: "ZMAT" })) },
  navigation: {
    filtered: true,
    query: () =>
      from(Lips)
        .where((l) => l.mara.manufacturer.eq("MK1"))
        .orderBy((l) => l.delivery)
        .select((l) => ({ delivery: l.delivery, partNumber: l.mara.partNumber, text: l.mara.text.text })),
  },
  "one-to-many": {
    filtered: true,
    query: () =>
      from(Mara)
        .where((m) => {
          m.materialType.eq("ZMAT");
          m.deliveries.any((l) => l.unit.eq("EA"));
        })
        .orderBy((m) => m.material)
        .select((m) => ({
          material: m.material,
          lines: m.deliveries.count(),
          total: m.deliveries.sum((l) => l.quantity),
          latest: m.deliveries.first((l) => l.createdOn.desc()).delivery,
        })),
  },
  "date-functions": plain(() => dateFunctions()),
  "time-functions": plain(() => timeFunctions()),
  "number-text-functions": plain(() => numberTextFunctions()),
  "conditional-aggregates": plain(() => conditionalAggregates()),
  qualify: plain(() => qualify()),
  "window-values": plain(() => windowValues()),
  "many-text": { filtered: true, query: () => manyText() },
  "set-operations": plain(() => setOperations()),
  "subquery-comparisons": { filtered: true, query: () => subqueryComparisons() },
  statistics: plain(() => statistics()),
  "distribution-windows": plain(() => distributionWindows()),
  "shard-month-range": plain(() =>
    from(ShEvent)
      .where((e) => e.day.range(parseDate("2026-01-15"), parseDate("2026-03-01")))
      .groupBy((e) => e.note)
      .select((e) => ({ note: e.note, total: e.amount.sum() })),
  ),
  "shard-single": plain(() =>
    from(ShEvent)
      .where((e) => {
        e.day.eq(parseDate("2026-02-10"));
        e.note.like("a%");
      })
      .select((e) => e.id),
  ),
  "shard-modulo-in": plain(() =>
    from(ShOrder)
      .where((o) => any(() => {
        o.customer.in([1, 3]);
        o.customer.eq(5);
      }))
      .select((o) => ({ customer: o.customer, total: o.total })),
  ),
  "shard-all": plain(() => from(ShOrder).allShards().select(() => count())),
  "shard-update": plain(() =>
    update(ShEvent)
      .set(() => ({ note: "x" }))
      .where((e) => e.day.between(parseDate("2026-03-01"), parseDate("2026-03-31"))),
  ),
  "group-join": { filtered: true, query: () => groupJoin() },
  "workflow-todo": plain(() => wf.todo("alice", ["managers", "leads"], Object.assign(new wf.TodoSearch(), { process: "leave", name: "app" }))),
  "workflow-variables": plain(() => wf.instancesByVariables()),
  "workflow-variables-grouped": plain(() => wf.instancesByVariablesGrouped()),
  "workflow-variables-joined": plain(() => wf.instancesByVariablesJoined()),
  "workflow-latest": plain(() => wf.latestDefinitions()),
  "workflow-latest-ranked": plain(() => wf.latestDefinitionsRanked()),
  "workflow-statistics": plain(() => wf.definitionStatistics()),
  "workflow-workload": plain(() => wf.workload()),
  "workflow-elapsed": plain(() => wf.elapsed()),
  "write-insert": plain(() => insertInto(writes.Note).values({ id: "N1", tenant: "T1", title: "first", qty: 1, status: "open" })),
  "write-insert-select": plain(() => writes.copyNote()),
  "write-update": plain(() => writes.closeOpenNote("N1")),
  "write-join-update": plain(() => writes.syncNoteTitles()),
  "write-join-delete": plain(() => writes.deleteNotesOfDocs("gone")),
  "write-join-logic-delete": plain(() => writes.deleteTicketsOfDocs("gone")),
  "write-navigation": {
    filtered: true,
    query: () =>
      update(Lips)
        .set(() => ({ unit: "EA" }))
        .where((l) => l.text.text.isNull()),
  },
  "write-delete": plain(() => writes.deleteClosed()),
  "write-update-by-id": plain(() => writes.renameDoc({ id: "D1", title: "new", version: 3 })),
  "write-delete-by-id": plain(() => writes.deleteDoc({ id: "D1", title: null, version: 3 })),
  "write-upsert": plain(() => writes.upsertDoc({ id: "D1", title: "new", version: 1 })),
  "write-upsert-nothing": plain(() => writes.insertDocIfAbsent({ id: "D1", title: "new", version: 1 })),
  "workflow-claim": plain(() => wf.claimable()),
  "write-logic-delete": plain(() => writes.deleteTicket("T1")),
  "logic-delete-query": plain(() =>
    from(writes.Ticket)
      .orderBy((t) => t.id)
      .select((t) => t.id),
  ),
  "write-insert-generated": plain(() => insertInto(writes.Seq).values({ name: "a" })),
  subqueries: plain(() => {
    const parts = from(Mara)
      .where((m) => m.materialType.eq("ZMAT"))
      .select((m) => m.material);
    const open = from(Lips).select((l) => ({ delivery: l.delivery, material: l.material }));
    return from(open.asTable())
      .where((o) => {
        o.material.in(parts);
        notExists(from(Vbkd).where((v) => v.salesOrder.eq(o.delivery)));
      })
      .select((o) => ({
        delivery: o.delivery,
        text: scalar(
          from(Makt)
            .where((mk) => mk.material.eq(o.material))
            .select((mk) => mk.text.max()),
        ),
      }));
  }),
};

/** Totals per material and unit, subtotals per material, and the grand total. */
/** 同一个一对多关系上的存在判断、行数、合计和带筛选的行数，合成一个按连接键分组的派生表。 */
export function groupJoin() {
  return from(Mara)
    .groupJoin((m) => m.deliveries)
    .where((m) => m.deliveries.any((l) => l.unit.eq("EA")))
    .orderBy((m) => m.material)
    .select((m) => ({
      material: m.material,
      lines: m.deliveries.count(),
      total: m.deliveries.sum((l) => l.quantity),
      recent: m.deliveries.where((l) => l.createdOn.ge(parseDate("2026-01-01"))).count(),
    }));
}

/** 日期部分、日期加减和相隔天数，按半开区间筛选。 */
/** 时间部分、加减小时分钟秒与月初。 */
export function timeFunctions() {
  return from(Event)
    .orderBy((e) => e.id)
    .select((e) => ({
      id: e.id,
      h: e.atTime.hour(),
      mi: e.atTime.minute(),
      s: e.atTime.second(),
      plusHours: e.atTime.addHours(25),
      minusMinutes: e.atTime.addMinutes(-31),
      plusSeconds: e.atTime.addSeconds(59),
      monthStart: e.onDay.monthStart(),
    }));
}

/** 补齐、余数、乘方、开方与符号。 */
export function numberTextFunctions() {
  return from(Lips)
    .where((l) => l.delivery.eq("D1"))
    .select((l) => ({
      padded: l.material.lpad(5, "0"),
      unit: l.unit.rpad(4, "*"),
      remainder: l.quantity.mod(2),
      squared: l.quantity.power(2),
      root: l.quantity.sqrt(),
      sign: l.quantity.minus(lit(new Decimal("4"))).sign(),
    }));
}

export function dateFunctions() {
  return from(Event)
    .where((e) => e.onDay.range(parseDate("2011-01-01"), parseDate("2025-01-01")))
    .orderBy((e) => e.id)
    .select((e) => ({
      id: e.id,
      y: e.onDay.year(),
      m: e.atTime.month(),
      d: e.onDay.day(),
      plusDays: e.onDay.addDays(3),
      minusMonth: e.onDay.addMonths(-1),
      plusYear: e.onDay.addYears(1),
      days: e.onDay.daysBetween(lit(parseDate("2024-04-30"))),
    }));
}

/** 每个物料最新的两张交货单：窗口条件包成派生表，不在结果里的排序列作为额外的列带出来。 */
export function qualify() {
  return from(Lips)
    .qualify((l) => rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc())).le(2))
    .orderBy((l) => [l.material, l.createdOn.desc()])
    .select((l) => ({ material: l.material, delivery: l.delivery }));
}

/** 窗口里的首行、末行（整个分区）、带默认值的上一行和分组号。 */
export function windowValues() {
  return from(Lips)
    .orderBy((l) => l.delivery)
    .select((l) => ({
      delivery: l.delivery,
      first: firstValue(l.quantity).over((w) => w.partitionBy(l.material).orderBy(l.delivery)),
      last: lastValue(l.quantity).over((w) => w.partitionBy(l.material).orderBy(l.delivery).rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())),
      previous: lag(l.quantity, 1, new Decimal(0)).over((w) => w.partitionBy(l.material).orderBy(l.delivery)),
      half: ntile(2).over((w) => w.orderBy(l.delivery)),
    }));
}

/**
 * INTERSECT、EXCEPT 与 UNION 混用：`(交货的物料 ∪ 主数据的物料) ∩ ZMAT 物料 − (X − Y)`，
 * 先算的部分都写出括号，三库读法一致。
 */
export function setOperations() {
  const delivered = from(Lips).select((l) => l.material);
  const master = from(Mara).select((m) => m.material);
  const zmat = from(Mara).where((m) => m.materialType.eq("ZMAT")).select((m) => m.material);
  const x = from(Mara).where((m) => m.manufacturer.eq("X")).select((m) => m.material);
  const y = from(Mara).where((m) => m.manufacturer.eq("Y")).select((m) => m.material);
  return delivered.union(master).intersect(zmat).except(x.except(y));
}

/**
 * RIGHT JOIN 与子查询比较：主数据的过滤条件挪进 RIGHT JOIN 的 ON；多列 IN、ANY、ALL、
 * NULL 也相等的比较，以及条件作为布尔列。
 */
export function subqueryComparisons() {
  return from(Mara)
    .rightJoin(Lips, (m, l) => m.material.eq(l.material))
    .where((m, l) => {
      keys(l.delivery, l.material).in(from(Lips).where((x) => x.unit.eq("EA")).select((x) => ({ delivery: x.delivery, material: x.material })));
      l.quantity.ge(anyOf(from(Lips).where((x) => x.unit.eq("KG")).select((x) => x.quantity)));
      l.quantity.lt(allOf(from(Lips).where((x) => x.unit.eq("PC")).select((x) => x.quantity)));
      not(() => m.manufacturer.isNotDistinctFrom(m.partNumber));
      l.unit.isDistinctFrom(m.materialType);
    })
    .orderBy((m, l) => l.delivery)
    .select((m, l) => ({ delivery: l.delivery, partNumber: m.partNumber, big: bool(() => l.quantity.gt(new Decimal(1))) }));
}

/** 去重的求和与平均、样本与总体的标准差和方差。 */
export function statistics() {
  return from(Lips)
    .groupBy((l) => l.material)
    .orderBy((l) => l.material)
    .select((l) => ({
      material: l.material,
      distinctTotal: l.quantity.sumDistinct(),
      distinctAverage: l.quantity.avgDistinct(),
      sampleDeviation: l.quantity.stddevSamp(),
      populationDeviation: l.quantity.stddevPop(),
      sampleVariance: l.quantity.varSamp(),
      populationVariance: l.quantity.varPop(),
    }));
}

/** 相对排名、累计分布和窗口里的第 n 行。 */
export function distributionWindows() {
  return from(Lips)
    .orderBy((l) => l.delivery)
    .select((l) => ({
      delivery: l.delivery,
      percentRank: percentRank().over((w) => w.orderBy(l.quantity)),
      cumeDist: cumeDist().over((w) => w.partitionBy(l.material).orderBy(l.quantity)),
      second: nthValue(l.delivery, 2).over((w) => w.partitionBy(l.material).orderBy(l.delivery).rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())),
    }));
}

/** 一对多上的去重计数和按顺序拼接的文本。 */
export function manyText() {
  return from(Mara)
    .orderBy((m) => m.material)
    .select((m) => ({
      material: m.material,
      orders: m.deliveries.countDistinct((l) => l.salesOrder),
      deliveries: m.deliveries.orderBy((l) => l.delivery).stringAgg((l) => l.delivery, ","),
    }));
}

/** 条件聚合与文本拼接：每个物料按单位分开合计。 */
export function conditionalAggregates() {
  return from(Lips)
    .groupBy((l) => l.material)
    .orderBy((l) => l.material)
    .select((l) => ({
      material: l.material,
      kg: l.quantity.sum().filter(() => l.unit.eq("KG")),
      eachLines: count().filter(() => l.unit.eq("EA")),
      units: l.unit.countDistinct().filter(() => l.quantity.gt(new Decimal(0))),
      deliveries: l.delivery.stringAgg(",", l.delivery.asc()),
    }));
}

export function rollup() {
  return from(Lips)
    .groupByRollup((l) => [l.material, l.unit])
    .orderBy((l) => [grouping(l.material), l.material, grouping(l.unit)])
    .select((l) => ({ material: l.material, unit: l.unit, total: l.quantity.sum(), level: grouping(l.material) }));
}

/** Cuts each comma-separated code where LOCATE finds the comma: computed SUBSTRING positions. */
export function textPositions() {
  const codes = cte("codes", values([{ text: "21,32" }, { text: "05" }]));
  return from(codes)
    .orderBy((c) => c.text)
    .select((c) => {
      const comma = c.text.locate(",");
      return {
        text: c.text,
        comma,
        head: caseWhen(() => comma.gt(0), c.text.substring(1, comma.minus(lit(1)))).otherwise(c.text),
        tail: caseWhen(() => comma.gt(0), c.text.substring(comma.plus(lit(1)))).end(),
      };
    });
}

/** A code table written inline, and the first present of several values. */
export function simpleCase() {
  return from(Mara)
    .orderBy((m) => m.material)
    .select((m) => ({
      material: m.material,
      kind: caseOf(m.materialType).when("ZMAT", "material").otherwise("other"),
      maker: m.manufacturer.nullif("").coalesce(m.partNumber.nullif(""), val("none")),
    }));
}

/** FLOOR and CEIL of half the delivered quantity. */
export function floorCeil() {
  return from(Lips)
    .orderBy((l) => l.delivery)
    .select((l) => {
      const half = l.quantity.divide(lit(new Decimal("2")));
      return { delivery: l.delivery, down: half.floor(), up: half.ceil() };
    });
}

/** 原生 SQL：标识符按方言加引号的 sqlTable、数组参数展开、混用表达式和值的 sql 与 sqlCond。 */
export function nativeSql() {
  const id = identifier;
  const totals = sqlTable({ material: text, total: decimal })`SELECT ${id("MATNR")} AS ${id("material")}, SUM(${id("LFIMG")}) AS ${id("total")} FROM ${id("QUERY_IT")}.${id("LIPS")} WHERE ${id("VRKME")} IN (${["EA", "KG"]}) GROUP BY ${id("MATNR")}`;
  return from(totals)
    .join(Mara, (t, m) => m.material.eq(t.material))
    .where((_t, m) => sqlCond`${m.materialType} <> ${"ZOTH"}`)
    .orderBy((t) => t.material)
    .select((t, m) => ({ material: t.material, padded: sql(text)`LPAD(${m.material}, ${10}, '0')`, total: t.total }));
}

/** 交货查询的请求参数：每个非空字段是一个条件。 */
export class DeliverySearch {
  material?: string[];
  @where("ge", "createdOn") from?: string;
  @where("lt", "createdOn") to?: string;
  @where("contains", "mara.partNumber", "text.text") keyword?: string;
  @where("ne") unit?: string;
}

/** 物料查询的请求参数：两个字段走同一个一对多关系，合并进同一个 EXISTS。 */
export class MaterialSearch {
  @where("eq", "deliveries.unit") unit?: string;
  @where("ge", "deliveries.quantity") minQuantity?: string;
  materialType?: string;
}

/** 按交货行的条件筛选物料。 */
export function filterMany(search: MaterialSearch) {
  return from(Mara)
    .where(search)
    .orderBy((m) => m.material)
    .select((m) => m.material);
}

/** 按条件对象筛选交货单。 */
export function filterObject(search: DeliverySearch) {
  return from(Lips)
    .where(search)
    .orderBy((l) => l.delivery)
    .select((l) => l.delivery);
}

/** 排除分组维度和导航列后按用户的排序串排序：分组键、排序和连接都随之去掉。 */
export function dynamicColumns() {
  return from(Lips)
    .groupBy((l) => [l.material, l.unit, l.mara.partNumber])
    .orderBy((l) => [l.material.asc(), l.unit.asc()])
    .select((l) => ({ material: l.material, unit: l.unit, partNumber: l.mara.partNumber, total: l.quantity.sum() }))
    .exclude("unit", "partNumber")
    .sortBy("total desc, material");
}

/** An entity named Order: its alias is a reserved word and must be quoted. */
export function reservedAlias() {
  return from(Order)
    .where((o) => o.material.eq("M1"))
    .orderBy((o) => o.delivery)
    .select((o) => o.delivery);
}

/** a UNION ALL (b UNION c): the right-hand UNION keeps its parentheses, so a row shared by a and c stays twice. */
export function unionGrouping() {
  const materialOf = (delivery: string) =>
    from(Lips)
      .where((l) => l.delivery.eq(delivery))
      .select((l) => l.material);
  return materialOf("D1").unionAll(materialOf("D3").union(materialOf("D2")));
}

const groupingCases = (sets: typeof hanaGroupingSets, cubeOf: typeof hanaCube, prefix: string): Record<string, SpecCase> => ({
  [`${prefix}-grouping-sets`]: plain(() =>
    from(Lips)
      .groupBy((l) => sets([l.material, l.unit], [l.material], []))
      .select((l) => ({ material: l.material, unit: l.unit, lines: count() })),
  ),
  [`${prefix}-cube`]: plain(() =>
    from(Lips)
      .groupBy((l) => cubeOf(l.material, l.unit))
      .select((l) => ({ material: l.material, unit: l.unit, lines: count() })),
  ),
});

export const dialectCases: Readonly<Record<"hana" | "postgres" | "mysql" | "sqlite", Readonly<Record<string, SpecCase>>>> = {
  hana: {
    ...groupingCases(hanaGroupingSets, hanaCube, "hana"),
    "hana-extremes": plain(() =>
      from(Lips).select((l) => ({ atLeast4: hanaGreatest(l.quantity, val(new Decimal("4"))), atMost4: hanaLeast(l.quantity, val(new Decimal("4"))), number: hana(l.delivery.substring(2)).toInteger() })),
    ),
    "hana-functions": plain(() =>
      from(Lips)
        .with(hint("NO_CS_JOIN", "IGNORE_PLAN_CACHE"))
        .select((l) => ({
          days: lit(parseDate("2026-01-01")).daysBetween(lit(parseDate("2026-01-01")).addDays(3)),
          months: lit(DAY).addMonths(1),
          years: lit(DAY).addYears(2),
          text: hana(lit(DAY)).toVarchar("YYYYMMDD"),
          day: hana(l.delivery).toDate("YYYYMMDD"),
          size: hana(l.material).length(),
        })),
    ),
    "hana-statistics": plain(() =>
      from(Lips)
        .distinct()
        .select((l) => ({
          material: l.material,
          median: hanax.percentileCont(0.5, l.quantity).over((w) => w.partitionBy(l.material)),
          lower: hanax.percentileDisc(0.5, l.quantity).over((w) => w.partitionBy(l.material)),
        })),
    ),
    "hana-median": plain(() =>
      from(Lips)
        .groupBy((l) => l.material)
        .select((l) => ({ material: l.material, median: hanax.hana(l.quantity).median(), self: hanax.corr(l.quantity, l.quantity) })),
    ),
    "hana-full-join": plain(() =>
      from(Lips)
        .fullJoin(Mara, (l, m) => m.material.eq(l.material))
        .select((l, m) => ({ delivery: l.delivery, material: m.material })),
    ),
    "hana-calculation-view": plain(() =>
      hanax.fromView(SalesView, { P_YEAR: "2026", P_REGION: null })
        .where((v) => v.amount.gt(new Decimal(10)))
        .select((v) => ({ material: v.material, amount: v.amount })),
    ),
    "hana-table-function": plain(() =>
      hanax.fromFunction(LipsByUnit, { P_UNIT: "EA" })
        .orderBy((f) => f.delivery)
        .select((f) => f.delivery),
    ),
    "hana-hierarchy": plain(() =>
      from(hanax.hierarchy(orgSource()))
        .orderBy((n) => n.hierarchyRank)
        .select((n) => ({ nodeId: n.nodeId, hierarchyLevel: n.hierarchyLevel, hierarchyParentRank: n.hierarchyParentRank, name: n.name })),
    ),
    "hana-descendants": plain(() =>
      from(hanax.descendants(orgSource(), ["B"]))
        .orderBy((n) => n.nodeId)
        .select((n) => ({ nodeId: n.nodeId, hierarchyDistance: n.hierarchyDistance })),
    ),
    "hana-search": plain(() =>
      from(Lips)
        .where((l) => {
          hanax.contains("M1", l.material);
          hanax.containsFuzzy(0.8, "D1", l.delivery, l.material);
        })
        .orderBy(() => hanax.score().desc())
        .select((l) => ({ delivery: l.delivery, score: hanax.score(), json: hanax.hana(l.unit).jsonValue("$.unit") })),
    ),
    "hana-regexpr": plain(() =>
      from(Lips)
        .where((l) => hana(l.delivery).likeRegexpr("^D[0-9]+$"))
        .select((l) => ({ text: hana(l.quantity).toNvarchar(), day: hana(lit(DAY)).toNvarchar("YYYYMMDD") })),
    ),
    "hana-string-agg": plain(() =>
      from(Lips)
        .groupBy((l) => l.material)
        .select((l) => ({ material: l.material, deliveries: l.delivery.stringAgg(",", l.delivery.asc(), l.unit.desc()) })),
    ),
  },
  postgres: {
    ...groupingCases(pgGroupingSets, pgCube, "postgres"),
    "postgres-extremes": plain(() =>
      from(Lips).select((l) => ({ atLeast4: pgGreatest(l.quantity, val(new Decimal("4"))), atMost4: pgLeast(l.quantity, val(new Decimal("4"))) })),
    ),
    "postgres-functions": plain(() =>
      from(Lips)
        .with((l) => distinctOn(l.material))
        .where((l) => {
          postgres(l.unit).ilike("e%");
          l.material.isDistinctFrom(val("M9"));
          l.unit.isNotDistinctFrom(l.unit);
        })
        .orderBy((l) => [l.material.asc(), l.delivery.desc()])
        .select((l) => ({ material: l.material, delivery: l.delivery })),
    ),

    "postgres-set-all": plain(() => pgx.exceptAll(pgx.intersectAll(materialsOf("EA"), materialsOf("KG")), materialsOf("PC"))),
    "postgres-range-frame": plain(() =>
      from(Event)
        .orderBy((e) => e.id)
        .select((e) => ({
          id: e.id,
          lastWeek: count().over((w) => w.orderBy(e.onDay).frame(pgx.range(pgx.daysPreceding(7), Bound.currentRow()))),
          near: count().over((w) => w.orderBy(e.id).frame(pgx.range(Bound.preceding(1), Bound.following(0)))),
        })),
    ),
    "postgres-recursive": plain(() => {
      const steps = pgx.recursiveCte(
        "steps",
        from(Lips).where((l) => l.delivery.eq("D1")).select((l) => ({ delivery: l.delivery, depth: lit(1) })),
        (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: s.depth.plus(lit(1)) })),
      );
      return from(steps).orderBy((s) => s.depth).select((s) => s.depth);
    }),
    "postgres-locks": plain(() =>
      from(Lips)
        .join(Mara, (l, m) => m.material.eq(l.material))
        .with((l) => pgx.forUpdateOf("skipLocked", l))
        .select((l) => l.delivery),
    ),
    "postgres-share-lock": plain(() => from(Lips).with(pgx.forShare("nowait")).select((l) => l.delivery)),
    "postgres-statistics": plain(() =>
      from(Lips)
        .groupBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          median: pgx.percentileCont(0.5, l.quantity),
          lower: pgx.percentileDisc(0.5, l.quantity),
          unit: pgx.mode(l.unit),
          self: pgx.corr(l.quantity, l.quantity),
        })),
    ),
    "postgres-full-join": plain(() =>
      from(Lips)
        .fullJoin(Mara, (l, m) => m.material.eq(l.material))
        .select((l, m) => ({ delivery: l.delivery, material: m.material })),
    ),
    "postgres-arrays": plain(() =>
      from(Tagged)
        .where((t) => {
          pgx.postgres(t.id).eqAny([1, 2]);
          pgx.arrayContains(t.tags, ["red"]);
          pgx.arrayOverlaps(t.tags, ["blue", "green"]);
        })
        .orderBy((t) => t.id)
        .select((t) => ({ id: t.id, tags: t.tags, count: pgx.cardinality(t.tags) })),
    ),
    "postgres-array-agg": plain(() =>
      from(Lips)
        .groupBy((l) => l.material)
        .select((l) => ({ material: l.material, deliveries: pgx.arrayAgg(l.delivery, l.delivery.desc()) })),
    ),
    "postgres-json": plain(() =>
      from(Tagged)
        .where((t) => {
          pgx.postgres(t.doc).jsonContains('{"status":"open"}');
          pgx.postgres(t.doc).jsonHasKey("owner");
        })
        .select((t) => ({ id: t.id, owner: pgx.postgres(t.doc).jsonText("owner", "name") })),
    ),
    "postgres-text-search": plain(() =>
      from(Tagged)
        .where((t) => any(() => {
          pgx.textMatches("simple", t.body, "pump");
          pgx.webMatches("english", t.body, "valve -leak");
        }))
        .orderBy((t) => pgx.textRank("simple", t.body, "pump").desc())
        .select((t) => t.id),
    ),
    "postgres-aggregates": plain(() =>
      from(Lips)
        .groupBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          each: l.quantity.sum().filter(() => l.unit.eq("EA")),
          deliveries: l.delivery.stringAgg(";", l.delivery.desc()),
        })),
    ),
  },
  mysql: {
    "mysql-extremes": plain(() =>
      from(Lips).select((l) => ({ atLeast4: mysqlGreatest(l.quantity, val(new Decimal("4"))), atMost4: mysqlLeast(l.quantity, val(new Decimal("4"))) })),
    ),

    "mysql-set-all": plain(() => mysqlx.exceptAll(mysqlx.intersectAll(materialsOf("EA"), materialsOf("KG")), materialsOf("PC"))),
    "mysql-range-frame": plain(() =>
      from(Event)
        .orderBy((e) => e.id)
        .select((e) => ({
          id: e.id,
          lastWeek: count().over((w) => w.orderBy(e.onDay).frame(mysqlx.range(mysqlx.daysPreceding(7), Bound.currentRow()))),
          near: count().over((w) => w.orderBy(e.id).frame(mysqlx.range(Bound.preceding(1), Bound.following(0)))),
        })),
    ),
    "mysql-recursive": plain(() => {
      const steps = mysqlx.recursiveCte(
        "steps",
        from(Lips).where((l) => l.delivery.eq("D1")).select((l) => ({ delivery: l.delivery, depth: lit(1) })),
        (self) => from(self).where((s) => s.depth.lt(3)).select((s) => ({ delivery: s.delivery, depth: s.depth.plus(lit(1)) })),
      );
      return from(steps).orderBy((s) => s.depth).select((s) => s.depth);
    }),
    "mysql-locks": plain(() =>
      from(Lips)
        .join(Mara, (l, m) => m.material.eq(l.material))
        .with((l) => mysqlx.forUpdateOf("skipLocked", l))
        .select((l) => l.delivery),
    ),
    "mysql-share-lock": plain(() => from(Lips).with(mysqlx.forShare("nowait")).select((l) => l.delivery)),
    "mysql-hints": plain(() =>
      from(Lips)
        .join(Mara, (l, m) => m.material.eq(l.material))
        .with(() => mysqlx.maxExecutionTime(2000))
        .with((l) => mysqlx.index(l, "PRIMARY"))
        .with((l, m) => mysqlx.noIndex(m))
        .select((l) => l.delivery),
    ),
    "mysql-json": plain(() =>
      from(Article)
        .where((a) => mysqlx.mysql(a.doc).jsonContains('{"status":"open"}'))
        .select((a) => ({ id: a.id, owner: mysqlx.mysql(a.doc).jsonText("$.owner.name") })),
    ),
    "mysql-fulltext": plain(() =>
      from(Article)
        .where((a) => any(() => {
          mysqlx.match("pump", a.title, a.body);
          mysqlx.matchBoolean("+valve -leak", a.title, a.body);
        }))
        .orderBy((a) => mysqlx.matchScore("pump", a.title, a.body).desc())
        .select((a) => a.id),
    ),
    "mysql-functions": plain(() =>
      from(Lips)
        .with(maxExecutionTime(2000))
        .groupBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          days: lit(parseDate("2026-01-01")).daysBetween(lit(parseDate("2026-02-01"))),
          formatted: mysql(lit(DAY)).dateFormat("%Y%m%d"),
          deliveries: l.delivery.stringAgg("|", l.delivery.asc()),
        })),
    ),
    "mysql-update-hints": {
      filtered: true,
      query: () =>
        update(Lips)
          .with((l) => mysqlx.noIndex(l, "IX_LIPS"))
          .with(() => maxExecutionTime(2000))
          .set(() => ({ unit: "KG" }))
          .where((l) => l.delivery.eq("D1")),
    },
    "mysql-delete-hints": {
      filtered: true,
      query: () =>
        deleteFrom(Lips)
          .with((l) => mysqlx.index(l, "PRIMARY"))
          .where((l) => l.delivery.eq("D9")),
    },
  },
  sqlite: {
    "sqlite-functions": plain(() =>
      from(Lips)
        .where((l) => sqlite(l.unit).glob("E*"))
        .groupBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          middle: sqlite(l.quantity).median(),
          p90: sqlitePercentileCont(0.9, l.quantity),
          p50: sqlitePercentileDisc(0.5, l.quantity),
          padded: l.material.lpad(5, "0"),
          head: l.material.left(1),
        })),
    ),
  },
};

/**
 * PolarDB 的用例：写法沿用 MySQL，共享用例和 MySQL 方言的用例都和 MySQL 的金样例比对（见 spec.test.ts 的 fallbacks），
 * 只有 PolarDB 专属的写法有 `*.polardb.sql`；与 Java 的 PolardbSpecTest 一致。
 */
export const polardbCases: Readonly<Record<string, SpecCase>> = {
  ...dialectCases.mysql,
  "polardb-flashback": {
    filtered: true,
    query: () =>
      fromAsOf(Lips, parseDateTime("2026-01-15T08:30:00"))
        .where((l) => l.material.eq("M1"))
        .orderBy((l) => l.delivery)
        .select((l) => ({ delivery: l.delivery, quantity: l.quantity })),
  },
  "polardb-inventory": plain(() =>
    update(Lips)
      .with(() => inventory(1))
      .with((l) => mysqlx.noIndex(l))
      .set((l) => ({ quantity: l.quantity.minus(val(new Decimal("1"))) }))
      .where((l) => {
        l.delivery.eq("D1");
        l.quantity.gt(new Decimal(0));
      }),
  ),
};

/** 某个单位的交货行的物料。 */
function materialsOf(unit: string) {
  return from(Lips).where((l) => l.unit.eq(unit)).select((l) => l.material);
}

/** 方言不支持的共享用例：渲染时必须报错，也不能有金样例文件；与 Java 各方言的 unsupportedCases 一致。 */
export const unsupportedCases: Readonly<Partial<Record<keyof typeof dialectCases, readonly string[]>>> = {
  sqlite: ["rollup", "workflow-claim"],
};

import {
  abs,
  all,
  any,
  avg,
  Bound,
  caseOf,
  caseWhen,
  cast,
  ceil,
  CastType,
  concat,
  count,
  countDistinct,
  cte,
  date,
  decimal,
  identifier,
  denseRank,
  divide,
  exists,
  floor,
  from,
  grouping,
  lag,
  lead,
  left,
  like,
  lit,
  locate,
  lower,
  max,
  min,
  minus,
  negate,
  not,
  notExists,
  notLike,
  plus,
  rank,
  replace,
  right,
  round,
  rowNumber,
  scalar,
  sql,
  sqlCond,
  sqlTable,
  substring,
  sum,
  text,
  timestamp,
  times,
  trim,
  upper,
  val,
  where,
  values,
  type Query,
  type Ref,
} from "@zerodep-dev/query-core";
import { addDays, addMonths, addYears, day, daysBetween, firstValue, lastValue, month, ntile, stringAgg, year } from "@zerodep-dev/query-core";
import { cube as hanaCube, greatest as hanaGreatest, groupingSets as hanaGroupingSets, hint, least as hanaLeast, length, likeRegexpr, toDate, toInteger, toNvarchar, toVarchar } from "@zerodep-dev/query-hana";
import { dateFormat, greatest as mysqlGreatest, least as mysqlLeast, maxExecutionTime } from "@zerodep-dev/query-mysql";
import { cube as pgCube, distinctOn, greatest as pgGreatest, groupingSets as pgGroupingSets, ilike, least as pgLeast, pg } from "@zerodep-dev/query-postgres";
import { Event, Lips, Makt, Mara, MaraView, Order, Vbkd } from "./erp.js";
import { deliveries, latestDeliveries, lines, stock } from "./scenarios.js";
import * as wf from "./workflow.js";

/**
 * The golden-SQL cases of spec/render, built the TypeScript way. The Java tests build the same
 * cases (SpecCases.java and the dialect modules' spec tests); both must render every
 * spec/render/<case>.<dialect>.sql file exactly.
 */
export interface SpecCase {
  /** Rendered by a database instance with the client filter of the live scenarios. */
  readonly filtered: boolean;
  readonly query: () => Query<unknown>;
}

const plain = (query: () => Query<unknown>): SpecCase => ({ filtered: false, query });
const DAY = "2026-01-31";

export const sharedCases: Readonly<Record<string, SpecCase>> = {
  deliveries: plain(() => deliveries("2026-01-01", "2026-06-30", null)),
  "deliveries-filtered": { filtered: true, query: () => deliveries("2026-01-01", "2026-06-30", "MK1") },
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
      .where((m) =>
        all(
          any(m.material.eq("A"), m.partNumber.ne("B").and(m.manufacturer.isNull())),
          not(m.materialType.in(["ZMAT", "ZOTH"])),
          m.material.notIn(["X"]),
          like(m.partNumber, "P%"),
          notLike(m.manufacturer, "%X"),
          m.material.between("A", "Z"),
          m.manufacturer.isNotNull(),
          m.material.gt("0").and(m.material.ge("1")).and(m.material.lt("9")).and(m.material.le("8")),
          m.material.eq(null),
          like(m.material, null),
          m.material.between("A", null),
        ),
      )
      .select((m) => m.material),
  ),
  "case-template": plain(() =>
    from(Mara).select((m) =>
      caseWhen(m.materialType.eq("ZMAT"), "material")
        .when(m.materialType.eq("ZOTH"), "other")
        .otherwise(sql(text)`LOWER(${m.materialType})`),
    ),
  ),
  literals: plain(() =>
    from(Mara).select(() => ({
      quote: lit("O'Brien"),
      unicode: lit("Größe"),
      backslash: lit("a\\b"),
      day: lit(DAY, date),
      moment: lit("2026-01-31 08:15:30", timestamp),
      integer: lit(42),
      decimal: lit("12.50", decimal),
      flag: lit(true),
    })),
  ),
  functions: plain(() =>
    from(Lips).select((l) => ({
      upper: upper(l.material),
      lower: lower(l.unit),
      trimmed: trim(l.delivery),
      joined: concat(l.material, val("-"), l.unit),
      fallback: l.unit.coalesce("EA"),
      emptyUnit: l.unit.nullif(""),
      total: plus(l.quantity, l.quantity),
      difference: minus(l.quantity, val(1)),
      product: times(l.quantity, val(2)),
      ratio: divide(l.quantity, val(3)),
      magnitude: abs(l.quantity),
      rounded: round(l.quantity, 1),
      asText: cast(l.quantity, CastType.text(20)),
      asDecimal: l.material.cast(CastType.decimal(15, 3)),
      asInteger: cast(l.quantity, CastType.integer()),
      asDouble: cast(l.quantity, CastType.double()),
      asDate: cast(l.salesOrder, CastType.date()),
      asTimestamp: cast(l.salesOrder, CastType.timestamp()),
    })),
  ),
  aggregates: plain(() =>
    from(Lips)
      .groupBy((l) => l.material)
      .having((l) => count().gt(1))
      .select((l) => ({
        material: l.material,
        lines: count(),
        orders: countDistinct(l.salesOrder),
        average: avg(l.quantity),
        smallest: min(l.quantity),
        largest: max(l.quantity),
        total: sum(l.quantity),
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
      running: sum(l.quantity).over((w) => w.partitionBy(l.material).orderBy(l.delivery.asc()).rows(Bound.unboundedPreceding(), Bound.currentRow())),
      moving: avg(l.quantity).over((w) => w.orderBy(l.delivery.asc()).rows(Bound.preceding(2), Bound.following(1))),
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
      prefix: left(l.delivery, 2),
      suffix: right(l.delivery, 3),
      middle: substring(l.material, 2, 3),
      tail: substring(l.material, 2),
      cleaned: replace(l.unit, "-", ""),
      negative: negate(l.quantity),
      periods: plus([l.quantity, l.quantity.coalesce("0")]),
    })),
  ),
  "grouped-constants": plain(() => {
    const bucket = (l: Ref<Lips>) => caseWhen(l.quantity.le("2"), "small").when(l.quantity.le("5"), "medium").otherwise("large");
    return from(Lips)
      .where((l) => l.unit.eq("EA"))
      .groupBy((l) => [bucket(l), substring(l.delivery, 1, 1)])
      .having(() => count().gt(1))
      .orderBy((l) => bucket(l))
      .select((l) => ({
        bucket: bucket(l),
        prefix: substring(l.delivery, 1, 1),
        lines: count(),
        total: sum(caseWhen(l.unit.eq("EA"), l.quantity).otherwise("0")),
      }));
  }),
  "column-transforms": plain(() =>
    from(MaraView)
      .where((m) => all(m.maker.isNotNull(), m.storedMaker.ne("")))
      .groupBy((m) => m.partNumber)
      .select((m) => ({ partNumber: m.partNumber, makers: countDistinct(m.maker) })),
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
        .where((m) => all(m.materialType.eq("ZMAT"), m.deliveries.any((l) => l.unit.eq("EA"))))
        .orderBy((m) => m.material)
        .select((m) => ({
          material: m.material,
          lines: m.deliveries.count(),
          total: m.deliveries.sum((l) => l.quantity),
          latest: m.deliveries.first((l) => l.createdOn.desc()).delivery,
        })),
  },
  "date-functions": plain(() => dateFunctions()),
  "conditional-aggregates": plain(() => conditionalAggregates()),
  qualify: plain(() => qualify()),
  "window-values": plain(() => windowValues()),
  "many-text": { filtered: true, query: () => manyText() },
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
  subqueries: plain(() => {
    const parts = from(Mara)
      .where((m) => m.materialType.eq("ZMAT"))
      .select((m) => m.material);
    const open = from(Lips).select((l) => ({ delivery: l.delivery, material: l.material }));
    return from(open.asTable())
      .where((o) => all(o.material.in(parts), notExists(from(Vbkd).where((v) => v.salesOrder.eq(o.delivery)))))
      .select((o) => ({
        delivery: o.delivery,
        text: scalar(
          from(Makt)
            .where((mk) => mk.material.eq(o.material))
            .select((mk) => max(mk.text)),
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
      recent: m.deliveries.where((l) => l.createdOn.ge("2026-01-01")).count(),
    }));
}

/** 日期部分、日期加减和相隔天数，按半开区间筛选。 */
export function dateFunctions() {
  return from(Event)
    .where((e) => e.onDay.range("2011-01-01", "2025-01-01"))
    .orderBy((e) => e.id)
    .select((e) => ({
      id: e.id,
      y: year(e.onDay),
      m: month(e.atTime),
      d: day(e.onDay),
      plusDays: addDays(e.onDay, 3),
      minusMonth: addMonths(e.onDay, -1),
      plusYear: addYears(e.onDay, 1),
      days: daysBetween(e.onDay, lit("2024-04-30", date)),
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
      previous: lag(l.quantity, 1, "0").over((w) => w.partitionBy(l.material).orderBy(l.delivery)),
      half: ntile(2).over((w) => w.orderBy(l.delivery)),
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
      kg: sum(l.quantity).filter(l.unit.eq("KG")),
      eachLines: count().filter(l.unit.eq("EA")),
      units: countDistinct(l.unit).filter(l.quantity.gt("0")),
      deliveries: stringAgg(l.delivery, ",", l.delivery.asc()),
    }));
}

export function rollup() {
  return from(Lips)
    .groupByRollup((l) => [l.material, l.unit])
    .orderBy((l) => [grouping(l.material), l.material, grouping(l.unit)])
    .select((l) => ({ material: l.material, unit: l.unit, total: sum(l.quantity), level: grouping(l.material) }));
}

/** Cuts each comma-separated code where LOCATE finds the comma: computed SUBSTRING positions. */
export function textPositions() {
  const codes = cte("codes", values([{ text: "21,32" }, { text: "05" }]));
  return from(codes)
    .orderBy((c) => c.text)
    .select((c) => {
      const comma = locate(c.text, ",");
      return {
        text: c.text,
        comma,
        head: caseWhen(comma.gt(0), substring(c.text, 1, minus(comma, lit(1)))).otherwise(c.text),
        tail: caseWhen(comma.gt(0), substring(c.text, plus(comma, lit(1)))).end(),
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
      const half = divide(l.quantity, lit("2", decimal));
      return { delivery: l.delivery, down: floor(half), up: ceil(half) };
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
    .select((l) => ({ material: l.material, unit: l.unit, partNumber: l.mara.partNumber, total: sum(l.quantity) }))
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

export const dialectCases: Readonly<Record<"hana" | "postgres" | "mysql", Readonly<Record<string, SpecCase>>>> = {
  hana: {
    ...groupingCases(hanaGroupingSets, hanaCube, "hana"),
    "hana-extremes": plain(() =>
      from(Lips).select((l) => ({ atLeast4: hanaGreatest(l.quantity, val("4", decimal)), atMost4: hanaLeast(l.quantity, val("4", decimal)), number: toInteger(substring(l.delivery, 2)) })),
    ),
    "hana-functions": plain(() =>
      from(Lips)
        .with(hint("NO_CS_JOIN", "IGNORE_PLAN_CACHE"))
        .select((l) => ({
          days: daysBetween(lit("2026-01-01", date), addDays(lit("2026-01-01", date), 3)),
          months: addMonths(lit(DAY, date), 1),
          years: addYears(lit(DAY, date), 2),
          text: toVarchar(lit(DAY, date), "YYYYMMDD"),
          day: toDate(l.delivery, "YYYYMMDD"),
          size: length(l.material),
        })),
    ),
    "hana-regexpr": plain(() =>
      from(Lips)
        .where((l) => likeRegexpr(l.delivery, "^D[0-9]+$"))
        .select((l) => ({ text: toNvarchar(l.quantity), day: toNvarchar(lit(DAY, date), "YYYYMMDD") })),
    ),
    "hana-string-agg": plain(() =>
      from(Lips)
        .groupBy((l) => l.material)
        .select((l) => ({ material: l.material, deliveries: stringAgg(l.delivery, ",", l.delivery.asc(), l.unit.desc()) })),
    ),
  },
  postgres: {
    ...groupingCases(pgGroupingSets, pgCube, "postgres"),
    "postgres-extremes": plain(() =>
      from(Lips).select((l) => ({ atLeast4: pgGreatest(l.quantity, val("4", decimal)), atMost4: pgLeast(l.quantity, val("4", decimal)) })),
    ),
    "postgres-functions": plain(() =>
      from(Lips)
        .with((l) => distinctOn(l.material))
        .where((l) => all(ilike(l.unit, "e%"), pg(l.material).isDistinctFrom("M9"), pg(l.unit).isNotDistinctFrom(l.unit)))
        .orderBy((l) => [l.material.asc(), l.delivery.desc()])
        .select((l) => ({ material: l.material, delivery: l.delivery })),
    ),
    "postgres-aggregates": plain(() =>
      from(Lips)
        .groupBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          each: sum(l.quantity).filter(l.unit.eq("EA")),
          deliveries: stringAgg(l.delivery, ";", l.delivery.desc()),
        })),
    ),
  },
  mysql: {
    "mysql-extremes": plain(() =>
      from(Lips).select((l) => ({ atLeast4: mysqlGreatest(l.quantity, val("4", decimal)), atMost4: mysqlLeast(l.quantity, val("4", decimal)) })),
    ),
    "mysql-functions": plain(() =>
      from(Lips)
        .with(maxExecutionTime(2000))
        .groupBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          days: daysBetween(lit("2026-01-01", date), lit("2026-02-01", date)),
          formatted: dateFormat(lit(DAY, date), "%Y%m%d"),
          deliveries: stringAgg(l.delivery, "|", l.delivery.asc()),
        })),
    ),
  },
};

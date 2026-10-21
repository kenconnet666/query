import {
  abs,
  all,
  any,
  avg,
  Bound,
  caseWhen,
  cast,
  CastType,
  concat,
  count,
  countDistinct,
  cte,
  date,
  decimal,
  denseRank,
  divide,
  exists,
  from,
  lag,
  lead,
  left,
  like,
  likeIfPresent,
  lit,
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
  substring,
  sum,
  text,
  timestamp,
  times,
  trim,
  upper,
  val,
  values,
  type Query,
  type Ref,
} from "@query/core";
import { addDays, addMonths, addYears, daysBetween, hint, length, likeRegexpr, stringAgg as hanaStringAgg, toDate, toNvarchar, toVarchar } from "@query/hana";
import { dateDiff, dateFormat, groupConcat, maxExecutionTime } from "@query/mysql";
import { distinctOn, filterWhere, ilike, pg, stringAgg as pgStringAgg } from "@query/postgres";
import { Lips, Makt, Mara, Vbkd } from "./erp.js";
import { deliveries, latestDeliveries, lines, stock } from "./scenarios.js";

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
      .where((a, b) => exists(from(Mara).where((c) => c.material.eq(b.material)).selectOne()))
      .selectValue((a) => a.material),
  ),
  paging: plain(() =>
    from(Mara)
      .orderBy((m) => [m.material.asc().nullsLast(), m.partNumber.desc().nullsFirst()])
      .selectValue((m) => m.material)
      .limit(10)
      .offset(20),
  ),
  "offset-only": plain(() =>
    from(Mara)
      .orderBy((m) => m.material.asc())
      .selectValue((m) => m.material)
      .offset(5),
  ),
  conditions: plain(() =>
    from(Mara)
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
          m.material.eqIfPresent(null),
          likeIfPresent(m.material, null),
          m.material.betweenIfPresent("A", null),
        ),
      )
      .selectValue((m) => m.material),
  ),
  "case-template": plain(() =>
    from(Mara).selectValue((m) =>
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
  subqueries: plain(() => {
    const parts = from(Mara)
      .where((m) => m.materialType.eq("ZMAT"))
      .selectValue((m) => m.material);
    const open = from(Lips).select((l) => ({ delivery: l.delivery, material: l.material }));
    return from(open.asTable())
      .where((o) => all(o.material.in(parts), notExists(from(Vbkd).where((v) => v.salesOrder.eq(o.delivery)).selectOne())))
      .select((o) => ({
        delivery: o.delivery,
        text: scalar(
          from(Makt)
            .where((mk) => mk.material.eq(o.material))
            .selectValue((mk) => max(mk.text)),
        ),
      }));
  }),
};

export const dialectCases: Readonly<Record<"hana" | "postgres" | "mysql", Readonly<Record<string, SpecCase>>>> = {
  hana: {
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
        .select((l) => ({ material: l.material, deliveries: hanaStringAgg(l.delivery, ",", l.delivery.asc(), l.unit.desc()) })),
    ),
  },
  postgres: {
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
          each: filterWhere(sum(l.quantity), l.unit.eq("EA")),
          deliveries: pgStringAgg(l.delivery, ";", l.delivery.desc()),
        })),
    ),
  },
  mysql: {
    "mysql-functions": plain(() =>
      from(Lips)
        .with(maxExecutionTime(2000))
        .groupBy((l) => l.material)
        .select((l) => ({
          material: l.material,
          days: dateDiff(lit("2026-02-01", date), lit("2026-01-01", date)),
          formatted: dateFormat(lit(DAY, date), "%Y%m%d"),
          deliveries: groupConcat(l.delivery, "|", l.delivery.asc()),
        })),
    ),
  },
};

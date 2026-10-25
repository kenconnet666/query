import type { CalendarDate } from "@internationalized/date";
import { caseWhen, cte, exists, from, rowNumber, type Expr, type ExprText, type Ref, type Table } from "@zerodep-dev/query-core";
import { Decimal } from "decimal.js";
import { LineVO, Lips, Makt, Mara, Mardh, Mchbh, Vbkd, type PeriodStock } from "./erp.js";

/** Queries written the way users write them; rendered by the unit tests and executed by the live tests. */

// ---------- business definitions are plain functions ----------

/** A reusable condition is a function that writes it into the caller's condition block. */
export const activeMaterial = (ma: Ref<Mara>): void => ma.materialType.eq("ZMAT");

export const normalizedPart = (part: ExprText<string>): ExprText<string> => caseWhen(() => part.ne(part.trim()), part.trim()).otherwise(part);

/** CTE, EXISTS, LEFT JOIN, optional filter and GROUP BY. */
export function deliveries(start: CalendarDate, end: CalendarDate, maker: string | null) {
  const scope = cte(
    "material_scope",
    from(Mara)
      .skipEmpty()
      .where((ma) => {
        activeMaterial(ma);
        ma.manufacturer.eq(maker);
      })
      .distinct()
      .select((ma) => ({ client: ma.client, material: ma.material, partNumber: normalizedPart(ma.partNumber) })),
  );
  return from(scope)
    .join(Lips, (ms, l) => {
      l.client.eq(ms.client);
      l.material.eq(ms.material);
    })
    .leftJoin(Makt, (ms, l, mk) => {
      mk.material.eq(ms.material);
      mk.language.eq("1");
    })
    .where((ms, l) => {
      l.createdOn.between(start, end);
      exists(from(Vbkd).where((v) => v.salesOrder.eq(l.salesOrder)));
    })
    .groupBy((ms) => ms.partNumber)
    .orderBy((ms) => ms.partNumber.asc())
    .select((ms, l, mk) => ({
      partNumber: ms.partNumber,
      name: mk.text.coalesce(ms.partNumber).max(),
      quantity: l.quantity.sum(),
    }));
}

/** One template for every table with the PeriodStock shape; the quantity column differs, so it is a function. */
export function stockArm<E extends PeriodStock>(table: Table<E>, quantity: (h: Ref<E>) => Expr<Decimal>, year: string) {
  return from(table)
    .where((h) => h.year.eq(year))
    .select((h) => ({ material: h.material, year: h.year, period: h.period, quantity: quantity(h) }));
}

export function stock(year: string) {
  return stockArm(Mardh, (h) => h.unrestricted.coalesce(new Decimal(0)), year).unionAll(stockArm(Mchbh, (h) => h.batchUnrestricted.coalesce(new Decimal(0)), year));
}

/** The latest delivery per material: ROW_NUMBER in a CTE, then rank = 1. */
export function latestDeliveries() {
  const ranked = cte(
    "ranked_delivery",
    from(Lips).select((l) => ({
      material: l.material,
      delivery: l.delivery,
      rank: rowNumber().over((w) => w.partitionBy(l.material).orderBy(l.createdOn.desc(), l.delivery.desc())),
    })),
  );
  return from(ranked)
    .where((x) => x.rank.eq(1))
    .orderBy((x) => x.material.asc())
    .select((x) => x.delivery);
}

/** 按名映射：改名的列写成对象的键。 */
export function lines() {
  return from(Lips)
    .leftJoin(Makt, (l, mk) => {
      mk.material.eq(l.material);
      mk.language.eq("1");
    })
    .orderBy((l) => l.delivery.asc())
    .select((l, mk) => ({ delivery: l.delivery, materialNo: l.material, createdOn: l.createdOn, text: mk.text }))
    .into(LineVO);
}

/** A nested result computed in JavaScript from the selected values. */
export function partTotals() {
  return from(Lips)
    .join(Mara, (l, ma) => ma.material.eq(l.material))
    .groupBy((l, ma) => ma.partNumber)
    .orderBy((l, ma) => ma.partNumber.asc())
    .select((l, ma) => ({ partNumber: ma.partNumber, quantity: l.quantity.sum() }))
    .map((r) => ({ partNumber: r.partNumber, amount: { quantity: r.quantity, wan: r.quantity === null ? 0 : r.quantity.div(10_000).toNumber() } }));
}

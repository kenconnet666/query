import { all, caseWhen, cte, exists, from, max, rowNumber, sum, trim, type Cond, type Expr, type Ref, type Table } from "@query/core";
import { LineVO, Lips, Makt, Mara, Mardh, Mchbh, Vbkd, type PeriodStock } from "./erp.js";

/** Queries written the way users write them; rendered by the unit tests and executed by the live tests. */

// ---------- business definitions are plain functions ----------

export const activeMaterial = (ma: Ref<Mara>): Cond => ma.materialType.eq("ZMAT");

export const normalizedPart = (part: Expr<string>): Expr<string> => caseWhen(part.ne(trim(part)), trim(part)).otherwise(part);

/** CTE, EXISTS, LEFT JOIN, optional filter and GROUP BY. */
export function deliveries(start: string, end: string, maker: string | null) {
  const scope = cte(
    "material_scope",
    from(Mara)
      .where((ma) => all(activeMaterial(ma), ma.manufacturer.eqIfPresent(maker)))
      .distinct()
      .select((ma) => ({ client: ma.client, material: ma.material, partNumber: normalizedPart(ma.partNumber) })),
  );
  return from(scope)
    .join(Lips, (ms, l) => l.client.eq(ms.client).and(l.material.eq(ms.material)))
    .leftJoin(Makt, (ms, l, mk) => mk.material.eq(ms.material).and(mk.language.eq("1")))
    .where((ms, l) => all(l.createdOn.between(start, end), exists(from(Vbkd).where((v) => v.salesOrder.eq(l.salesOrder)).selectOne())))
    .groupBy((ms) => ms.partNumber)
    .orderBy((ms) => ms.partNumber.asc())
    .select((ms, l, mk) => ({
      partNumber: ms.partNumber,
      name: max(mk.text.coalesce(ms.partNumber)),
      quantity: sum(l.quantity),
    }));
}

/** One template for every table with the PeriodStock shape; the quantity column differs, so it is a function. */
export function stockArm<E extends PeriodStock>(table: Table<E>, quantity: (h: Ref<E>) => Expr<string>, year: string) {
  return from(table)
    .where((h) => h.year.eq(year))
    .select((h) => ({ material: h.material, year: h.year, period: h.period, quantity: quantity(h) }));
}

export function stock(year: string) {
  return stockArm(Mardh, (h) => h.unrestricted.coalesce("0"), year).unionAll(stockArm(Mchbh, (h) => h.batchUnrestricted.coalesce("0"), year));
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
    .selectValue((x) => x.delivery);
}

/** Same-named fields picked automatically, a renamed one through @mapFrom. */
export function lines() {
  return from(Lips)
    .leftJoin(Makt, (l, mk) => mk.material.eq(l.material).and(mk.language.eq("1")))
    .orderBy((l) => l.delivery.asc())
    .selectAs(LineVO);
}

/** A nested result computed in JavaScript from the selected values. */
export function partTotals() {
  return from(Lips)
    .join(Mara, (l, ma) => ma.material.eq(l.material))
    .groupBy((l, ma) => ma.partNumber)
    .orderBy((l, ma) => ma.partNumber.asc())
    .select((l, ma) => ({ partNumber: ma.partNumber, quantity: sum(l.quantity) }))
    .map((r) => ({ partNumber: r.partNumber, amount: { quantity: r.quantity, wan: r.quantity === null ? 0 : Number(r.quantity) / 10_000 } }));
}

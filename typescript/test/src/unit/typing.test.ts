import { parseDate, type CalendarDate, type CalendarDateTime } from "@internationalized/date";
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  column,
  currentDate,
  decimal,
  from,
  integer,
  lag,
  lit,
  onInsert,
  rowNumber,
  update,
  val,
  type Expr,
  type ExprBigint,
  type ExprDate,
  type ExprDecimal,
  type ExprNode,
  type ExprNumber,
  type ExprText,
  type ExprTimestamp,
  type Query,
} from "@zerodep-dev/query-core";
import { Decimal } from "decimal.js";
import { DeliveryVO, Event, Lips, Makt, Mara, sapDate } from "../fixtures/erp.js";
import { stockArm } from "../fixtures/scenarios.js";
import { Audited } from "../fixtures/writes.js";

/**
 * Compile-time checks: every `@ts-expect-error` below must be an error, or `tsc -b` fails with an
 * unused directive. The runtime test only records that the file compiled.
 */

type Equal<A, B> = (<X>() => X extends A ? 1 : 2) extends <X>() => X extends B ? 1 : 2 ? true : false;
const expectType = <T extends true>(_?: T) => undefined;

/** Never called: the checks only need to compile, and most would fail if they ran. */
export function typeChecks(): unknown[] {
  // ---------- scope ----------

  // @ts-expect-error a column of a table that is not in the scope
  from(Lips).where((l) => l.language.eq("x"));

  // @ts-expect-error more callback parameters than tables
  from(Lips).join(Mara, (l, ma) => ma.material.eq(l.material)).where((l, ma, extra) => extra.eq("x"));

  // ---------- comparisons and same-type functions ----------

  // @ts-expect-error a decimal column compared with a number
  from(Lips).where((l) => l.quantity.eq(1));

  // @ts-expect-error join keys of different types
  from(Lips).join(Event, (l, e) => e.id.eq(l.material));

  // @ts-expect-error COALESCE with a value of another type
  from(Lips).select((l) => l.unit.coalesce(1));

  // @ts-expect-error IN with values of another type
  from(Event).where((e) => e.id.in(["1"]));

  // @ts-expect-error an IN subquery of another type
  from(Event).where((e) => e.id.in(from(Lips).select((l) => l.delivery)));

  // ---------- typed columns: what a column offers follows how the database stores it ----------

  // @ts-expect-error a text column has no numeric functions
  from(Lips).select((l) => l.material.sum());

  // @ts-expect-error a date stored as text (Stored<CalendarDate, string>) has no date functions
  from(Lips).select((l) => l.createdOn.addDays(1));

  // The stored text has text functions, and compares with the field type.
  from(Lips).where((l) => {
    l.createdOn.startsWith("2026");
    l.createdOn.ge(parseDate("2026-01-01"));
  });

  // @ts-expect-error a date has no time-of-day functions
  from(Event).select((e) => e.onDay.hour());

  // @ts-expect-error a bigint has no division: integer division differs between databases
  from(Event).select((e) => (e.id as unknown as ExprBigint<bigint>).divide(2n));

  // Arithmetic promotes to the wider operand: integer plus decimal is a decimal.
  const promoted = from(Event).select((e) => e.id.plus(new Decimal("0.5")));
  expectType<Equal<typeof promoted, Query<Decimal>>>();

  // Functions over an expression keep its kind: lag of a decimal is a decimal that can still divide.
  const lagged = from(Lips).select((l) => lag(l.quantity).over((w) => w.orderBy(l.delivery)).divide(2));
  expectType<Equal<typeof lagged, Query<Decimal | null>>>();

  const dated = from(Event).select((e) => ({ next: e.onDay.addDays(1), at: e.atTime.addHours(1), year: e.onDay.year() }));
  expectType<Equal<typeof dated, Query<{ next: CalendarDate; at: CalendarDateTime; year: number }>>>();

  // Every method of the typed interfaces is implemented by the runtime node class.
  type Declared = keyof ExprText<string> | keyof ExprNumber<number> | keyof ExprBigint<bigint> | keyof ExprDecimal<Decimal> | keyof ExprDate<CalendarDate> | keyof ExprTimestamp<CalendarDateTime>;
  expectType<Equal<Exclude<Declared, keyof ExprNode<unknown>>, never>>();

  // ---------- statement-style conditions ----------

  // @ts-expect-error a condition is a statement, not a value to chain
  from(Lips).where((l) => l.unit.eq("A").and(l.unit.eq("B")));

  // @ts-expect-error conditions do not combine with &&
  from(Lips).where((l) => l.unit.eq("A") && l.unit.eq("B"));

  // @ts-expect-error a date stored as text cannot be assigned a date expression
  update(Lips).set(() => ({ createdOn: currentDate() }));

  // @ts-expect-error fills name a column, not a computed expression
  onInsert(Audited, (a) => a.updatedBy.upper(), () => "x");

  // ---------- results ----------

  const joined = from(Lips)
    .leftJoin(Makt, (l, mk) => mk.material.eq(l.material))
    .select((l, mk) => ({ delivery: l.delivery, text: mk.text, total: { quantity: l.quantity.sum() } }));
  expectType<Equal<typeof joined, Query<{ delivery: string; text: string | null; total: { quantity: Decimal | null } }>>>();

  const rightJoined = from(Makt)
    .rightJoin(Lips, (mk, l) => mk.material.eq(l.material))
    .select((mk, l) => ({ text: mk.text, delivery: l.delivery }));
  expectType<Equal<typeof rightJoined, Query<{ text: string | null; delivery: string }>>>();

  // @ts-expect-error the LEFT JOIN side may be missing
  const notNull: Query<{ text: string }> = from(Lips).leftJoin(Makt, (l, mk) => mk.material.eq(l.material)).select((l, mk) => ({ text: mk.text }));

  // @ts-expect-error a window function needs over(...) before it is an expression
  from(Lips).select(() => ({ n: rowNumber() }));

  // @ts-expect-error UNION arms of different row types
  from(Lips).select((l) => ({ a: l.delivery })).unionAll(from(Lips).select((l) => ({ b: l.delivery })));

  // @ts-expect-error into needs the row to provide every field of the class
  from(Lips).select((l) => ({ partNumber: l.material })).into(DeliveryVO);

  from(Lips)
    .groupBy((l) => l.material)
    .select((l) => ({ partNumber: l.material, name: l.unit.max(), quantity: l.quantity.sum() }))
    .into(DeliveryVO);

  // @ts-expect-error a generic template applied to a table without the shared fields
  stockArm(Lips, (h) => h.quantity, "2026");

  // ---------- dynamic columns ----------

  const excluded = from(Lips)
    .select((l) => ({ delivery: l.delivery, stock: { quantity: l.quantity, unit: l.unit } }))
    .exclude("stock");
  expectType<Equal<typeof excluded, Query<{ delivery: string; stock: { quantity: Decimal | null; unit: string | null } }>>>();

  // @ts-expect-error exclude names a key of the result
  from(Lips).select((l) => ({ delivery: l.delivery })).exclude("plant");

  // ---------- relations ----------

  const navigated = from(Lips).select((l) => ({
    part: l.mara.partNumber,
    lines: l.mara.deliveries.count(),
    latest: l.mara.deliveries.first((d) => d.createdOn.desc()).delivery,
  }));
  expectType<Equal<typeof navigated, Query<{ part: string | null; lines: number; latest: string | null }>>>();

  // Spreading a reference keeps only the columns.
  const spread = from(Makt).select((t) => ({ ...t }));
  expectType<Equal<typeof spread, Query<{ client: string; material: string; language: string; text: string }>>>();
  const withoutRelations = from(Mara).select((m) => ({ material: m.material, deliveries: m.deliveries, text: m.text }));
  expectType<Equal<typeof withoutRelations, Query<{ material: string }>>>();

  const tree = from(Mara).select((m) => ({ material: m.material, lines: m.deliveries.list((l) => ({ delivery: l.delivery })), orders: m.deliveries.list((l) => l.salesOrder) }));
  expectType<Equal<typeof tree, Query<{ material: string; lines: { delivery: string }[]; orders: string[] }>>>();

  // @ts-expect-error a one-to-many relation is not a column
  from(Mara).where((m) => m.deliveries.eq("x"));

  // @ts-expect-error a many-to-one relation is a reference, not a column
  from(Lips).where((l) => l.mara.eq("x"));

  // ---------- decorators ----------

  class BadColumns {
    // @ts-expect-error the default text type on a number field
    @column("A") a!: number;
    // @ts-expect-error an integer column on a text field
    @column("B", integer) b!: string;
    @column("C", decimal) c!: Decimal | null;
    // @ts-expect-error a column stored as another type needs Stored<FieldType, StorageType>
    @column("D", sapDate) d!: CalendarDate | null;
  }

  // ---------- generic code over a shared shape ----------

  const quantityOf = (q: ExprDecimal<Decimal | null>): ExprDecimal<Decimal> => q.coalesce(new Decimal(0));

  // Constants widen, so arms that differ only in a constant combine.
  const codes = from(Lips)
    .select(() => ({ code: lit("A"), rank: val(1) }))
    .unionAll(from(Lips).select(() => ({ code: lit("B"), rank: val(2) })));
  expectType<Equal<typeof codes, Query<{ code: string; rank: number }>>>();

  return [joined, notNull, BadColumns, quantityOf, expectType, codes, navigated, spread, withoutRelations, excluded, tree, promoted, lagged, dated];
}

describe("编译期类型检查", () => {
  it("错误的写法都不能通过编译", () => {
    assert.ok(true, "this file compiled, so every @ts-expect-error above was an error");
  });
});

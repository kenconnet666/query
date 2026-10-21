import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { column, decimal, from, integer, lit, mapFrom, max, rowNumber, sum, val, type Expr, type Query } from "@query/core";
import { DeliveryVO, Event, Lips, Makt, Mara } from "../erp.js";
import { stockArm } from "../scenarios.js";

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
  from(Lips).where((l) => l.text.eq("x"));

  // @ts-expect-error more callback parameters than tables
  from(Lips).join(Mara, (l, ma) => ma.material.eq(l.material)).where((l, ma, extra) => extra.eq("x"));

  // ---------- comparisons and same-type functions ----------

  // @ts-expect-error a decimal column compared with a number
  from(Lips).where((l) => l.quantity.eq(1));

  // @ts-expect-error join keys of different types
  from(Lips).join(Event, (l, e) => e.id.eq(l.material));

  // @ts-expect-error COALESCE with a value of another type
  from(Lips).selectValue((l) => l.unit.coalesce(1));

  // @ts-expect-error IN with values of another type
  from(Event).where((e) => e.id.in(["1"]));

  // @ts-expect-error an IN subquery of another type
  from(Event).where((e) => e.id.in(from(Lips).selectValue((l) => l.delivery)));

  // ---------- results ----------

  const joined = from(Lips)
    .leftJoin(Makt, (l, mk) => mk.material.eq(l.material))
    .select((l, mk) => ({ delivery: l.delivery, text: mk.text, total: { quantity: sum(l.quantity) } }));
  expectType<Equal<typeof joined, Query<{ delivery: string; text: string | null; total: { quantity: string | null } }>>>();

  // @ts-expect-error the LEFT JOIN side may be missing
  const notNull: Query<{ text: string }> = from(Lips).leftJoin(Makt, (l, mk) => mk.material.eq(l.material)).select((l, mk) => ({ text: mk.text }));

  // @ts-expect-error a window function needs over(...) before it is an expression
  from(Lips).select(() => ({ n: rowNumber() }));

  // @ts-expect-error UNION arms of different row types
  from(Lips).select((l) => ({ a: l.delivery })).unionAll(from(Lips).select((l) => ({ b: l.delivery })));

  // @ts-expect-error mapTo needs the row to provide every field of the class
  from(Lips).select((l) => ({ partNumber: l.material })).mapTo(DeliveryVO);

  from(Lips)
    .groupBy((l) => l.material)
    .select((l) => ({ partNumber: l.material, name: max(l.unit), quantity: sum(l.quantity) }))
    .mapTo(DeliveryVO);

  // @ts-expect-error a generic template applied to a table without the shared fields
  stockArm(Lips, (h) => h.quantity, "2026");

  // ---------- decorators ----------

  class BadColumns {
    // @ts-expect-error the default text type on a number field
    @column("A") a!: number;
    // @ts-expect-error an integer column on a text field
    @column("B", integer) b!: string;
    @column("C", decimal) c!: string | null;
  }

  class BadMapping {
    // @ts-expect-error a key the entity does not have
    @mapFrom(Lips, "nothing") a!: string;
    // @ts-expect-error a field that cannot hold the entity's value
    @mapFrom(Lips, "delivery") b!: number;
  }

  // ---------- generic code over a shared shape ----------

  const quantityOf = (q: Expr<string | null>): Expr<string> => q.coalesce("0");

  // Constants widen, so arms that differ only in a constant combine.
  const codes = from(Lips)
    .select(() => ({ code: lit("A"), rank: val(1) }))
    .unionAll(from(Lips).select(() => ({ code: lit("B"), rank: val(2) })));
  expectType<Equal<typeof codes, Query<{ code: string; rank: number }>>>();

  return [joined, notNull, BadColumns, BadMapping, quantityOf, expectType, codes];
}

describe("编译期类型检查", () => {
  it("错误的写法都不能通过编译", () => {
    assert.ok(true, "this file compiled, so every @ts-expect-error above was an error");
  });
});

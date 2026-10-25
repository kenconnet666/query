import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { CalendarDate, CalendarDateTime, parseAbsolute, Time, ZonedDateTime } from "@internationalized/date";
import { decodeBase64, encodeBase64, fromWire, parseWire, toWire, wireText } from "@zerodep-dev/wire";
import { Decimal } from "decimal.js";

/** 与 Java、vue-tsx-pro 共用的样例：spec/wire/order.json 是下面这个对象写到线上的样子。 */
const SAMPLE = fileURLToPath(new URL("../../../../spec/wire/order.json", import.meta.url));

/** 样例对象的 TypeScript 写法；Java 的 WireTest 用 record 写同一个对象。 */
function order() {
  return {
    id: 9007199254740993n,
    code: "PO-1",
    count: 3,
    rate: 0.25,
    ratio: Number.NaN,
    active: true,
    amount: new Decimal("12.5"),
    on: new CalendarDate(2024, 1, 31),
    at: new Time(13, 45),
    created: new CalendarDateTime(2024, 1, 31, 13, 45, 0, 500),
    sent: parseAbsolute("2024-01-31T05:45:00Z", "Asia/Shanghai"),
    file: new Uint8Array([1, 2, 3, 255]),
    note: null,
    lines: [
      { sku: "A.1", qty: new Decimal("2.75") },
      { sku: "B", qty: new Decimal("1") },
    ],
    tags: [1n, 2n],
    byMonth: new Map([
      ["2024.01", new Decimal("3.5")],
      ["2024.02", new Decimal("0")],
    ]),
  };
}

describe("wire：线上的 typed JSON", () => {
  it("toWire 与共用样例逐字段相同，经过 JSON 文本也一样", () => {
    const expected: unknown = JSON.parse(readFileSync(SAMPLE, "utf8"));
    assert.deepEqual(toWire(order()), expected);
    assert.deepEqual(JSON.parse(JSON.stringify(toWire(order()))), expected);
  });

  it("fromWire 按 $types 还原样例，去掉 $types", () => {
    const value = fromWire<Record<string, unknown>>(JSON.parse(readFileSync(SAMPLE, "utf8")), { timeZone: "Asia/Shanghai" });
    const expected = order();
    assert.equal(value.id, expected.id);
    assert.ok((value.amount as Decimal).equals(expected.amount));
    assert.equal((value.on as CalendarDate).compare(expected.on), 0);
    assert.equal(String(value.at), "13:45:00");
    assert.equal(String(value.created), "2024-01-31T13:45:00.5");
    assert.ok(value.sent instanceof ZonedDateTime);
    assert.equal((value.sent as ZonedDateTime).compare(expected.sent), 0);
    assert.deepEqual([...(value.file as Uint8Array)], [1, 2, 3, 255]);
    assert.ok(Number.isNaN(value.ratio));
    assert.deepEqual(value.tags, [1n, 2n]);
    assert.ok(((value.lines as { qty: Decimal }[])[0]!.qty).equals("2.75"));
    assert.ok(((value.byMonth as Record<string, Decimal>)["2024.01"]!).equals("3.5"));
    assert.equal(value.code, "PO-1");
    assert.equal("$types" in value, false);
  });

  it("没有要还原的值时就是普通 JSON；根不是对象时包成 $value", () => {
    assert.deepEqual(toWire({ name: "a", n: 1, list: [true, null] }), { name: "a", n: 1, list: [true, null] });
    assert.deepEqual(toWire([1n, 2n]), { $value: ["1", "2"], $types: { $value: "bigint" } });
    assert.deepEqual(toWire(new Decimal("1e21")), { $value: "1000000000000000000000", $types: { $value: "decimal" } });
    assert.deepEqual(fromWire(toWire([1n, 2n])), [1n, 2n]);
    assert.deepEqual(fromWire({ a: 1 }), { a: 1 });
  });

  it("同一路径只能是一种值；循环引用与保留的键报错", () => {
    assert.throws(() => toWire([{ v: 1n }, { v: "x" }]), /values at v are both bigint and text/);
    const loop: Record<string, unknown> = {};
    loop.self = loop;
    assert.throws(() => toWire(loop), /refers back to itself/);
    assert.throws(() => toWire({ $types: {} }), /reserved/);
  });

  it("有 toJSON 的对象先调用它；undefined 与函数跳过；-0 写成 0", () => {
    const page = { rows: [{ amount: new Decimal("2") }], toJSON: undefined, hidden: () => 1, gone: undefined, zero: -0 };
    assert.deepEqual(toWire(page), { rows: [{ amount: "2" }], zero: 0, $types: { "rows.amount": "decimal" } });
    const custom = { toJSON: () => ({ when: new CalendarDate(2024, 1, 1) }) };
    assert.deepEqual(toWire({ x: custom }), { x: { when: "2024-01-01" }, $types: { "x.when": "date" } });
  });

  it("fromWire 读不出的文字报错并指出路径", () => {
    assert.throws(() => fromWire({ a: "x", $types: { a: "decimal" } }), /fromWire: a: "x" is not a decimal number/);
    assert.throws(() => fromWire({ a: "2024-02-30", $types: { a: "date" } }), /not a calendar date/);
  });

  it("wireText 与 parseWire：地址栏里的单个值", () => {
    assert.equal(wireText(new Decimal("1e-7")), "0.0000001");
    assert.equal(wireText(12345678901234567890n), "12345678901234567890");
    assert.equal(wireText(new CalendarDate(2024, 1, 31)), "2024-01-31");
    assert.equal(wireText(new Date(Date.UTC(2024, 0, 31, 5, 45, 0, 120))), "2024-01-31T05:45:00.12Z");
    assert.equal(wireText(null), "");
    assert.throws(() => wireText({}), /not a single value/);
    assert.equal(parseWire("integer", "-12"), -12);
    assert.throws(() => parseWire("integer", "9007199254740993"), /safe integer/);
    assert.throws(() => parseWire("integer", "1.5"), /safe integer/);
    assert.equal(parseWire("bigint", "9007199254740993"), 9007199254740993n);
    assert.equal(parseWire("boolean", "false"), false);
    assert.throws(() => parseWire("boolean", "yes"), /true or false/);
    assert.equal(String(parseWire("timestamp", "2024-01-31 13:45")), "2024-01-31T13:45:00");
    assert.equal(String(parseWire("time", "13:45:30.1239")), "13:45:30.123");
    assert.throws(() => parseWire("timestamptz", "2024-01-31T05:45:00"), /offset/);
    assert.equal((parseWire("timestamptz", "2024-01-31T13:45:00+08:00", { timeZone: "UTC" }) as ZonedDateTime).hour, 5);
  });

  it("base64 与 Java 的 Base64 一致", () => {
    for (const bytes of [[], [0], [1, 2], [1, 2, 3], [255, 254, 253, 252]]) {
      const text = encodeBase64(new Uint8Array(bytes));
      assert.equal(text, Buffer.from(bytes).toString("base64"));
      assert.deepEqual([...decodeBase64(text)], bytes);
    }
    assert.throws(() => decodeBase64("abc"), /not base64/);
  });
});

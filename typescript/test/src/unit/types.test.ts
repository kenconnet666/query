import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { CalendarDate, CalendarDateTime, parseDate } from "@internationalized/date";
import { bigint, boolean, bytes, date, decimal, double, integer, text, timestamp, typeOf } from "@zerodep-dev/query-core";
import { Decimal } from "decimal.js";
import { sapDate } from "../fixtures/erp.js";

describe("值类型", () => {
  it("时间戳读成 CalendarDateTime，绑定时写成秒加去掉尾零的毫秒", () => {
    assert.equal(timestamp.decode("2024-03-31 02:30:00.0000000").toString(), "2024-03-31T02:30:00");
    assert.equal(timestamp.decode("2024-03-31T02:30:00.120").millisecond, 120);
    assert.equal(timestamp.decode("2024-03-31 02:30:00.1239").millisecond, 123, "毫秒以下的部分舍去");
    assert.equal(timestamp.decode("2024-03-31 02:30").toString(), "2024-03-31T02:30:00");
    assert.equal(timestamp.encode(new CalendarDateTime(2024, 3, 31, 2, 30, 5, 500)), "2024-03-31 02:30:05.5");
    assert.equal(timestamp.encode(new CalendarDateTime(2024, 3, 31, 2, 30)), "2024-03-31 02:30:00");
    assert.throws(() => timestamp.decode("2024-03-31 24:00:00"), /invalid time of day/);
  });

  it("日期只接受日历上存在的日子", () => {
    assert.ok(date.decode("2024-02-29").compare(new CalendarDate(2024, 2, 29)) === 0);
    assert.equal(date.decode("2024-02-29 00:00:00").toString(), "2024-02-29");
    assert.equal(date.encode(new CalendarDate(2024, 2, 29)), "2024-02-29");
    assert.throws(() => date.decode("2023-02-29"), /not a calendar date/);
    assert.throws(() => date.decode("2023-2-1"), /YYYY-MM-DD/);
    assert.throws(() => date.decode(new Date() as unknown), /as date/);
  });

  it("绑定时也接受文本形式的小数、日期和时间戳，并按格式校验", () => {
    const loose = (type: { encode(v: never): unknown }, value: string) => type.encode(value as never);
    assert.equal(loose(date, "2024-02-29"), "2024-02-29");
    assert.equal(loose(timestamp, "2024-03-31T02:30:05.5"), "2024-03-31 02:30:05.5");
    assert.equal(loose(decimal, "12.50"), "12.50");
    assert.throws(() => loose(date, "2023-02-29"), /not a calendar date/);
    assert.throws(() => loose(decimal, "1,5"), /not a decimal/);
  });

  it("数值类型接受驱动的文本形式；小数读成 Decimal", () => {
    assert.equal(integer.decode("42"), 42);
    assert.throws(() => integer.decode("9007199254740993"), /safe integer/);
    assert.equal(bigint.decode("9007199254740993"), 9007199254740993n);
    assert.equal(double.decode("1.25"), 1.25);
    assert.ok(decimal.decode(" 3.000 ").eq(3));
    assert.ok(decimal.decode(5).eq(5));
    assert.equal(decimal.encode(new Decimal("1e-7")), "0.0000001", "绑定时不写成科学计数法");
    assert.throws(() => decimal.decode("1,5"), /as decimal/);
  });

  it("布尔值接受各库的写法", () => {
    for (const t of [true, 1, "t", "true", "1"]) assert.equal(boolean.decode(t), true);
    for (const f of [false, 0, "f", "false", "0"]) assert.equal(boolean.decode(f), false);
  });

  it("字节接受 PostgreSQL 的十六进制文本", () => {
    assert.deepEqual(bytes.decode("\\x0aff"), new Uint8Array([10, 255]));
  });

  it("转换器同时作用于参数和结果", () => {
    assert.equal(sapDate.encode(parseDate("2026-01-15")), "20260115");
    assert.equal(sapDate.decode("20260115")?.toString(), "2026-01-15");
    assert.equal(sapDate.decode("        "), null);
    assert.equal(sapDate.kind, "text");
  });

  it("普通值按 JS 类型推断 SQL 类型", () => {
    assert.equal(typeOf("x"), text);
    assert.equal(typeOf(1), integer);
    assert.equal(typeOf(1.5), double);
    assert.equal(typeOf(1n), bigint);
    assert.equal(typeOf(new Decimal(1)), decimal);
    assert.equal(typeOf(new CalendarDate(2026, 1, 1)), date);
    assert.equal(typeOf(new CalendarDateTime(2026, 1, 1, 8, 0)), timestamp);
    assert.throws(() => typeOf(null), /pass a type explicitly/);
  });
});

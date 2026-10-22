import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { bigint, boolean, bytes, date, decimal, double, integer, text, timestamp, typeOf } from "@zerodep-dev/query-core";
import { sapDate } from "../erp.js";

describe("值类型", () => {
  it("时间戳统一为秒加去掉尾零的小数", () => {
    assert.equal(timestamp.decode("2024-03-31 02:30:00.0000000"), "2024-03-31 02:30:00");
    assert.equal(timestamp.decode("2024-03-31T02:30:00.120"), "2024-03-31 02:30:00.12");
    assert.equal(timestamp.decode("2024-03-31 02:30"), "2024-03-31 02:30:00");
    assert.equal(timestamp.encode("2024-03-31T02:30:05.5"), "2024-03-31 02:30:05.5");
    assert.throws(() => timestamp.encode("2024-03-31 24:00:00"), /invalid time of day/);
  });

  it("日期只接受日历上存在的日子", () => {
    assert.equal(date.decode("2024-02-29"), "2024-02-29");
    assert.equal(date.decode("2024-02-29 00:00:00"), "2024-02-29");
    assert.throws(() => date.encode("2023-02-29"), /not a calendar date/);
    assert.throws(() => date.encode("2023-2-1"), /YYYY-MM-DD/);
    assert.throws(() => date.decode(new Date() as unknown), /as date/);
  });

  it("数值类型接受驱动的文本形式", () => {
    assert.equal(integer.decode("42"), 42);
    assert.throws(() => integer.decode("9007199254740993"), /safe integer/);
    assert.equal(bigint.decode("9007199254740993"), 9007199254740993n);
    assert.equal(double.decode("1.25"), 1.25);
    assert.equal(decimal.decode(" 3.000 "), "3.000");
    assert.equal(decimal.decode(5), "5");
    assert.throws(() => decimal.encode("1,5"), /not a decimal/);
  });

  it("布尔值接受各库的写法", () => {
    for (const t of [true, 1, "t", "true", "1"]) assert.equal(boolean.decode(t), true);
    for (const f of [false, 0, "f", "false", "0"]) assert.equal(boolean.decode(f), false);
  });

  it("字节接受 PostgreSQL 的十六进制文本", () => {
    assert.deepEqual(bytes.decode("\\x0aff"), new Uint8Array([10, 255]));
  });

  it("转换器同时作用于参数和结果", () => {
    assert.equal(sapDate.encode("2026-01-15"), "20260115");
    assert.equal(sapDate.decode("20260115"), "2026-01-15");
    assert.equal(sapDate.decode("        "), null);
    assert.equal(sapDate.kind, "text");
  });

  it("普通值按 JS 类型推断 SQL 类型", () => {
    assert.equal(typeOf("x"), text);
    assert.equal(typeOf(1), integer);
    assert.equal(typeOf(1.5), double);
    assert.equal(typeOf(1n), bigint);
    assert.throws(() => typeOf(null), /pass a type explicitly/);
  });
});

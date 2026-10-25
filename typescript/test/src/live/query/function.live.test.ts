import { Decimal } from "decimal.js";
import assert from "node:assert/strict";
import { it } from "node:test";
import { caseOf, caseWhen, count, cte, decimal, from, lit, val, values } from "@zerodep-dev/query-core";
import { Lips, Mara } from "../../fixtures/erp.js";
import { liveSuite } from "../support/live.js";

/**
 * 文本与数值函数，以及 CASE 和 COALESCE：同一个写法在各库上结果相同。数据见 support/sample-data.ts；
 * 大多数断言用 D1（物料 M1、数量 3、单位 EA、销售订单 S1）。与 Java 的 FunctionTest 对应。
 */
liveSuite("文本与数值函数", (live) => {
  it("文本函数取负与多项求和以及按子串分组", async () => {
    const [pieces] = await live.db.list(
      from(Lips)
        .where((l) => l.delivery.eq("D1"))
        .select((l) => ({
          prefix: l.delivery.left(1),
          tail: l.material.substring(2),
          mid: l.unit.substring(1, 1),
          suffix: l.salesOrder.right(1),
          cleaned: l.unit.replace("A", "-"),
          negative: l.quantity.negate(),
          twice: l.quantity.plus(l.quantity),
        })),
    );
    assert.deepEqual({ ...pieces!, negative: Number(pieces!.negative), twice: Number(pieces!.twice) }, { prefix: "D", tail: "1", mid: "E", suffix: "1", cleaned: "E-", negative: -3, twice: 6 });
    const prefixes = await live.db.list(
      from(Lips)
        .groupBy((l) => l.delivery.substring(1, 1))
        .select((l) => ({ prefix: l.delivery.substring(1, 1), n: count() })),
    );
    assert.deepEqual(prefixes, [{ prefix: "D", n: 5 }]);
  });

  it("按 LOCATE 找到的位置截取文本", async () => {
    // 逗号分隔的代码（SAP 在一个字段里存多个 ATA 章节）：逗号之前是 head，之后是 tail；没有逗号时 LOCATE 是 0，head 取整个文本。
    const codes = cte("codes", values([{ text: "21,32" }, { text: "05" }]));
    const rows = await live.db.list(
      from(codes)
        .orderBy((c) => c.text)
        .select((c) => {
          const comma = c.text.locate(",");
          return {
            text: c.text,
            comma,
            head: caseWhen(() => comma.gt(0), c.text.substring(1, comma.minus(lit(1)))).otherwise(c.text),
            tail: caseWhen(() => comma.gt(0), c.text.substring(comma.plus(lit(1)))).end(),
          };
        }),
    );
    assert.deepEqual(rows.map((r) => `${r.text}:${r.comma}:${r.head}:${r.tail}`), ["05:0:05:null", "21,32:3:21:32"]);
  });

  it("简单 CASE 与多参数 COALESCE", async () => {
    // 制造商为空串时取零件号，再为空取 none；M4 的制造商是空串，所以取零件号 P1。
    const rows = await live.db.list(
      from(Mara)
        .orderBy((m) => m.material)
        .select((m) => ({
          material: m.material,
          kind: caseOf(m.materialType).when("ZMAT", "material").otherwise("other"),
          maker: m.manufacturer.nullif("").coalesce(m.partNumber.nullif(""), val("none")),
        })),
    );
    assert.deepEqual(rows.map((r) => `${r.material}:${r.kind}:${r.maker}`), ["M1:material:MK1", "M2:material:MK2", "M3:other:MK1", "M4:other:P1"]);
  });

  it("数量折半后向下与向上取整", async () => {
    const rows = await live.db.list(
      from(Lips)
        .orderBy((l) => l.delivery)
        .select((l) => {
          const half = l.quantity.divide(lit(new Decimal("2")));
          return { delivery: l.delivery, down: half.floor(), up: half.ceil() };
        }),
    );
    assert.deepEqual(rows.map((r) => `${r.delivery}:${Number(r.down)}:${Number(r.up)}`), ["D1:1:2", "D2:1:1", "D3:2:3", "D4:3:4", "D5:0:1"]);
  });

  it("补齐余数乘方开方与符号", async () => {
    const r = await live.db.one(
      from(Lips)
        .where((l) => l.delivery.eq("D1"))
        .select((l) => ({
          padded: l.material.lpad(5, "0"),
          unit: l.unit.rpad(4, "*"),
          remainder: l.quantity.mod(2),
          squared: l.quantity.power(2),
          root: l.quantity.sqrt(),
          sign: l.quantity.minus(lit(new Decimal("4"))).sign(),
        })),
    );
    assert.equal(r.padded, "000M1");
    assert.equal(r.unit, "EA**");
    assert.equal(Number(r.remainder), 1);
    assert.ok(Math.abs(r.squared! - 9) < 1e-9, String(r.squared));
    assert.ok(Math.abs(r.root! - Math.sqrt(3)) < 1e-9, String(r.root));
    assert.equal(r.sign, -1, "3 - 4 的符号");
  });
});

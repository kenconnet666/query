import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { blankAsNull, column, entityMeta, from, ignore, integer, NamingStrategy, readAs, shapeFields, table, trimmed } from "@query/core";
import { ClientEntity, LineVO, Lips, MaraView } from "../erp.js";
import { TestDatabase } from "../support.js";

@table("ORDERS")
class Order {
  @column() orderNumber!: string;
  @column("ITEM_COUNT", integer) items!: number;
}

describe("元数据", () => {
  it("子类继承基类的列，基类的列在前", () => {
    assert.deepEqual(
      entityMeta(Lips).properties.map((p) => p.name),
      ["client", "delivery", "material", "createdOn", "quantity", "unit", "salesOrder"],
    );
    assert.deepEqual(
      entityMeta(ClientEntity).properties.map((p) => p.name),
      ["client"],
      "a subclass never changes its base",
    );
  });

  it("表信息只属于声明它的类", () => {
    assert.deepEqual(entityMeta(Lips).table, { name: "LIPS", schema: "QUERY_IT" });
    assert.equal(entityMeta(ClientEntity).table, undefined);
    class Untabled extends Lips {}
    assert.equal(entityMeta(Untabled).table, undefined);
    assert.throws(() => from(Untabled as never), /Untabled has no @table/);
  });

  it("没写列名的字段按数据库的命名策略推导", () => {
    const q = from(Order).select((o) => ({ ...o }));
    assert.equal(
      new TestDatabase({ naming: NamingStrategy.UPPER_SNAKE }).render(q).sql,
      'SELECT ORDER.ORDER_NUMBER AS "orderNumber", ORDER.ITEM_COUNT AS "items"\nFROM ORDERS ORDER',
    );
    assert.ok(new TestDatabase().render(q).sql.startsWith('SELECT ORDER."orderNumber"'));
    assert.equal(NamingStrategy.LOWER_SNAKE("partNumber2Code"), "part_number2_code");
  });

  it("DTO 的字段来自类字段和装饰器，跳过 ignore", () => {
    assert.deepEqual(shapeFields(LineVO), ["delivery", "materialNo", "createdOn", "text"]);
    assert.deepEqual(entityMeta(LineVO).mapFrom("materialNo"), { entity: Lips, property: "material" });
  });

  it("装饰器只用于公开的实例字段", () => {
    assert.throws(() => {
      class Bad {
        @column("X") static x: string;
      }
      return Bad;
    }, /public instance fields/);
    assert.throws(() => {
      class Hidden {
        @ignore #secret?: string;
      }
      return Hidden;
    }, /public instance fields/);
  });

  it("读取模板每个字段只能有一个且必须包含列占位符", () => {
    const read = (name: string) => entityMeta(MaraView).properties.find((p) => p.name === name)?.read;
    assert.equal(read("partNumber"), "TRIM({0})");
    assert.equal(read("maker"), "NULLIF({0}, '')");
    assert.equal(read("storedMaker"), undefined);
    assert.throws(() => {
      class TwoReads {
        @column("X") @trimmed @blankAsNull x!: string;
      }
      return TwoReads;
    }, /x declares two read templates/);
    assert.throws(() => readAs("TRIM(X)"), /needs \{0\}/);
  });

  it("接收行的 DTO 需要无参构造器", () => {
    class NeedsArgs {
      constructor(readonly x: string) {
        if (x === undefined) throw new Error("x is required");
      }
    }
    assert.throws(() => shapeFields(NeedsArgs), /constructor without arguments/);
  });
});

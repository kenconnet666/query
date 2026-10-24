import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { column, columnType, declaredTable, generatedTypeName, NamingStrategy, propertyName, smallint, table, tableBuilder, typescriptSource } from "@zerodep-dev/query-core";
import { SItemColumns } from "../fixtures/generated/SItemColumns.js";
import { SItem } from "../fixtures/schemas.js";

/** 继承生成的基类，只补上表名。 */
@table("S_ITEM", { schema: "QUERY_IT", comment: "items" })
class PulledItem extends SItemColumns {}

/** 小整数列。 */
@table("T_SMALL")
class Small {
  @column("N", smallint) n!: number | null;
}

const declared = (t: Parameters<typeof declaredTable>[0]) => declaredTable(t, NamingStrategy.AS_IS, (s) => s);

describe("query-pull 生成的源码", () => {
  it("列名和表名转成与 Java 相同的名称", () => {
    assert.equal(propertyName("ORDER_ID"), "orderId");
    assert.equal(propertyName("MATNR"), "matnr");
    assert.equal(propertyName("CLASS"), "classColumn");
    assert.equal(propertyName("1X"), "c1x");
    assert.equal(generatedTypeName("S_ITEM"), "SItemColumns");
    assert.equal(generatedTypeName("CLASS"), "ClassColumns");
  });

  it("继承生成的基类得到与手写声明相同的列", () => {
    assert.deepEqual(declared(PulledItem).columns, declared(SItem).columns);
    assert.deepEqual(declared(PulledItem).primaryKey, declared(SItem).primaryKey);
  });

  it("smallint 列在结构变更里是 SMALLINT，生成时也写回 smallint", () => {
    assert.equal(declared(Small).columns[0]!.type.kind, "SMALLINT");
    const source = typescriptSource(tableBuilder("", "T_SMALL").column("N", columnType("SMALLINT")).build(), "TSmallColumns");
    assert.ok(source.includes(`@column("N", smallint) n!: number | null;`), source);
  });

  it("白名单里的列不存在时报错", () => {
    assert.throws(() => typescriptSource(tableBuilder("", "T").column("A", columnType("TEXT", 10)).build(), "TColumns", ["B"]), /has no column B/);
  });
});

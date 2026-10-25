import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { beforeEach, it } from "node:test";
import { fileURLToPath } from "node:url";
import { generatedTypeName, typescriptSource } from "@zerodep-dev/query-core";
import { LipsBroken, LipsExternal, SItem, SItemV2, SItemV3, SOwner } from "../../fixtures/schemas.js";
import { liveSuite } from "../support/live.js";

/**
 * 结构变更：按声明建表、从实库读回表生成基类、安全的差异自动执行而有风险的要显式允许、外部表只核对声明的列。
 * 表声明在 fixtures/schemas.ts：S_ITEM 的三个版本（初版、安全的变更、有风险的变更）和它引用的 S_OWNER。每个测试先删掉这两张表。
 * 与 Java 的 SchemaTest 对应。
 */
liveSuite("结构变更", (live) => {
  beforeEach(() => live.execute(`-DROP TABLE ${live.table("S_ITEM")}`, `-DROP TABLE ${live.table("S_OWNER")}`)); // S_ITEM 引用 S_OWNER，先删它

  it("按声明建表后与实库一致", async () => {
    const create = await live.db.schema().plan(SItem, SOwner);
    assert.ok(create.changes.every((c) => c.risk === "SAFE"), create.toString());
    // 外键要么写在建表语句里（SQLite 不能事后加外键），要么在全部建表之后单独加。
    if (live.db.dialect.schemaSyntax.inlineForeignKeys) assert.ok(create.statements.some((x) => x.startsWith("CREATE TABLE") && x.includes("FOREIGN KEY")), "foreign keys are inline");
    else assert.ok(create.statements.at(-1)!.includes("FOREIGN KEY"), "foreign keys come last");
    await create.apply();
    const again = await live.db.schema().check(SItem, SOwner);
    assert.ok(again.isEmpty, again.toString());
  });

  it("从实库读回的表生成的基类在各库上相同", async () => {
    await (await live.db.schema().plan(SItem, SOwner)).apply();
    const table = await live.db.schema().read("QUERY_IT", "S_ITEM");
    assert.ok(table !== undefined);
    const source = typescriptSource(table, generatedTypeName(table.name));
    // 期望的源码就是测试夹具，编译通过即证明生成的代码可用；QUERY_SPEC_WRITE=1 时重写它。
    const file = fileURLToPath(new URL("../../../src/fixtures/generated/SItemColumns.ts", import.meta.url));
    const comments = live.db.dialect.schemaSyntax.storesComments;
    if (comments && process.env.QUERY_SPEC_WRITE !== undefined) writeFileSync(file, source, "utf8");
    let expected = readFileSync(file, "utf8").replace(/\r\n/g, "\n");
    // 不保存注释的库（SQLite）读回的表没有注释：期望的源码去掉表和列的注释再比。
    if (!comments) expected = expected.replace("（items）的列", " 的列").replace(', comment: "title"', "").replace("。title */", " */");
    assert.equal(source, expected);
  });

  it("安全的差异自动执行而有风险的要显式允许", async () => {
    await (await live.db.schema().plan(SItem, SOwner)).apply();
    // V2：放宽标题和价格、加 NOTE 列、STATUS 按旧名改成 STATE、加一个索引，五项都安全。
    const safe = await live.db.schema().plan(SItemV2);
    assert.equal(safe.changes.length, 5, safe.toString());
    // SQLite 不能就地改列：放宽标题和价格只报告，改名、加列、加索引照常执行。
    const alters = live.db.dialect.schemaSyntax.altersColumns;
    const reported = alters ? 0 : 2;
    assert.equal(safe.changes.filter((c) => c.risk === "REPORT").length, reported, safe.toString());
    assert.ok(safe.changes.every((c) => c.risk !== "DESTRUCTIVE"), safe.toString());
    await safe.apply();
    const clean = await live.db.schema().check(SItemV2);
    assert.ok(clean.changes.length === reported && clean.changes.every((c) => c.risk === "REPORT"), clean.toString());
    // V3：缩窄标题、创建日期改成 NOT NULL。HANA 不能缩短列，那一项只报告；其余库是有风险的语句；SQLite 全部只报告（价格的放宽也还在）。
    const expected = alters ? 2 : 3;
    const risky = await live.db.schema().plan(SItemV3);
    assert.ok(risky.changes.length === expected && risky.changes.every((c) => c.risk !== "SAFE"), risky.toString());
    assert.deepEqual(risky.statements, [], "nothing runs without allowDestructive");
    assert.equal(risky.pending.length, expected);
    await risky.allowDestructive().apply();
    const done = await live.db.schema().check(SItemV3);
    assert.ok(done.changes.every((c) => c.risk === "REPORT"), done.toString());
    const back = await live.db.schema().check(SItem);
    assert.ok(back.changes.some((c) => c.risk === "REPORT" && c.message.includes("NOTE is not declared")), back.toString());
  });

  it("外部表只核对已声明的列", async () => {
    // 把样例数据的 LIPS 当外部表：只核对声明的两列存在、类型相容，不建也不改。
    assert.ok((await live.db.schema().check(LipsExternal)).isEmpty);
    const broken = await live.db.schema().check(LipsBroken);
    assert.equal(broken.changes.length, 2, broken.toString());
    assert.ok(broken.changes.every((c) => c.risk === "REPORT" && c.statements.length === 0));
    assert.throws(() => live.db.schema().createTable(LipsExternal), /external/);
  });
});

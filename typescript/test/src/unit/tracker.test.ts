import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Doc, Seq } from "../fixtures/writes.js";
import { FakeDriver, TestDatabase } from "../fixtures/support.js";

/** 每条语句都报告改了一行的数据库。 */
function database(): { db: TestDatabase; driver: FakeDriver } {
  const driver = new FakeDriver(() => ({ columns: [], rows: [], affected: 1 }));
  return { db: new TestDatabase({}, driver), driver };
}

describe("实体追踪", () => {
  it("范围结束时只为变了的行发一条按主键和版本的 UPDATE，并把版本写回对象", async () => {
    const { db, driver } = database();
    const changed = { id: "D1", title: "a", version: 1 };
    await db.tracking(async (t) => {
      t.track(Doc, changed).title = "b";
      t.track(Doc, { id: "D2", title: "c", version: 1 });
    });
    assert.equal(driver.statements.length, 1);
    assert.equal(driver.statements[0]!.sql, "UPDATE QUERY_IT.W_DOC DOC\nSET TITLE = ?, VERSION = (DOC.VERSION + 1)\nWHERE DOC.ID = ? AND DOC.VERSION = ?");
    assert.deepEqual(driver.statements[0]!.parameters, ["b", "D1", 1]);
    assert.equal(changed.version, 2);
  });

  it("同一主键再次登记时返回先登记的对象，forget 之后可以重新登记", async () => {
    const { db, driver } = database();
    await db.tracking(async (t) => {
      const first = t.track(Doc, { id: "D1", title: "a", version: 1 });
      assert.equal(t.track(Doc, { id: "D1", title: "b", version: 1 }), first);
      t.forget(first);
      const other = { id: "D1", title: "c", version: 1 };
      assert.equal(t.track(Doc, other), other);
    });
    assert.equal(driver.statements.length, 0);
  });

  it("缺少主键或带着表里没有的字段的行不能登记", async () => {
    const { db } = database();
    await db.tracking(async (t) => {
      assert.throws(() => t.track(Doc, { id: null, title: "a", version: 1 }));
      assert.throws(() => t.track(Seq, { id: 1, title: "a" }), /title has no column/);
    });
  });
});

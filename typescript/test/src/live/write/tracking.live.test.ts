import assert from "node:assert/strict";
import { before, beforeEach, it } from "node:test";
import { deleteFrom, from, insertInto, UnexpectedRowCountError, update } from "@zerodep-dev/query-core";
import { Doc } from "../../fixtures/writes.js";
import { liveSuite } from "../support/live.js";

/**
 * 追踪范围：范围里读出的可变行登记快照，范围结束时只把改了的行按主键和版本写回，并把新版本写回对象。
 * 表是 fixtures/writes.ts 的 W_DOC（版本列做乐观锁）；每个测试开始时只有 D1（标题 a）和 D2（标题 b），版本都是 1。
 * 与 Java 的 TrackingTest 对应。
 */
liveSuite("追踪范围", (live) => {
  before(() => live.recreate(Doc));

  beforeEach(async () => {
    await live.db.execute(deleteFrom(Doc).all());
    await live.db.execute(insertInto(Doc).values({ id: "D1", title: "a", version: 1 }, { id: "D2", title: "b", version: 1 }));
  });

  /** 一份文档的可变行。 */
  const doc = (id: string) => from(Doc).where((d) => d.id.eq(id)).select((d) => ({ id: d.id, title: d.title, version: d.version }));
  /** 库里每份文档写成 编号:标题:版本。 */
  const docs = async () => (await live.db.list(from(Doc).orderBy((d) => d.id).select((d) => ({ id: d.id, title: d.title, version: d.version })))).map((r) => `${r.id}:${r.title}:${r.version}`);

  it("范围结束时只写回改了的行并把新版本写回对象", async () => {
    const d1 = await live.db.tracking(async (t) => {
      const all = await t.list(Doc, from(Doc).orderBy((d) => d.id).select((d) => ({ id: d.id, title: d.title, version: d.version })));
      all[0]!.title = "a2";
      return all[0]!;
    });
    assert.deepEqual(await docs(), ["D1:a2:2", "D2:b:1"]);
    assert.equal(d1.version, 2);
    await live.db.tracking(async (t) => {
      const again = await t.one(Doc, doc("D1"));
      again.title = "a3";
      again.title = "a2";
    });
    assert.deepEqual(await docs(), ["D1:a2:2", "D2:b:1"], "a row changed back is not written");
  });

  it("同一主键只登记一份且嵌套范围由最外层写回", async () => {
    await live.db.tracking(async (t) => {
      const first = await t.one(Doc, doc("D1"));
      first.title = "x";
      await live.db.tracking(async (inner) => {
        const same = await inner.one(Doc, doc("D1"));
        assert.equal(same, first);
        same.title = "y";
      });
      assert.deepEqual(await docs(), ["D1:a:1", "D2:b:1"], "the inner scope does not write");
    });
    assert.deepEqual(await docs(), ["D1:y:2", "D2:b:1"]);
  });

  it("范围抛出异常或写回冲突时不写也不改对象的版本", async () => {
    await assert.rejects(
      live.db.tracking(async (t) => {
        (await t.one(Doc, doc("D1"))).title = "lost";
        throw new Error("abort");
      }),
      /abort/,
    );
    assert.deepEqual(await docs(), ["D1:a:1", "D2:b:1"]);
    // 范围里读出 D1 后，别处先改了它（版本变成 2），写回时版本对不上。
    let held: { version: number } | undefined;
    await assert.rejects(
      live.db.tracking(async (t) => {
        const d = await t.one(Doc, doc("D1"));
        held = d;
        d.title = "mine";
        await live.db.execute(update(Doc).byId({ id: "D1", title: "theirs", version: 1 }));
      }),
      UnexpectedRowCountError,
    );
    assert.deepEqual(await docs(), ["D1:theirs:2", "D2:b:1"]);
    assert.equal(held!.version, 1, "a failed write leaves the version as read");
  });
});

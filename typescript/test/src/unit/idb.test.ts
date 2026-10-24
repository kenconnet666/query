import "fake-indexeddb/auto";
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { BatchWriteError, bigint, InvalidRowError, openLocal, QuotaError, TransactionClosedError, type LocalDatabase } from "@zerodep-dev/idb";
import { Cell, Comment, Counter, Note, note, User } from "../fixtures/local.js";

/** IndexedDB 本地存储（0010）的单元测试，跑在 fake-indexeddb 上；自动提交时机与多标签页另由浏览器实测覆盖。 */

let databases = 0;
const STORES = [User, Note, Comment, Counter, Cell];

/** 每个测试一个新库。 */
function open(version = 1): Promise<LocalDatabase> {
  return openLocal(`test-${++databases}`, { version, stores: STORES });
}

/** 几条便签：n1 到 n5，属于 u1 和 u2，带标签和置顶。 */
async function seeded(): Promise<LocalDatabase> {
  const db = await open();
  await db.putAll(Note, [
    note("n1", { owner: "u1", tags: ["work", "home"], title: "Meeting notes" }),
    note("n2", { owner: "u1", tags: ["work"], pinned: true, title: "groceries" }),
    note("n3", { owner: "u2", tags: ["home"], title: "MEETING agenda", dueOn: "2026-02-01" }),
    note("n4", { owner: "u2", tags: [], pinned: true, title: "Travel" }),
    note("n5", { owner: "u1", tags: ["work", "travel"], title: "meetup" }),
  ]);
  return db;
}

const ids = (rows: readonly Note[]) => rows.map((n) => n.id);

describe("IndexedDB 本地存储", () => {
  it("写入后按主键读回的是声明的类的实例，影子属性不出现在行上", async () => {
    const db = await open();
    await db.put(Note, note("n1", { pinned: true }));
    const n = (await db.get(Note, "n1"))!;
    assert.ok(n instanceof Note);
    assert.equal(n.titleLength(), "Title n1".length);
    assert.equal(n.pinned, true);
    assert.ok(n.createdAt instanceof Date);
    assert.equal("$idx" in n, false);
    assert.equal(await db.get(Note, "missing"), undefined);
    await db.delete(Note, "n1");
    assert.equal(await db.get(Note, "n1"), undefined);
  });

  it("add 遇到已有的主键或违反唯一索引时报错", async () => {
    const db = await open();
    await db.add(Note, note("n1"));
    await assert.rejects(db.add(Note, note("n1")), (e: Error) => e.name === "ConstraintError");
    await assert.rejects(db.put(Note, note("n2", { slug: "slug-n1" })), (e: Error) => e.name === "ConstraintError");
    assert.equal(await db.from(Note).all().count(), 1);
  });

  it("索引字段为空或不是合法的键时写入报错并指出字段，声明 optional 的可以为空", async () => {
    const db = await open();
    await assert.rejects(db.put(Note, note("n1", { owner: undefined as never })), (e: InvalidRowError) => e instanceof InvalidRowError && /owner is undefined/.test(e.message) && /optional: true/.test(e.message));
    await assert.rejects(db.put(Note, note("n1", { slug: true as never })), /slug is boolean true, which is not a valid IndexedDB key/);
    await assert.rejects(db.put(Note, note("n1", { tags: [{}] as never })), /tags must be an array of valid IndexedDB keys/);
    await db.put(Note, note("n1", { dueOn: null }));
    assert.deepEqual(await db.from(Note).where("dueOn").ge("").keys(), [], "an empty optional field keeps the row out of that index");
  });

  it("where 的名字不是索引时构造查询就报错并列出已有的索引", async () => {
    const db = await open();
    assert.throws(() => db.from(Note).where("createdAt" as never), /notes has no index createdAt; its indexes are owner, slug, tags, pinned, title, dueOn, owner\+createdAt, id \(the primary key\)/);
    assert.throws(() => db.from(Note).all().include("title" as never), /no relation title/);
    class NotDeclared {}
    assert.throws(() => db.from(NotDeclared), /NotDeclared is not among the stores passed to openLocal/);
    await assert.rejects(openLocal("bad", { version: 1, stores: [NotDeclared] }), /NotDeclared is not a store; add @store/);
  });

  it("按索引的等于、任一、区间、前缀与大小比较，结果按索引顺序", async () => {
    const db = await seeded();
    assert.deepEqual(ids(await db.from(Note).where("owner").eq("u1").list()), ["n1", "n2", "n5"]);
    assert.deepEqual(ids(await db.from(Note).where("owner").anyOf(["u2", "u1", "u2"]).list()), ["n1", "n2", "n5", "n3", "n4"]);
    assert.deepEqual(ids(await db.from(Note).where("id").between("n2", "n4").list()), ["n2", "n3", "n4"]);
    assert.deepEqual(ids(await db.from(Note).where("id").between("n2", "n4", { upperOpen: true }).list()), ["n2", "n3"]);
    assert.deepEqual(ids(await db.from(Note).where("id").gt("n3").list()), ["n4", "n5"]);
    assert.deepEqual(ids(await db.from(Note).where("id").le("n2").list()), ["n1", "n2"]);
    assert.deepEqual(ids(await db.from(Note).where("slug").startsWith("slug-n").limit(2).list()), ["n1", "n2"]);
    assert.deepEqual(await db.from(Note).where("owner").eq("u2").keys(), ["n3", "n4"]);
    assert.equal(await db.from(Note).where("owner").eq("u1").count(), 3);
    assert.equal((await db.from(Note).where("owner").eq("nobody").first()), undefined);
  });

  it("倒序、跳过与限制行数，内存里的 scan 与 sortBy 在分页之前", async () => {
    const db = await seeded();
    const u1 = db.from(Note).where("owner").eq("u1");
    assert.deepEqual(ids(await u1.reverse().list()), ["n5", "n2", "n1"]);
    assert.deepEqual(ids(await u1.offset(1).limit(1).list()), ["n2"]);
    assert.deepEqual(ids(await u1.reverse().offset(1).list()), ["n2", "n1"]);
    assert.deepEqual(ids(await db.from(Note).all().scan((n) => n.title.length > 7).offset(1).list()), ["n2", "n3"]);
    assert.equal(await db.from(Note).all().scan((n) => n.pinned).count(), 2);
    assert.deepEqual(ids(await db.from(Note).all().sortBy((a, b) => a.title.localeCompare(b.title)).limit(2).list()), ["n2", "n3"]);
    assert.equal((await u1.reverse().first())!.id, "n5");
  });

  it("多值索引每行只返回一次，编码器让布尔可查、文本不分大小写", async () => {
    const db = await seeded();
    // 按索引的顺序：先 home（n1、n3），再 work（n1 已出现过，n2、n5）
    assert.deepEqual(ids(await db.from(Note).where("tags").anyOf(["work", "home"]).list()), ["n1", "n3", "n2", "n5"]);
    assert.equal(await db.from(Note).where("tags").anyOf(["work", "home"]).count(), 4);
    assert.deepEqual(ids(await db.from(Note).where("tags").between("h", "x").list()), ["n1", "n3", "n5", "n2"]);
    assert.deepEqual(ids(await db.from(Note).where("pinned").eq(true).list()), ["n2", "n4"]);
    assert.deepEqual(ids(await db.from(Note).where("title").startsWith("MEET").list()), ["n3", "n1", "n5"]);
    assert.deepEqual(ids(await db.from(Note).where("title").eq("travel").list()), ["n4"]);
  });

  it("复合索引按前缀查区间，复合主键与 bigint 主键按顺序读回", async () => {
    const db = await seeded();
    const from = new Date(Date.UTC(2026, 0, 2));
    const until = new Date(Date.UTC(2026, 0, 31));
    assert.deepEqual(ids(await db.from(Note).where(["owner", "createdAt"]).between(["u1", from], ["u1", until]).list()), ["n2", "n5"]);
    await db.putAll(Cell, [
      { row: 2, col: 1, value: "b" },
      { row: 1, col: 2, value: "a2" },
      { row: 1, col: 1, value: "a1" },
    ]);
    assert.deepEqual((await db.from(Cell).all().list()).map((c) => c.value), ["a1", "a2", "b"]);
    assert.equal((await db.get(Cell, [1, 2]))!.value, "a2");
    assert.deepEqual(await db.from(Cell).where(["row", "col"]).ge([1, 2]).keys(), [[1, 2], [2, 1]]);
    await db.putAll(Counter, [-100n, -5n, 0n, 7n, 12n, 10n ** 30n].map((n) => ({ n, label: String(n) })));
    assert.deepEqual(await db.from(Counter).all().keys(), [-100n, -5n, 0n, 7n, 12n, 10n ** 30n]);
    assert.deepEqual((await db.from(Counter).where("n").between(-5n, 7n).list()).map((c) => c.n), [-5n, 0n, 7n]);
    assert.equal((await db.get(Counter, 12n))!.label, "12");
  });

  it("bigint 编码器在正负、位数不同时都保持大小顺序", () => {
    const values = [-(10n ** 40n), -12345n, -999n, -1n, 0n, 1n, 9n, 10n, 999n, 10n ** 40n];
    const encoded = values.map((v) => bigint.encode(v) as string);
    assert.deepEqual([...encoded].sort(), encoded);
    assert.deepEqual(encoded.map((e) => bigint.decode!(e)), values);
  });

  it("按查询删除和改写匹配的行，改写不能改主键", async () => {
    const db = await seeded();
    assert.equal(await db.from(Note).where("owner").eq("u2").modify({ pinned: false }), 2);
    assert.deepEqual(ids(await db.from(Note).where("pinned").eq(true).list()), ["n2"]);
    assert.equal(await db.from(Note).where("tags").anyOf(["travel"]).modify((n) => n.tags.push("trip")), 1);
    assert.deepEqual((await db.get(Note, "n5"))!.tags, ["work", "travel", "trip"]);
    await assert.rejects(db.from(Note).where("id").eq("n1").modify({ id: "x" }), /cannot change the primary key/);
    assert.equal(await db.from(Note).where("owner").eq("u1").scan((n) => n.pinned).delete(), 1);
    assert.deepEqual(ids(await db.from(Note).all().list()), ["n1", "n3", "n4", "n5"]);
  });

  it("事务正常结束时提交，抛出异常时整体回滚，嵌套调用加入外层", async () => {
    const db = await open();
    await assert.rejects(
      db.transaction([Note, User], "readwrite", async (tx) => {
        await tx.put(User, { id: "u1", name: "Ann" });
        await tx.put(Note, note("n1"));
        throw new Error("boom");
      }),
      /boom/,
    );
    assert.equal(await db.get(User, "u1"), undefined);
    await db.transaction([Note, User], "readwrite", async (tx) => {
      await tx.put(User, { id: "u1", name: "Ann" });
      await tx.transaction([Note], "readwrite", (inner) => inner.put(Note, note("n1")));
      assert.equal((await tx.from(Note).all().count()), 1, "the transaction sees its own writes");
    });
    assert.equal((await db.get(User, "u1"))!.name, "Ann");
    await assert.rejects(db.transaction([Note], "readwrite", (tx) => tx.transaction([User], "readonly", async () => undefined)), /\[users\] are not in this transaction/);
    await assert.rejects(db.transaction([Note], "readonly", (tx) => tx.put(Note, note("n2"))), (e: Error) => e.name === "ReadOnlyError");
    await assert.rejects(db.transaction([Note], "readonly", (tx) => tx.transaction([Note], "readwrite", async () => undefined)), /is readonly/);
  });

  it("回调里 await 了别的工作后再用 tx，报出明确的错误而不是 TransactionInactiveError", async () => {
    const db = await open();
    await assert.rejects(
      db.transaction([Note], "readwrite", async (tx) => {
        await tx.put(Note, note("n1"));
        await new Promise((resolve) => setTimeout(resolve, 20));
        await tx.put(Note, note("n2"));
      }),
      (e: Error) => e instanceof TransactionClosedError && /awaited non-IndexedDB work/.test(e.message),
    );
    assert.deepEqual(await db.from(Note).all().keys(), ["n1"], "what was written before the await had committed");
  });

  it("批量写入任何一行失败时都不写，错误里列出全部失败的行", async () => {
    const db = await open();
    await db.put(Note, note("n2"));
    await assert.rejects(db.addAll(Note, [note("n1"), note("n2"), note("n3", { owner: null as never }), note("n4")]), (e: BatchWriteError) => e instanceof BatchWriteError && e.failures.length === 1 && e.failures[0]!.index === 2);
    await assert.rejects(db.addAll(Note, [note("n1"), note("n2"), note("n3"), note("n4", { slug: "slug-n1" })]), (e: BatchWriteError) => e instanceof BatchWriteError && e.failures.map((f) => f.index).join() === "1,3");
    assert.deepEqual(await db.from(Note).all().keys(), ["n2"]);
  });

  it("include 按多对一的主键和一对多的索引批量装回关系，关系字段不存进记录", async () => {
    const db = await seeded();
    await db.putAll(User, [
      { id: "u1", name: "Ann" },
      { id: "u2", name: "Bob" },
    ]);
    await db.putAll(Comment, [
      { id: "c1", noteId: "n1", text: "a" },
      { id: "c2", noteId: "n1", text: "b" },
      { id: "c3", noteId: "n3", text: "c" },
    ]);
    const rows = await db.from(Note).where("id").anyOf(["n1", "n3", "n4"]).include("user", "comments").list();
    assert.deepEqual(
      rows.map((n) => [n.id, n.user?.name, n.comments?.map((c) => c.text)]),
      [
        ["n1", "Ann", ["a", "b"]],
        ["n3", "Bob", ["c"]],
        ["n4", "Bob", []],
      ],
    );
    assert.ok(rows[0]!.user instanceof User);
    await db.put(Note, rows[0]!);
    assert.equal((await db.get(Note, "n1"))!.user, undefined, "relations are not stored");
  });
});

describe("IndexedDB 的打开与升级", () => {
  /** 版本 1 的便签：没有 slug 的唯一索引，多一个 tags 之外的旧索引。 */
  it("升级时建缺的仓库与索引、删多出的索引，按版本执行数据迁移；结构变了而版本没变时报错", async () => {
    const name = `upgrade-${++databases}`;
    const v1 = await openLocal(name, { version: 1, stores: [User] });
    await v1.put(User, { id: "u1", name: "ann" });
    v1.close();
    await assert.rejects(openLocal(name, { version: 1, stores: [User, Note] }), /the store notes is missing.*raise version to 2/);
    const steps: number[] = [];
    const v3 = await openLocal(name, {
      version: 3,
      stores: [User, Note],
      upgrade: {
        2: async (tx) => {
          steps.push(2);
          for (const u of await tx.from(User).all().list()) await tx.put(User, { ...u, name: u.name.toUpperCase() });
        },
        3: async (tx, info) => {
          steps.push(3, info.oldVersion);
          await tx.put(Note, note("n1"));
        },
        4: () => {
          steps.push(4);
        },
      },
    });
    assert.deepEqual(steps, [2, 3, 1]);
    assert.equal((await v3.get(User, "u1"))!.name, "ANN");
    assert.equal(await v3.from(Note).where("owner").eq("u1").count(), 1);
    v3.close();
    const reopened = await openLocal(name, { version: 3, stores: [User] });
    assert.equal(reopened.version, 3, "a store in the browser that is no longer declared is kept");
    reopened.close();
    const dropped = await openLocal(name, { version: 4, stores: [User], dropStores: ["notes"] });
    assert.throws(() => dropped.from(Note), /Note is not among the stores/);
    dropped.close();
    await assert.rejects(openLocal(name, { version: 4, stores: [User, Note] }), /notes is missing/);
  });

  it("升级的数据迁移失败时不升级，库保持原来的版本", async () => {
    const name = `upgrade-${++databases}`;
    (await openLocal(name, { version: 1, stores: [User] })).close();
    await assert.rejects(
      openLocal(name, {
        version: 2,
        stores: [User, Note],
        upgrade: {
          2: () => {
            throw new Error("bad step");
          },
        },
      }),
      /bad step/,
    );
    const db = await openLocal(name, { version: 1, stores: [User] });
    assert.equal(db.version, 1);
    db.close();
  });

  it("别的连接要升级时本连接关闭并通知", async () => {
    const name = `upgrade-${++databases}`;
    let notified = 0;
    const old = await openLocal(name, { version: 1, stores: [User], onVersionChange: () => notified++ });
    const next = await openLocal(name, { version: 2, stores: [User, Note] });
    assert.equal(notified, 1);
    await assert.rejects(old.get(User, "u1"), /is closed/);
    next.close();
  });
});

describe("IndexedDB 的实时查询与配额", () => {
  /** 等到 check 成立，至多 2 秒。 */
  async function until(check: () => boolean): Promise<void> {
    for (let i = 0; i < 200 && !check(); i++) await new Promise((resolve) => setTimeout(resolve, 10));
    assert.ok(check(), "timed out");
  }

  it("写入提交后读过这个仓库的实时查询重新执行，写别的仓库不触发，停止后不再回调", async () => {
    const db = await open();
    const seen: string[][] = [];
    const stop = db.from(Note).where("owner").eq("u1").live((rows) => seen.push(ids(rows)));
    await until(() => seen.length === 1);
    await db.put(Note, note("n1"));
    await until(() => seen.length === 2);
    await db.put(User, { id: "u1", name: "Ann" });
    await db.transaction([Note], "readwrite", async (tx) => {
      await tx.put(Note, note("n2"));
      await tx.put(Note, note("n3", { owner: "u2" }));
    });
    await until(() => seen.length === 3);
    await assert.rejects(db.transaction([Note], "readwrite", async (tx) => {
      await tx.put(Note, note("n4"));
      throw new Error("rolled back");
    }));
    stop();
    await db.put(Note, note("n5"));
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.deepEqual(seen, [[], ["n1"], ["n1", "n2"]], "a rolled-back write and writes after stop() do not notify");
    await assert.rejects(db.transaction([Note], "readonly", async (tx) => tx.from(Note).all().live(() => undefined)), /runs on the database/);
    db.close();
  });

  it("另一个连接（另一个标签页）提交的写入经由 BroadcastChannel 让实时查询重新执行", async () => {
    const name = `tabs-${++databases}`;
    const a = await openLocal(name, { version: 1, stores: STORES });
    const b = await openLocal(name, { version: 1, stores: STORES });
    const seen: string[][] = [];
    const stop = a.from(Note).all().include("user").live((rows) => seen.push(rows.map((n) => `${n.id}:${n.user?.name ?? ""}`)));
    await until(() => seen.length === 1);
    await b.put(Note, note("n1"));
    await until(() => seen.length === 2);
    await b.put(User, { id: "u1", name: "Ann" });
    await until(() => seen.length === 3);
    assert.deepEqual(seen, [[], ["n1:"], ["n1:Ann"]], "writes to an included store notify too");
    stop();
    a.close();
    b.close();
  });

  it("配额用尽的写入报 QuotaError，事务回滚", async () => {
    const db = await open();
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (this: IDBObjectStore, ...args: Parameters<IDBObjectStore["put"]>) {
      if (this.name === "comments") throw new DOMException("quota", "QuotaExceededError");
      return put.apply(this, args);
    };
    try {
      await assert.rejects(
        db.transaction([Note, Comment], "readwrite", async (tx) => {
          await tx.put(Note, note("n1"));
          await tx.put(Comment, { id: "c1", noteId: "n1", text: "x" });
        }),
        (e: Error) => e instanceof QuotaError && (e.cause as DOMException).name === "QuotaExceededError",
      );
    } finally {
      IDBObjectStore.prototype.put = put;
    }
    assert.equal(await db.get(Note, "n1"), undefined);
    assert.equal(await db.persist(), false, "Node has no StorageManager");
  });
});

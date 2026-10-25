import { parseDateTime } from "@internationalized/date";
import assert from "node:assert/strict";
import { before, it } from "node:test";
import { deleteFrom, filter, from, insertInto, JoinDelete, JoinUpdate, onInsert, onUpdate, QueryExecutionError, UnexpectedRowCountError, update, val } from "@zerodep-dev/query-core";
import { AppNote, Audited, Code, Doc, Note, Seq, Tenanted, Ticket } from "../../fixtures/writes.js";
import { liveSuite } from "../support/live.js";

/**
 * 插入、更新与删除：期望的行数、事务回滚、多行插入、乐观锁、差异更新、生成的主键、插入或更新、联表写、逻辑删除与审计填充、
 * 具名过滤器和 schema 映射。表声明在 fixtures/writes.ts，每个库按声明建一次：
 * - W_NOTE 便签（文本主键、租户、标题、数量、状态）；
 * - W_DOC 文档（版本列做乐观锁，SOURCE 由数据库默认值 'db' 维护，本库不写）；
 * - W_SEQ 数据库生成主键的表；W_CODE 编码表（逻辑删除记删除时刻的毫秒数，编码加删除时刻唯一）；
 * - W_TICKET 工单（DELETED 为 1 是逻辑删除，另有创建时间、修改时间和修改人三个审计列）。
 * 每个测试先清空自己用的表。与 Java 的 WriteTest 对应。
 */
liveSuite("插入、更新与删除", (live) => {
  before(() => live.recreate(Note, Doc, Seq, Code, Ticket));

  /** 两张便签：N1 标题 first、数量 1；N2 没有标题、数量 2；都属于租户 T1、状态 open。 */
  const twoNotes = () => insertInto(Note).values({ id: "N1", tenant: "T1", title: "first", qty: 1, status: "open" }, { id: "N2", tenant: "T1", title: null, qty: 2, status: "open" });
  /** 全部便签的编号。 */
  const noteIds = () => live.db.list(from(Note).orderBy((n) => n.id).select((n) => n.id));
  /** 全部文档写成 编号:标题:版本。 */
  const docs = async () => (await live.db.list(from(Doc).orderBy((d) => d.id).select((d) => ({ id: d.id, title: d.title, version: d.version })))).map((r) => `${r.id}:${r.title}:${r.version}`);

  it("插入更新删除按期望的行数执行", async () => {
    await live.db.execute(deleteFrom(Note).all());
    assert.equal(await live.db.execute(twoNotes()), 2);
    // 比较后更新：只改开着的便签，期望改到一行，改到 0 行（已经被关了）就报错。
    const closeN1 = update(Note)
      .set((n) => ({ qty: n.qty.plus(val(10)), status: "closed" }))
      .where((n) => {
        n.id.eq("N1");
        n.status.eq("open");
      })
      .expect(1);
    assert.equal(await live.db.execute(closeN1), 1);
    const stale = await live.db.execute(closeN1).then(() => undefined, (e: unknown) => e);
    assert.ok(stale instanceof UnexpectedRowCountError && stale.actual === 0, String(stale));
    const rows = await live.db.list(from(Note).orderBy((n) => n.id).select((n) => ({ id: n.id, title: n.title, qty: n.qty, status: n.status })));
    assert.deepEqual(rows.map((r) => `${r.id}:${r.title}:${r.qty}:${r.status}`), ["N1:first:11:closed", "N2:null:2:open"]);
    // 把查询结果插入表里：复制 N1 为 N9；再删掉关闭的 N1、N9。
    assert.equal(await live.db.execute(insertInto(Note).from(from(Note).where((n) => n.id.eq("N1")).select((n) => ({ id: val("N9"), tenant: n.tenant, title: n.title, qty: n.qty, status: n.status })))), 1);
    assert.equal(await live.db.execute(deleteFrom(Note).where((n) => n.status.eq("closed"))), 2);
    assert.deepEqual(await noteIds(), ["N2"]);
  });

  it("事务里出错时写入整体回滚", async () => {
    await live.db.execute(deleteFrom(Note).all());
    await assert.rejects(
      live.db.transaction(async () => {
        await live.db.execute(twoNotes());
        assert.deepEqual(await noteIds(), ["N1", "N2"], "the transaction sees its own rows");
        throw new Error("roll back");
      }),
      /roll back/,
    );
    assert.deepEqual(await noteIds(), []);
    await live.db.transaction(() => live.db.execute(twoNotes()));
    assert.deepEqual(await noteIds(), ["N1", "N2"]);
  });

  it("多行插入在一个事务里执行", async () => {
    await live.db.execute(deleteFrom(Note).all());
    const rows = Array.from({ length: 120 }, (_, i) => ({ id: `B${i}`, tenant: "T1", title: "bulk", qty: i, status: "open" }));
    assert.equal(await live.db.execute(insertInto(Note).values(...rows)), 120);
    assert.equal(await live.db.count(from(Note).select((n) => n.id)), 120);
    assert.equal(await live.db.execute(deleteFrom(Note).where((n) => n.title.eq("bulk"))), 120);
  });

  it("按主键更新和删除时版本不对就报乐观锁冲突", async () => {
    await live.db.execute(deleteFrom(Doc).all());
    await live.db.execute(insertInto(Doc).values({ id: "D1", title: "a", version: 1 }));
    // byId 按主键和版本改，版本加一；行里的版本与库里不同就改到 0 行，报冲突。
    assert.equal(await live.db.execute(update(Doc).byId({ id: "D1", title: "b", version: 1 })), 1);
    assert.deepEqual(await docs(), ["D1:b:2"]);
    await assert.rejects(live.db.execute(update(Doc).byId({ id: "D1", title: "c", version: 1 })), UnexpectedRowCountError);
    await assert.rejects(live.db.execute(deleteFrom(Doc).byId({ id: "D1", version: 1 })), UnexpectedRowCountError);
    assert.equal(await live.db.execute(deleteFrom(Doc).byId({ id: "D1", version: 2 })), 1);
  });

  it("差异更新只写变了的列且只读列保留数据库默认值", async () => {
    await live.db.execute(deleteFrom(Doc).all());
    await live.db.execute(insertInto(Doc).values({ id: "D1", title: "a", version: 1, source: "app" }));
    assert.equal(await live.db.execute(update(Doc).byId({ id: "D1", title: "a", version: 1 }).diffFrom({ id: "D1", title: "a", version: 1 })), 0, "nothing changed, so nothing runs and the version stays");
    assert.equal(await live.db.execute(update(Doc).byId({ id: "D1", title: "b", version: 1, source: "app" }).diffFrom({ id: "D1", title: "a", version: 1, source: "db" })), 1);
    const rows = await live.db.list(from(Doc).select((d) => ({ title: d.title, version: d.version, source: d.source })));
    assert.deepEqual(rows.map((r) => `${r.title}:${r.version}:${r.source}`), ["b:2:db"], "SOURCE 是只读列：插入和更新都不写它，留着默认值 db");
    assert.equal(await live.db.exists(from(Doc).where((d) => d.id.eq("D1")).select((d) => d.id)), true);
    assert.equal(await live.db.exists(from(Doc).where((d) => d.id.eq("D9")).select((d) => d.id)), false);
  });

  it("插入后按行的顺序取回数据库生成的主键", async () => {
    await live.db.execute(deleteFrom(Seq).all());
    const keys = await live.db.insertKeys<number>(insertInto(Seq).values({ name: "a" }, { name: "b" }));
    assert.equal(keys.length, 2);
    assert.equal(keys[1], keys[0]! + 1);
    const rows = await live.db.list(from(Seq).orderBy((s) => s.id).select((s) => ({ id: s.id, name: s.name })));
    assert.deepEqual(rows.map((r) => `${r.id}:${r.name}`), [`${keys[0]}:a`, `${keys[1]}:b`]);
  });

  it("插入或更新在冲突时更新指定的列或保留原来的行", async () => {
    await live.db.execute(deleteFrom(Doc).all());
    const upsert = (row: { id: string; title: string; version: number }) => insertInto(Doc).values(row).onConflict().update((d) => d.title);
    const ifAbsent = (row: { id: string; title: string; version: number }) => insertInto(Doc).values(row).onConflict((d) => d.id).doNothing();
    await live.db.execute(upsert({ id: "D1", title: "a", version: 1 }));
    await live.db.execute(upsert({ id: "D1", title: "b", version: 9 })); // 只更新标题，版本还是 1
    await live.db.execute(ifAbsent({ id: "D1", title: "c", version: 5 })); // 保留原来的行
    await live.db.execute(ifAbsent({ id: "D2", title: "d", version: 1 }));
    assert.deepEqual(await docs(), ["D1:b:1", "D2:d:1"]);
  });

  it("联表更新与删除按连接得上的行改动", async () => {
    await live.db.execute(deleteFrom(Note).all());
    await live.db.execute(deleteFrom(Doc).all());
    await live.db.execute(twoNotes());
    await live.db.execute(insertInto(Doc).values({ id: "N1", title: "synced", version: 2 }, { id: "N2", title: "gone", version: 1 }));
    // 把同编号文档的标题抄到便签上，只抄改过的文档（版本大于 1）；再删掉对应文档标题为 gone 的便签。
    assert.equal(await live.db.execute(JoinUpdate.of(Note, Doc, (n, d) => n.id.eq(d.id)).set((_n, d) => ({ title: d.title })).where((_n, d) => d.version.gt(1))), 1);
    assert.equal(await live.db.execute(JoinDelete.of(Note, Doc, (n, d) => n.id.eq(d.id)).where((_n, d) => d.title.eq("gone"))), 1);
    const rows = await live.db.list(from(Note).select((n) => ({ id: n.id, title: n.title })));
    assert.deepEqual(rows.map((r) => `${r.id}:${r.title}`), ["N1:synced"]);
    // 工单有逻辑删除列：联表删除改写成把 DELETED 设为 1 的联表更新。
    await live.db.execute(deleteFrom(Ticket).physically().unfiltered().all());
    await live.db.execute(insertInto(Ticket).values({ id: "N2", title: "t" }));
    assert.equal(await live.db.execute(JoinDelete.of(Ticket, Doc, (t, d) => t.id.eq(d.id)).where((_t, d) => d.title.eq("gone"))), 1);
    assert.equal(await live.db.count(from(Ticket).select((t) => t.id)), 0, "the ticket is deleted logically");
    assert.equal(await live.db.count(from(Ticket).withDeleted().select((t) => t.id)), 1);
  });

  it("逻辑删除记毫秒数时同一个编码删除后可以再建", async () => {
    await live.db.execute(deleteFrom(Code).physically().unfiltered().all());
    await live.db.execute(insertInto(Code).values({ id: "C1", code: "A" }));
    await assert.rejects(live.db.execute(insertInto(Code).values({ id: "C2", code: "A" })), QueryExecutionError, "the unique key stops a second live row with the same code");
    // 删除把 DELETED 从 0 改成删除时刻的毫秒数，(CODE, DELETED) 不再冲突。
    assert.equal(await live.db.execute(deleteFrom(Code).where((c) => c.code.eq("A"))), 1);
    await live.db.execute(insertInto(Code).values({ id: "C2", code: "A" }));
    assert.deepEqual(await live.db.list(from(Code).select((c) => c.id)), ["C2"]);
    assert.equal(await live.db.count(from(Code).withDeleted().select((c) => c.id)), 2);
  });

  it("逻辑删除与审计填充按预期写入和过滤", async () => {
    // 插入时填创建时间，更新时填修改时间和修改人；删除工单是把 DELETED 设为 1 的更新，所以也填修改人。
    const audited = live.database({
      fills: [onInsert(Audited, (a) => a.createdAt, () => parseDateTime("2026-01-01T08:00:00")), onUpdate(Audited, (a) => a.updatedAt, () => parseDateTime("2026-01-02T09:30:00")), onUpdate(Audited, (a) => a.updatedBy, () => "bob")],
    });
    await audited.execute(deleteFrom(Ticket).physically().unfiltered().all());
    await audited.execute(insertInto(Ticket).values({ id: "T1", title: "a" }, { id: "T2", title: "b" }));
    await audited.execute(update(Ticket).set(() => ({ title: "b2" })).where((t) => t.id.eq("T2")));
    assert.equal(await audited.execute(deleteFrom(Ticket).where((t) => t.id.eq("T1"))), 1);
    assert.equal(await audited.execute(deleteFrom(Ticket).where((t) => t.id.eq("T1"))), 0, "a deleted row is no longer visible");
    await audited.execute(insertInto(Ticket).values({ id: "T3", title: "c" }).onConflict().update());
    await audited.execute(insertInto(Ticket).values({ id: "T3", title: "c2" }).onConflict().update());
    assert.deepEqual(await audited.list(from(Ticket).orderBy((t) => t.id).select((t) => t.id)), ["T2", "T3"]);
    const rows = await audited.list(from(Ticket).unfiltered().orderBy((t) => t.id).select((t) => ({ id: t.id, title: t.title, deleted: t.deleted, createdAt: t.createdAt, updatedBy: t.updatedBy })));
    assert.deepEqual(
      rows.map((r) => `${r.id}:${r.title}:${r.deleted}:${r.createdAt}:${r.updatedBy}`),
      ["T1:a:1:2026-01-01T08:00:00:bob", "T2:b2:0:2026-01-01T08:00:00:bob", "T3:c2:0:2026-01-01T08:00:00:bob"],
      "the create time is written once and survives the upsert",
    );
    assert.equal(await audited.execute(deleteFrom(Ticket).physically().unfiltered().where((t) => t.id.eq("T1"))), 1);
  });

  it("具名过滤器与 schema 映射生效", async () => {
    // 具名过滤器 tenant 只看租户 T9；表声明里的 schema APP 映射成 QUERY_IT。
    const scoped = live.database({ filters: [filter("tenant", Tenanted, (t) => t.tenant.eq("T9"))], schemas: { APP: "QUERY_IT" } });
    await scoped.execute(deleteFrom(Note).unfiltered().all());
    await scoped.execute(twoNotes());
    assert.deepEqual(await scoped.list(from(Note).select((n) => n.id)), [], "the tenant filter hides tenant T1");
    assert.deepEqual(await scoped.list(from(Note).unfiltered("tenant").orderBy((n) => n.id).select((n) => n.id)), ["N1", "N2"]);
    assert.deepEqual(await scoped.list(from(AppNote).orderBy((n) => n.id).select((n) => n.id)), ["N1", "N2"], "APP maps to QUERY_IT");
  });
});

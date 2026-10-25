import assert from "node:assert/strict";
import { before, it } from "node:test";
import { bool, buildTree, from, insertInto, withAncestors, type Ref, type TreeNode } from "@zerodep-dev/query-core";
import { OrgUnit } from "../../fixtures/org.js";
import { liveSuite } from "../support/live.js";

/** 一个单元的行：键、父键和名字。 */
interface UnitRow {
  id: number;
  parentId: number | null;
  name: string;
}

/**
 * 树形数据：懒加载的第一层带 hasChildren，服务端搜索补上祖先再组装成树。每个库建一次表，写入（与 Java 的 TreeDataTest 相同）：
 *
 * ```
 * ORG_UNIT  1 研发（根）→ 2 前端、3 后端 → 4 平台；5 采购（根）
 * ```
 */
liveSuite("树形数据", (live) => {
  before(async () => {
    await live.recreate(OrgUnit);
    await live.database().execute(
      insertInto(OrgUnit).values(
        { id: 1, parentId: null, name: "研发" },
        { id: 2, parentId: 1, name: "前端" },
        { id: 3, parentId: 1, name: "后端" },
        { id: 4, parentId: 3, name: "平台" },
        { id: 5, parentId: null, name: "采购" },
      ),
    );
  });
  const rows = (where: (u: Ref<OrgUnit>) => void) =>
    live.db.list(from(OrgUnit).where(where).orderBy((u) => u.id).select((u) => ({ id: u.id, parentId: u.parentId, name: u.name })));

  it("懒加载：每行带上有没有子节点", async () => {
    const roots = await live.db.list(
      from(OrgUnit)
        .where((u) => u.parentId.isNull())
        .orderBy((u) => u.id)
        .select((u) => ({ name: u.name, hasChildren: bool(() => u.children.any()) })),
    );
    assert.deepEqual(roots, [
      { name: "研发", hasChildren: true },
      { name: "采购", hasChildren: false },
    ]);
  });

  it("db.withAncestors：有递归 CTE 的库一条语句取出命中的行和祖先", async () => {
    const events = await live.db.capture(async () => {
      const all = await live.db.withAncestors(OrgUnit, {
        key: (u) => u.id,
        parent: (u) => u.parentId,
        hits: (u) => u.name.contains("平台"),
        select: (u) => ({ id: u.id, parentId: u.parentId, name: u.name }),
      });
      const names = (nodes: TreeNode<UnitRow>[]): unknown[] => nodes.map((n) => (n.children.length > 0 ? { [n.name]: names(n.children) } : n.name));
      assert.deepEqual(names(buildTree(all, { key: (u) => u.id, parent: (u) => u.parentId })), [{ 研发: [{ 后端: ["平台"] }] }]);
    });
    // HANA 没有递归 CTE：命中的键、两层祖先的键，再取一次列
    assert.equal(events.length, live.is("hana") ? 4 : 1, events.map((e) => e.statement.sql).join("\n\n"));
  });

  it("搜索：命中的行补上祖先，组装成从根开始的树", async () => {
    const hits: UnitRow[] = await rows((u) => u.name.contains("平台"));
    const all = await withAncestors(hits, { key: (u) => u.id, parent: (u) => u.parentId, load: (ids) => rows((u) => u.id.in(ids)) });
    const names = (nodes: TreeNode<UnitRow>[]): unknown[] => nodes.map((n) => (n.children.length > 0 ? { [n.name]: names(n.children) } : n.name));
    assert.deepEqual(names(buildTree(all, { key: (u) => u.id, parent: (u) => u.parentId })), [{ 研发: [{ 后端: ["平台"] }] }]);
  });
});

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { buildTree, TREE_BATCH, withAncestors } from "@zerodep-dev/query-core";

interface Unit {
  id: number;
  parentId: number | null;
  name: string;
}

/** 1 研发 → 2 前端、3 后端 → 4 平台；5 采购。 */
const units: Unit[] = [
  { id: 4, parentId: 3, name: "平台" },
  { id: 1, parentId: null, name: "研发" },
  { id: 2, parentId: 1, name: "前端" },
  { id: 3, parentId: 1, name: "后端" },
  { id: 5, parentId: null, name: "采购" },
];
const shape = { key: (u: Unit) => u.id, parent: (u: Unit) => u.parentId };

/** 树的形状：叶子是名字，有子节点的是 { 名字: 子节点 }。 */
type Node = Unit & { children: Node[] };
const names = (nodes: Node[]): unknown[] => nodes.map((n) => (n.children.length > 0 ? { [n.name]: names(n.children) } : n.name));

describe("树形数据", () => {
  it("buildTree：同级保持原来的顺序，父行不在这批里的当作根", () => {
    assert.deepEqual(names(buildTree(units, shape)), [{ 研发: ["前端", { 后端: ["平台"] }] }, "采购"]);
    assert.deepEqual(names(buildTree(units.slice(0, 1), shape)), ["平台"], "父行不在这批里");
    assert.ok(!("children" in units[0]!), "不改原来的行");
  });

  it("buildTree：withChildren 决定节点的样子；键重复或成环时报错", () => {
    const tree = buildTree(units, { ...shape, withChildren: (u, kids: string[]) => `${u.name}(${kids.join(",")})` });
    assert.deepEqual(tree, ["研发(前端(),后端(平台()))", "采购()"]);
    assert.throws(() => buildTree([units[0]!, units[0]!], shape), /appears twice/);
    assert.throws(() => buildTree([{ id: 1, parentId: 2, name: "a" }, { id: 2, parentId: 1, name: "b" }], shape), /parent cycle/);
  });

  it("withAncestors：一层一层按键补上祖先，已有的不重复取", async () => {
    const asked: number[][] = [];
    const load = async (ids: number[]) => {
      asked.push(ids);
      return units.filter((u) => ids.includes(u.id));
    };
    const rows = await withAncestors([units[0]!, units[2]!], { ...shape, load });
    assert.deepEqual(rows.map((u) => u.name), ["平台", "前端", "研发", "后端"], "补上的祖先按 load 返回的顺序");
    assert.deepEqual(asked, [[3, 1]], "后端和研发在同一层，研发已经在第一批里，不再取第二次");
    assert.deepEqual(names(buildTree(rows, shape)), [{ 研发: ["前端", { 后端: ["平台"] }] }]);
  });

  it("withAncestors：父行不存在时停下，键多时分批", async () => {
    const orphans = Array.from({ length: TREE_BATCH + 3 }, (_, i) => ({ id: 1000 + i, parentId: 5000 + i, name: `x${i}` }));
    const sizes: number[] = [];
    const rows = await withAncestors(orphans, { ...shape, load: async (ids) => (sizes.push(ids.length), []) });
    assert.equal(rows.length, orphans.length);
    assert.deepEqual(sizes, [TREE_BATCH, 3]);
  });
});

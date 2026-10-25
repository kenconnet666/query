/**
 * 树形数据（部门、分类、菜单这类每行带父键的表）的两件常用事：把平铺的行组装成树（同 EasyQuery 的 `toTreeList`），
 * 以及给搜索命中的行补上它们的祖先，前端懒加载的树要在服务端搜索时用（vue-tsx-pro 用 `buildTree` 组装、`filterTree` 展开）：
 *
 *   const hits = await db.list(from(Dept).where((d) => d.name.contains(keyword)).select((d) => ({ ...d })));
 *   const rows = await withAncestors(hits, { key: (d) => d.id, parent: (d) => d.parentId,
 *     load: (ids) => db.list(from(Dept).where((d) => d.id.in(ids)).select((d) => ({ ...d }))) });
 *   const tree = buildTree(rows, { key: (d) => d.id, parent: (d) => d.parentId });
 *
 * 懒加载时每行带上有没有子节点：投影里写 `hasChildren: bool(() => d.children.any())`。整棵子树一次取出时，
 * PostgreSQL、MySQL、SQLite 有递归 CTE（方言包的 `recursiveCte`），HANA 有层级函数；这里的写法与方言无关。与 Java 的 `Trees` 相同。
 */

/** 一次按键查询最多带多少个键，避免超出各库的参数个数上限（同子数组的批量查询）。 */
export const TREE_BATCH = 500;

/** buildTree 默认的节点：原来的行加上 children。 */
export type TreeNode<T> = T & { children: TreeNode<T>[] };

/** buildTree 怎样读平铺的行。 */
export interface TreeShape<T, K, N> {
  /** 行的键，这批行里唯一。 */
  readonly key: (row: T) => K;
  /** 父行的键；根行是 null / undefined，父行不在这批行里的也当作根。 */
  readonly parent: (row: T) => K | null | undefined;
  /** 用子节点生成一个节点（不改原行）；不写时是 `{ ...row, children }`。 */
  readonly withChildren?: (row: T, children: N[]) => N;
}

/**
 * 平铺的行组装成树，返回第一层；同级保持 rows 里的顺序，叶子的 children 是空数组。父键为 null、等于自己、或不在这批行里的行当作根。
 * 键重复或父子成环（环上的行到不了第一层）时报错。键按 `String(键)` 比较，所以 1 和 1n 是同一个键。
 */
export function buildTree<T, K, N = TreeNode<T>>(rows: readonly T[], shape: TreeShape<T, K, N>): N[] {
  const make = shape.withChildren ?? ((row: T, children: N[]) => ({ ...row, children }) as N);
  const id = (k: K | null | undefined) => (k == null ? undefined : String(k));
  const keys = new Set<string>();
  for (const row of rows) {
    const k = id(shape.key(row))!;
    if (keys.has(k)) throw new Error(`buildTree: the key ${k} appears twice`);
    keys.add(k);
  }
  const roots: T[] = [];
  const childrenOf = new Map<string, T[]>();
  for (const row of rows) {
    const p = id(shape.parent(row));
    if (p === undefined || p === id(shape.key(row)) || !keys.has(p)) roots.push(row);
    else childrenOf.set(p, [...(childrenOf.get(p) ?? []), row]);
  }
  let built = 0;
  const node = (row: T): N => {
    built++;
    return make(row, (childrenOf.get(id(shape.key(row))!) ?? []).map(node));
  };
  const tree = roots.map(node);
  if (built < rows.length) throw new Error("buildTree: some rows form a parent cycle and never reach a root");
  return tree;
}

/** withAncestors 怎样读行、怎样按键取父行。 */
export interface AncestorLoad<T, K> {
  readonly key: (row: T) => K;
  readonly parent: (row: T) => K | null | undefined;
  /** 按键批量取行，例如 `(ids) => db.list(from(Dept).where((d) => d.id.in(ids)).select(...))`。 */
  readonly load: (keys: K[]) => Promise<readonly T[]>;
}

/**
 * rows 加上它们缺的祖先：一层一层往上，每层用 load 按键批量取父行（每批至多 {@link TREE_BATCH} 个键），直到都到了根或父行不存在。
 * 返回的顺序是先 rows，再按层补上的祖先；已有的行不会重复。父子成环时到环上已取过的行就停。
 */
export async function withAncestors<T, K>(rows: readonly T[], { key, parent, load }: AncestorLoad<T, K>): Promise<T[]> {
  const all = new Map<string, T>();
  for (const row of rows) if (!all.has(String(key(row)))) all.set(String(key(row)), row);
  const asked = new Set<string>();
  let level = [...all.values()];
  while (level.length > 0) {
    const missing = new Map<string, K>();
    for (const row of level) {
      const p = parent(row);
      if (p == null || all.has(String(p)) || asked.has(String(p))) continue;
      asked.add(String(p));
      missing.set(String(p), p);
    }
    const wanted = [...missing.values()];
    const found: T[] = [];
    for (let i = 0; i < wanted.length; i += TREE_BATCH) {
      for (const row of await load(wanted.slice(i, i + TREE_BATCH))) {
        const k = String(key(row));
        if (all.has(k)) continue;
        all.set(k, row);
        found.push(row);
      }
    }
    level = found;
  }
  return [...all.values()];
}

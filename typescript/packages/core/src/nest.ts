/**
 * Groups flat rows, such as a header joined to its lines, into parents with their children: one
 * parent per distinct key, in the order keys first appear, built from the key's first row and its
 * children. A child function that returns null or undefined, as for an unmatched LEFT JOIN, adds no
 * child. Keys compare like Map keys, so join composite keys into a string: `` (r) => `${r.a}|${r.b}` ``.
 *
 * ```ts
 * const orders = nest(await db.list(lines), (r) => r.orderNo, (r) => r.item, (first, items) => ({ orderNo: first.orderNo, items }));
 * ```
 */
export function nest<R, K, C, P>(
  rows: readonly R[],
  key: (row: R) => K,
  child: (row: R) => C | null | undefined,
  parent: (first: R, children: C[]) => P,
): P[] {
  const groups = new Map<K, { first: R; children: C[] }>();
  for (const row of rows) {
    const k = key(row);
    let group = groups.get(k);
    if (group === undefined) groups.set(k, (group = { first: row, children: [] }));
    const c = child(row);
    if (c != null) group.children.push(c);
  }
  return [...groups.values()].map((g) => parent(g.first, g.children));
}

package org.dpdns.zerodep.query.exec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 树形数据（部门、分类、菜单这类每行带父键的表）的两件常用事：把平铺的行组装成树（同 EasyQuery 的 {@code toTreeList}），
 * 以及给搜索命中的行补上它们的祖先，前端懒加载的树要在服务端搜索时用（vue-tsx-pro 用 {@code buildTree} 组装、{@code filterTree} 展开）。
 * <pre>{@code
 * List<Dept> rows = db.withAncestors(Dept.class, d -> d.id(), d -> d.parentId(), d -> d.name().contains(keyword),
 *     d -> cols(d.id(), d.parentId(), d.name())).into(...)   // 或 row(Dept::new, ...)
 * List<DeptNode> tree = Trees.build(rows, Dept::id, Dept::parentId, DeptNode::of);
 * }</pre>
 * {@link Database#withAncestors} 在 PostgreSQL、MySQL、SQLite 上写成一条递归 CTE，HANA 上一层一层按键查；数据不来自 SQL 时用这里的
 * {@link #withAncestors}，自己给出按键取行的 load。懒加载时每行带上有没有子节点：投影里写 {@code bool(() -> d.children().any()).as("hasChildren")}。
 */
public final class Trees {
    /** 一次按键查询最多带多少个键，避免超出各库的参数个数上限（同子列表的批量查询）。 */
    public static final int BATCH = 500;

    /** 一行的键和父键：{@link Database#withAncestors} 的递归 CTE 和逐层查询里只取这两列。 */
    public record Link(Object key, Object parent) { }

    /** 工具类，不能实例化。 */
    private Trees() { }

    /**
     * 平铺的行组装成树，返回第一层；同级保持 rows 里的顺序。父键为 null、等于自己、或不在这批行里的行当作根；
     * withChildren 用子节点生成一个节点（record 新建，可变的类设好 children 再返回自己），叶子拿到空列表。
     * 键重复或父子成环（环上的行到不了第一层）时报错。
     */
    public static <T, K, N> List<N> build(List<T> rows, Function<T, K> key, Function<T, K> parent, BiFunction<T, List<N>, N> withChildren) {
        Set<K> keys = new HashSet<>();
        for (T row : rows) {
            if (!keys.add(key.apply(row))) throw new IllegalArgumentException("Trees.build: the key " + key.apply(row) + " appears twice");
        }
        List<T> roots = new ArrayList<>();
        Map<K, List<T>> children = new LinkedHashMap<>();
        for (T row : rows) {
            K p = parent.apply(row);
            if (p == null || p.equals(key.apply(row)) || !keys.contains(p)) roots.add(row);
            else children.computeIfAbsent(p, k -> new ArrayList<>()).add(row);
        }
        int[] built = {0};
        List<N> tree = new ArrayList<>();
        for (T root : roots) tree.add(node(root, key, children, withChildren, built));
        if (built[0] < rows.size()) throw new IllegalArgumentException("Trees.build: some rows form a parent cycle and never reach a root");
        return tree;
    }

    /** row 和它下面的子树。 */
    private static <T, K, N> N node(T row, Function<T, K> key, Map<K, List<T>> children, BiFunction<T, List<N>, N> withChildren, int[] built) {
        built[0]++;
        List<N> kids = new ArrayList<>();
        for (T child : children.getOrDefault(key.apply(row), List.of())) kids.add(node(child, key, children, withChildren, built));
        return withChildren.apply(row, kids);
    }

    /**
     * rows 加上它们缺的祖先：一层一层往上，每层用 load 按键批量取父行（每批至多 {@link #BATCH} 个键），直到都到了根或父行不存在。
     * 返回的顺序是先 rows，再按层补上的祖先；已有的行不会重复。父子成环时到环上已取过的行就停。
     */
    public static <T, K> List<T> withAncestors(List<T> rows, Function<T, K> key, Function<T, K> parent, Function<List<K>, ? extends Collection<T>> load) {
        Map<K, T> all = new LinkedHashMap<>();
        for (T row : rows) all.putIfAbsent(key.apply(row), row);
        Set<K> asked = new HashSet<>();
        List<T> level = List.copyOf(all.values());
        while (!level.isEmpty()) {
            Set<K> missing = new LinkedHashSet<>();
            for (T row : level) {
                K p = parent.apply(row);
                if (p != null && !all.containsKey(p) && asked.add(p)) missing.add(p);
            }
            List<T> found = new ArrayList<>();
            List<K> wanted = new ArrayList<>(missing);
            for (int i = 0; i < wanted.size(); i += BATCH) {
                for (T row : load.apply(wanted.subList(i, Math.min(i + BATCH, wanted.size())))) {
                    if (all.putIfAbsent(Objects.requireNonNull(key.apply(row), "Trees.withAncestors: a loaded row has no key"), row) == null) found.add(row);
                }
            }
            level = found;
        }
        return List.copyOf(all.values());
    }
}

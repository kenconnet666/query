package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 一对多的子列表，来自 {@code many.list(...)}：放进 {@code row(...)} 的参数或 {@code selectAs} 的 VO，每个父行得到一个
 * {@code List<C>}。父查询只多取连接键；执行时用一条按键批量的 IN 查询取出这些父行的全部子行，再按键分给各父行，
 * 所以 N 个父行只多一条 SQL（父行很多时每 {@value #BATCH} 个键一批）。子投影里还可以再有 {@code list(...)}，逐层加载。
 *
 * @param <C> 每个子行的结果类型
 */
public final class Children<C> implements Selection<List<C>> {
    /** 每条子查询最多带多少个父键，避免超出各库的参数个数上限。 */
    static final int BATCH = 500;

    /** 子查询的 FROM、筛选条件和排序，不含按父键的条件。 */
    private final SelectSpec spec;

    /** 每个子行的投影。 */
    private final Selection<C> projection;

    /** 子表里与父行对应的列。 */
    private final List<Expr<?>> keys;

    /** 父行里与 keys 一一对应的列，父查询多取的就是它们。 */
    private final List<Expr<?>> outer;

    /** 由 {@code Many.list(...)} 创建：keys 与 outer 一一对应。 */
    public Children(SelectSpec spec, Selection<C> projection, List<? extends Expr<?>> keys, List<? extends Expr<?>> outer) {
        if (keys.isEmpty() || keys.size() != outer.size()) throw new IllegalArgumentException("list() needs matching key columns");
        this.spec = spec;
        this.projection = projection;
        this.keys = List.copyOf(keys);
        this.outer = List.copyOf(outer);
    }

    /** 父查询要多取的列。 */
    List<Expr<?>> outer() { return outer; }

    /**
     * 为 rows 里的父行加载子行，并把每个父行的子列表写到它的第 start 列（原来是第一个键）。
     * run 执行一条子查询并返回映射好的行，每行是 {子行, 键 1, 键 2, ...}。
     */
    @SuppressWarnings("unchecked")
    void attach(List<Object[]> rows, int start, Function<Query<Object[]>, List<Object[]>> run) {
        Map<List<Object>, List<Object>> wanted = new LinkedHashMap<>();
        List<List<Object>> rowKeys = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            List<Object> key = keyOf(row, start);
            rowKeys.add(key);
            if (key != null) wanted.putIfAbsent(key, Arrays.asList(Arrays.copyOfRange(row, start, start + keys.size())));
        }
        Map<List<Object>, List<C>> found = new HashMap<>();
        List<List<Object>> pending = new ArrayList<>(wanted.values());
        for (int from = 0; from < pending.size(); from += BATCH) {
            for (Object[] child : run.apply(query(pending.subList(from, Math.min(from + BATCH, pending.size()))))) {
                found.computeIfAbsent(keyOf(child, 1), k -> new ArrayList<>()).add((C) child[0]);
            }
        }
        Map<List<Object>, List<C>> frozen = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            List<Object> key = rowKeys.get(i);
            rows.get(i)[start] = key == null ? List.of() : frozen.computeIfAbsent(key, k -> List.copyOf(found.getOrDefault(k, List.of())));
        }
    }

    /** 一批父键对应的子查询：单列键用 IN，多列键用 (a = ? AND b = ?) OR ...；每行映射成 {子行, 各键}。 */
    @SuppressWarnings("unchecked")
    private Query<Object[]> query(List<List<Object>> batch) {
        Cond match;
        if (keys.size() == 1) {
            match = ((Expr<Object>) keys.get(0)).in(batch.stream().map(t -> t.get(0)).toList());
        } else {
            List<Cond> any = new ArrayList<>();
            for (List<Object> t : batch) {
                List<Cond> each = new ArrayList<>();
                for (int i = 0; i < keys.size(); i++) each.add(((Expr<Object>) keys.get(i)).eq(t.get(i)));
                any.add(Sql.all(each));
            }
            match = Sql.any(any);
        }
        Selection<?>[] parts = new Selection<?>[keys.size() + 1];
        parts[0] = projection;
        for (int i = 0; i < keys.size(); i++) parts[i + 1] = keys.get(i);
        return Query.select(spec.where(match), new RowSelection<>(args -> args, parts));
    }

    /** values 从 start 起的键，用来比较父键与子键；有 null 时为 null。数值统一成去掉尾零的 BigDecimal，1 与 1.00 相等。 */
    private List<Object> keyOf(Object[] values, int start) {
        Object[] key = new Object[keys.size()];
        for (int i = 0; i < key.length; i++) {
            Object v = values[start + i];
            if (v == null) return null;
            key[i] = v instanceof Number n ? new BigDecimal(n.toString()).stripTrailingZeros() : v;
        }
        return Arrays.asList(key);
    }
}

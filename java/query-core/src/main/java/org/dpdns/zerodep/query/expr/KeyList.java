package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.select.Query;

import java.util.AbstractList;
import java.util.List;

/** 按顺序排列的多个表达式，来自 {@link Sql#keys}；本身也是 List，{@code groupingSets} 接受它。 */
public final class KeyList extends AbstractList<Expr<?>> implements Keys {
    /** 各分组键，不可变。 */
    private final List<Expr<?>> items;

    /** 由 {@link Sql#keys} 创建。 */
    public KeyList(List<Expr<?>> items) { this.items = List.copyOf(items); }

    /** 把 {@link Keys} 摊平成表达式列表：单个表达式得到单元素列表。 */
    public static List<Expr<?>> of(Keys keys) { return keys instanceof Expr<?> e ? List.of(e) : (KeyList) keys; }

    /** 多列 IN 子查询：{@code keys(a, b).in(子查询)} 写成 {@code (a, b) IN (SELECT x, y ...)}，列数必须相同；写进当前的条件块。 */
    public void in(Query<?> subquery) { Conditions.add(Nodes.rowInQuery(items, subquery, false)); }

    /** 多列 NOT IN 子查询；子查询里有 NULL 时，与单列 NOT IN 一样整个条件为未知。写进当前的条件块。 */
    public void notIn(Query<?> subquery) { Conditions.add(Nodes.rowInQuery(items, subquery, true)); }

    /** 第 index 个键。 */
    @Override public Expr<?> get(int index) { return items.get(index); }

    /** 键的个数。 */
    @Override public int size() { return items.size(); }
}

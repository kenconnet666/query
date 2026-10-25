package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.expr.Expr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按列名选取的一组列，来自 {@link Sql#cols}。列名取自列方法的名字；只包着一列的函数或聚合沿用该列名
 * （{@code l.quantity().sum()} 叫 "quantity"），其他表达式必须写 {@code .as("name")}。
 * 结果行是 Map；用 {@code into(X.class)} 可以按列名对应属性，映射成指定的类。
 */
public final class Cols implements Selection<Map<String, Object>> {
    /** 各列的表达式，按投影顺序。 */
    private final List<Expr<?>> items;

    /** 各列在结果里的名字，与 items 一一对应。 */
    private final List<String> names;

    /** 解析每一列的名字；缺名字或名字重复（不分大小写）时抛出异常。 */
    public Cols(List<? extends Expr<?>> items) {
        this.items = List.copyOf(items);
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < this.items.size(); i++) {
            String name = this.items.get(i).nameHint();
            if (name == null) {
                throw new IllegalArgumentException("Column " + (i + 1) + " of cols(...) has no name; add .as(\"name\")");
            }
            if (!used.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Two columns are named '" + name + "'; rename one with .as(\"name\")");
            }
            names.add(name);
        }
        this.names = List.copyOf(names);
    }

    /** 各列的表达式。 */
    public List<Expr<?>> items() { return items; }

    /** 各列的结果名。 */
    List<String> names() { return names; }
}

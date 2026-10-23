package org.dpdns.zerodep.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按位置喂给构造器的投影，来自 {@code row(X::new, a, b, ...)}。每个参数是一列、嵌套的 row 或 {@code cols(...)}，
 * javac 会逐个位置对照构造器检查类型。
 */
public final class RowSelection<R> implements Selection<R> {
    /** 用各部分读出的值构造一行的函数，由 {@code row(X::new, ...)} 的重载适配。 */
    @FunctionalInterface
    interface Builder<R> {
        /** args 是各部分读出的值，按位置排列。 */
        R build(Object[] args);
    }

    /** 构造一行的函数。 */
    private final Builder<R> builder;

    /** 各个参数对应的选取，按构造器参数顺序。 */
    private final List<Selection<?>> parts;

    /** 由生成的 {@code Rows} 里的 {@code row(X::new, ...)} 创建。 */
    RowSelection(Builder<R> builder, Selection<?>... parts) {
        this.builder = builder;
        this.parts = List.of(parts);
    }

    /** 把选取摊平成 SELECT 列表，顺序就是读取顺序。 */
    static void collect(Selection<?> s, List<Expr<?>> into) {
        if (s instanceof Expr<?> e) into.add(e);
        else if (s instanceof Cols c) into.addAll(c.items());
        else for (Selection<?> part : ((RowSelection<?>) s).parts) collect(part, into);
    }

    /** 从一行的值里读出一个选取，cursor 前进到它用掉的列之后。 */
    static Object read(Selection<?> s, Object[] values, int[] cursor) {
        if (s instanceof Expr<?>) return values[cursor[0]++];
        if (s instanceof Cols c) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String name : c.names()) map.put(name, values[cursor[0]++]);
            return map;
        }
        RowSelection<?> row = (RowSelection<?>) s;
        Object[] args = new Object[row.parts.size()];
        for (int i = 0; i < args.length; i++) args[i] = read(row.parts.get(i), values, cursor);
        return row.builder.build(args);
    }
}

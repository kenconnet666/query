package org.dpdns.zerodep.query.table;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一条查询或写语句对全局过滤器和逻辑删除的开关。默认全部生效；{@code unfiltered()} 全部关闭；
 * {@code unfiltered("tenant")} 只关闭这些名字的过滤器；{@code withDeleted()} 只关闭逻辑删除，能看到已删除的行。
 *
 * @param enabled     是否套用过滤器；false 时全局过滤器和逻辑删除都不加
 * @param disabled    关闭的具名过滤器
 * @param logicDelete 是否加逻辑删除的"未删除"条件
 */
public record Filtering(boolean enabled, Set<String> disabled, boolean logicDelete) {
    /** 全部生效。 */
    public static final Filtering ON = new Filtering(true, Set.of(), true);

    /** 全部关闭。 */
    public static final Filtering OFF = new Filtering(false, Set.of(), false);

    /** 复制名字集合。 */
    public Filtering {
        disabled = Set.copyOf(disabled);
    }

    /** 再关闭这些名字的过滤器。 */
    public Filtering without(String first, String... more) {
        List<String> names = new ArrayList<>(disabled);
        names.add(first);
        names.addAll(List.of(more));
        return new Filtering(enabled, Set.copyOf(names), logicDelete);
    }

    /** 关闭逻辑删除，其余不变。 */
    public Filtering withDeleted() { return new Filtering(enabled, disabled, false); }
}

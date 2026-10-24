package org.dpdns.zerodep.query.table;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 一条查询或写语句看得到哪些行：全局过滤器、逻辑删除和分表。默认过滤器全部生效、分表按条件选表；
 * {@code unfiltered()} 关闭过滤器和逻辑删除；{@code unfiltered("tenant")} 只关闭这些名字的过滤器；
 * {@code withDeleted()} 只关闭逻辑删除；{@code allShards()} 读写全部分表，{@code onShards(...)} 只读写列出的分表。
 *
 * @param enabled     是否套用过滤器；false 时全局过滤器和逻辑删除都不加
 * @param disabled    关闭的具名过滤器
 * @param logicDelete 是否加逻辑删除的"未删除"条件
 * @param everyShard  是否不按条件选表、直接读写全部分表（{@code allShards()}）
 * @param onShards    只读写这些物理表；为空时按条件选表
 */
public record Filtering(boolean enabled, Set<String> disabled, boolean logicDelete, boolean everyShard, List<String> onShards) {
    /** 全部生效，分表按条件选表。 */
    public static final Filtering ON = new Filtering(true, Set.of(), true, false, List.of());

    /** 复制名字集合。 */
    public Filtering {
        disabled = Set.copyOf(disabled);
        onShards = List.copyOf(onShards);
    }

    /** 关闭全局过滤器和逻辑删除，分表的选择不变。 */
    public Filtering off() { return new Filtering(false, Set.of(), false, everyShard, onShards); }

    /** 再关闭这些名字的过滤器。 */
    public Filtering without(String first, String... more) {
        List<String> names = new ArrayList<>(disabled);
        names.add(first);
        names.addAll(List.of(more));
        return new Filtering(enabled, Set.copyOf(names), logicDelete, everyShard, onShards);
    }

    /** 关闭逻辑删除，其余不变。 */
    public Filtering withDeleted() { return new Filtering(enabled, disabled, false, everyShard, onShards); }

    /** 读写全部分表。 */
    public Filtering allShards() { return new Filtering(enabled, disabled, logicDelete, true, List.of()); }

    /** 只读写这些物理表。 */
    public Filtering onShards(String first, String... more) {
        List<String> tables = new ArrayList<>();
        tables.add(first);
        tables.addAll(List.of(more));
        return new Filtering(enabled, disabled, logicDelete, false, tables);
    }

    /** 是否显式选了分表（{@code allShards()} 或 {@code onShards(...)}）；没有时按条件选表。 */
    public boolean choosesShards() { return everyShard || !onShards.isEmpty(); }
}

package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.table.column.Col;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 表的每次出现要追加的条件：数据库实例配置的全局过滤器（按父类型或接口匹配），以及表上 {@code @LogicDelete} 列的"未删除"条件。
 * FROM 的表加到 WHERE，JOIN 和关系的表加到 ON；查询或写语句的 {@link Filtering} 决定关掉哪些，见 {@link #select}。
 */
public final class SourceFilters {
    /**
     * 一个全局过滤器。
     *
     * @param name      名字，用于 {@code unfiltered(name)} 单独关闭；没有名字时为 null
     * @param type      表接口或它继承的接口
     * @param condition 收到这张表的表接口，返回条件
     */
    public record Filter(String name, Class<?> type, Consumer<Object> condition) { }

    /** 没有配置全局过滤器：只有逻辑删除的条件，没有数据库实例时（例如 {@code toSql(dialect)}）也用它。 */
    public static final SourceFilters NONE = new SourceFilters(List.of(), true);

    /** {@code unfiltered()}：全局过滤器和逻辑删除的条件都不加。 */
    public static final SourceFilters OFF = new SourceFilters(List.of(), false);

    /** 已注册的过滤器，按注册顺序生效。 */
    private final List<Filter> filters;

    /** 是否加逻辑删除的条件。 */
    private final boolean logicDelete;

    /** 由 {@link Database} 用配置里的过滤器创建；逻辑删除的条件照常追加。 */
    public SourceFilters(List<Filter> filters) { this(filters, true); }

    /** 全部字段的构造。 */
    private SourceFilters(List<Filter> filters, boolean logicDelete) {
        this.filters = List.copyOf(filters);
        this.logicDelete = logicDelete;
    }

    /**
     * 按一条语句的开关取舍：{@code unfiltered()} 时是 {@link #OFF}，否则去掉关闭了的具名过滤器，按需去掉逻辑删除。
     * 关闭的名字不是任何已配置的过滤器时报错，免得拼错的名字悄悄不起作用；没有数据库实例（{@link #NONE}）时不检查。
     */
    public SourceFilters select(Filtering filtering) {
        if (!filtering.enabled()) return OFF;
        if (filtering.disabled().isEmpty() && filtering.logicDelete() == logicDelete) return this;
        if (this != NONE) {
            List<String> known = filters.stream().map(Filter::name).filter(Objects::nonNull).toList();
            for (String name : filtering.disabled()) {
                if (!known.contains(name)) throw new IllegalArgumentException("unfiltered(\"" + name + "\") names no configured filter; known: " + known);
            }
        }
        List<Filter> kept = filters.stream().filter(f -> f.name() == null || !filtering.disabled().contains(f.name())).toList();
        return new SourceFilters(kept, logicDelete && filtering.logicDelete());
    }

    /** 这张表要追加的条件：先是全局过滤器，再是逻辑删除；派生表、CTE 等不是表的来源没有。 */
    public List<Cond> conditionsFor(Ref<?> ref) {
        if (!ref.source().filtered()) return List.of();
        List<Cond> out = new ArrayList<>();
        for (Filter f : filters) {
            if (f.type().isAssignableFrom(ref.entity())) out.add(Conditions.collect(() -> f.condition().accept(ref.param())));
        }
        if (logicDelete) {
            Cond active = active(ref);
            if (active != null) out.add(active);
        }
        return out;
    }

    /** 表上有逻辑删除列时"未删除"的条件：时间戳列为 {@code IS NULL}，其余等于未删除的值；没有时为 null。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Cond active(Ref<?> ref) {
        EntityMeta<?> meta = Metadata.entity(ref.entity());
        WriteMeta.LogicDeleteMeta logic = meta.writes().logicDelete();
        if (logic == null) return null;
        Col column = Col.of(ref, meta.property(logic.property()));
        return logic.timestamp() ? Nodes.postfix(column, " IS NULL") : Nodes.compare(column, "=", new Nodes.Param(column.type(), logic.active()));
    }
}

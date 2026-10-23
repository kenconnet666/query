package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.WriteMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 表的每次出现要追加的条件：数据库实例配置的全局过滤器（按父类型或接口匹配），以及表上 {@code @LogicDelete} 列的"未删除"条件。
 * FROM 的表加到 WHERE，JOIN 和关系的表加到 ON；查询或写语句用了 {@code unfiltered()} 时两者都不加（{@link #OFF}）。
 */
public final class SourceFilters {
    /** type 是表接口或它继承的接口；condition 收到这张表的表接口。 */
    public record Filter(Class<?> type, Function<Object, Cond> condition) { }

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

    /** 这张表要追加的条件：先是全局过滤器，再是逻辑删除；派生表、CTE 等不是表的来源没有。 */
    public List<Cond> conditionsFor(Ref<?> ref) {
        if (!ref.source().filtered()) return List.of();
        List<Cond> out = new ArrayList<>();
        for (Filter f : filters) {
            if (f.type().isAssignableFrom(ref.entity())) out.add(f.condition().apply(ref.param()));
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
        Col column = new Col<>(ref, meta.property(logic.property()));
        return logic.timestamp() ? column.isNull() : column.eq(logic.active());
    }
}

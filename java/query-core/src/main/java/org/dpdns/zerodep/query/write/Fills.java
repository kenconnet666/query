package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.column.Col;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 写入时自动填充的列（审计字段），由数据库实例的 {@code onInsert(...)}、{@code onUpdate(...)} 配置：插入时填 onInsert 的列，
 * 更新（包括按主键更新、插入或更新的更新部分、逻辑删除）时填 onUpdate 的列。按表接口的父类型或接口匹配，值总是覆盖行里的值。
 */
public final class Fills {
    /**
     * 一项填充。
     *
     * @param type     表接口或它继承的接口
     * @param column   从表接口实例取出要填的列
     * @param value    每条语句取一次值；返回 {@code Expr} 时原样写出（例如 {@code currentTimestamp()}），否则按列的类型绑定
     * @param onInsert true 是插入时填，false 是更新时填
     */
    public record Fill(Class<?> type, Function<Object, Col<?>> column, Supplier<?> value, boolean onInsert) { }

    /**
     * 插入时逐行生成值的一列，只填值为 null 的行，例如雪花主键。
     *
     * @param type   表接口或它继承的接口
     * @param column 从表接口实例取出要生成的列
     * @param value  每行调用一次
     */
    public record Generator(Class<?> type, Function<Object, Col<?>> column, Supplier<?> value) { }

    /** 插入时要生成的一列：列的属性和取值函数。 */
    record Generated(PropertyMeta column, Supplier<?> value) { }

    /** 没有任何填充。 */
    public static final Fills NONE = new Fills(List.of(), List.of());

    /** 按注册顺序生效的填充。 */
    private final List<Fill> fills;

    /** 插入时逐行生成值的列。 */
    private final List<Generator> generators;

    /** 由数据库实例用配置里的填充和生成器创建。 */
    public Fills(List<Fill> fills, List<Generator> generators) {
        this.fills = List.copyOf(fills);
        this.generators = List.copyOf(generators);
    }

    /** 插入 ref 这张表时逐行生成的列；列必须是这张表的列。 */
    List<Generated> generators(Ref<?> ref) {
        List<Generated> out = new ArrayList<>();
        for (Generator g : generators) {
            if (!g.type().isAssignableFrom(ref.entity())) continue;
            Col<?> column = g.column().apply(ref.param());
            if (column == null || column.ref() != ref) throw new IllegalArgumentException("A generator for " + g.type().getSimpleName() + " must return one of its columns");
            out.add(new Generated(column.property(), g.value()));
        }
        return out;
    }

    /** 写入器上数据库实例的填充；没有数据库实例时（例如 {@code toSql(dialect)}）没有填充。 */
    static Fills of(org.dpdns.zerodep.query.dialect.SqlWriter w) {
        Object fills = w.attribute(Fills.class);
        return fills == null ? NONE : (Fills) fills;
    }

    /** 插入 ref 这张表时要填的列和值。 */
    List<Assignment> onInsert(Ref<?> ref) { return matching(ref, true); }

    /** 更新 ref 这张表时要填的列和值。 */
    List<Assignment> onUpdate(Ref<?> ref) { return matching(ref, false); }

    /** 对这张表生效的一类填充；列必须是这张表的列。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Assignment> matching(Ref<?> ref, boolean insert) {
        List<Assignment> out = new ArrayList<>();
        for (Fill f : fills) {
            if (f.onInsert() != insert || !f.type().isAssignableFrom(ref.entity())) continue;
            Col column = f.column().apply(ref.param());
            if (column == null || column.ref() != ref) throw new IllegalArgumentException("A fill for " + f.type().getSimpleName() + " must return one of its columns");
            Object value = f.value().get();
            out.add(new Assignment(column, value instanceof Expr<?> e ? e : new Nodes.Param<>(column.type(), value)));
        }
        return out;
    }
}

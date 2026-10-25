package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.column.Col;

/**
 * UPDATE 的一个 SET 项，由列的 {@code to(value)} 写进赋值块：{@code update(Lips.class).set(l -> l.unit().to("KG"))}。
 *
 * @param column 被赋值的列
 * @param value  新值的表达式
 */
public record Assignment(Col<?> column, Expr<?> value) {
    /** skipEmpty 下值为空而省略的 SET 项。 */
    public static final Assignment SKIPPED = new Assignment(null, null);

    /** 是否是被省略的 SET 项。 */
    public boolean skipped() { return column == null; }

    /** 把列设为常量 value（为 null 时设为 NULL），按列的类型绑定；与 {@code to(value)} 相同，skipEmpty 下值为空时省略。供写入内部使用。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Assignment value(Col<?> column, Object value) {
        if (SkipEmpty.skips(value)) return SKIPPED;
        return new Assignment(column, new Nodes.Param(column.type(), value));
    }

    /** 逻辑删除的 SET 项：时间戳列设为当前时间，其余设为表示已删除的值。 */
    static Assignment deleted(Ref<?> ref, EntityMeta<?> meta, WriteMeta.LogicDeleteMeta logic) {
        Col<?> column = Col.of(ref, meta.property(logic.property()));
        return logic.timestamp() ? new Assignment(column, Sql.currentTimestamp()) : value(column, logic.deletedValue());
    }

    /** 版本列加一：{@code version = (version + 1)}。 */
    static Assignment increment(Col<?> version) {
        return new Assignment(version, Nodes.arithmetic(version.type(), "+", version, Nodes.literal(Integer.class, 1)));
    }
}

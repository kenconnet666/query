package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.table.Col;

/**
 * UPDATE 的一个 SET 项，由 {@code col.to(value)} 创建：{@code update(Lips.class).set(l -> l.unit().to("KG"))}。
 *
 * @param column 被赋值的列
 * @param value  新值的表达式
 */
public record Assignment(Col<?> column, Expr<?> value) {
    /** skipEmpty 下值为空而省略的 SET 项。 */
    public static final Assignment SKIPPED = new Assignment(null, null);

    /** 是否是被省略的 SET 项。 */
    public boolean skipped() { return column == null; }
}

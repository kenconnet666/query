package org.dpdns.zerodep.query.table.column;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.ExprNode;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.type.SqlType;

/** 列的公共实现：某个来源（{@link Ref}）里的一列。各类型的列（{@link Columns}）只在它上面加上类型接口。 */
abstract class ColumnNode<T> extends ExprNode<T> implements Col<T> {
    /** 这一列所属的来源出现。 */
    private final Ref<?> ref;

    /** 对应的属性元数据，决定列名、类型和读取模板。 */
    private final PropertyMeta property;

    @SuppressWarnings("unchecked")
    ColumnNode(Ref<?> ref, PropertyMeta property) {
        super((SqlType<T>) property.type());
        this.ref = ref;
        this.property = property;
    }

    @Override public final Ref<?> ref() { return ref; }

    @Override public final PropertyMeta property() { return property; }

    @Override public final void renderName(SqlWriter w) {
        Object naming = w.attribute(NamingStrategy.class);
        w.identifier(ref.source().column(property, naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming));
    }

    /** 写出带别名的列，必要时套用属性的 {@code @ReadAs} 模板。 */
    @Override public final void render(SqlWriter w) { ref.renderColumn(w, property); }

    /** 投影时的列名是属性名。 */
    @Override public final String label() { return property.name(); }

    /** 调试用，形如 {@code Ref(Lips).material}。 */
    @Override public final String toString() { return ref + "." + property.name(); }
}

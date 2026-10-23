package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.type.SqlType;

/** 某个来源（{@link Ref}）里的一列；结果里的列名是列方法（或行属性）的名字。 */
public final class Col<T> extends Expr<T> {
    /** 这一列所属的来源出现。 */
    private final Ref<?> ref;

    /** 对应的属性元数据，决定列名、类型和读取模板。 */
    private final PropertyMeta property;

    /** 由表代理和 {@link Ref#col} 创建。 */
    @SuppressWarnings("unchecked")
    Col(Ref<?> ref, PropertyMeta property) {
        super((SqlType<T>) property.type());
        this.ref = ref;
        this.property = property;
    }

    /** 写出带别名的列，必要时套用属性的 {@code @ReadAs} 模板。 */
    @Override public void render(SqlWriter w) { ref.renderColumn(w, property); }

    /** 投影时的列名是属性名。 */
    @Override public String label() { return property.name(); }

    /** 调试用，形如 {@code Ref(Lips).material}。 */
    @Override public String toString() { return ref + "." + property.name(); }
}

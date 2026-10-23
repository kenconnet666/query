package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.Assignment;

/** 某个来源（{@link Ref}）里的一列；结果里的列名是列方法（或行属性）的名字。 */
public final class Col<T> extends Expr<T> {
    /** 这一列所属的来源出现。 */
    private final Ref<?> ref;

    /** 对应的属性元数据，决定列名、类型和读取模板。 */
    private final PropertyMeta property;

    /** 由表代理和 {@link Ref#col} 创建。 */
    @SuppressWarnings("unchecked")
    public Col(Ref<?> ref, PropertyMeta property) {
        super((SqlType<T>) property.type());
        this.ref = ref;
        this.property = property;
    }

    /** 这一列所属的来源出现。 */
    public Ref<?> ref() { return ref; }

    /** 对应的属性元数据。 */
    public PropertyMeta property() { return property; }

    /** UPDATE 的 SET 项：把这一列设为 value，value 为 null 时设为 NULL；开启 skipEmpty 时值为空则省略这一项。 */
    public Assignment to(T value) {
        if (SkipEmpty.skips(value)) return Assignment.SKIPPED;
        return new Assignment(this, new Nodes.Param<>(type(), value));
    }

    /** UPDATE 的 SET 项：把这一列设为表达式的值，例如 {@code l.quantity().to(plus(l.quantity(), val(BigDecimal.ONE)))}。 */
    public Assignment to(Expr<T> value) { return new Assignment(this, value); }

    /** 只写出列名（不带别名），用于 INSERT 的列清单和 UPDATE 的 SET。 */
    public void renderName(SqlWriter w) {
        Object naming = w.attribute(NamingStrategy.class);
        w.identifier(ref.source().column(property, naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming));
    }

    /** 写出带别名的列，必要时套用属性的 {@code @ReadAs} 模板。 */
    @Override public void render(SqlWriter w) { ref.renderColumn(w, property); }

    /** 投影时的列名是属性名。 */
    @Override public String label() { return property.name(); }

    /** 调试用，形如 {@code Ref(Lips).material}。 */
    @Override public String toString() { return ref + "." + property.name(); }
}

package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.Getter;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

import java.util.List;

/**
 * 来源（表、CTE、派生表等）在查询里的一次出现。表的出现以它的表接口传给 lambda；
 * CTE、派生表或常量行则以这个 Ref 传入，用 {@code r.col(Row::total)} 取列。
 * 别名在渲染每条语句时分配。
 */
public final class Ref<E> {
    /** 这次出现所对应的来源。 */
    private final Source<E> source;

    /** 传给 lambda 的对象：来源是接口（表）时是表代理，否则是 Ref 自己。 */
    private final Object param;

    /** 只能通过 {@link #table} 和 {@link #of} 创建。 */
    private Ref(Source<E> source) {
        this.source = source;
        this.param = source.entity().isInterface() ? TableProxy.create(this) : this;
    }

    /** 表接口的一次出现。 */
    public static <T extends Entity> Ref<T> table(Class<T> table) { return new Ref<>(new TableSource<>(table)); }

    /** 任意来源的一次出现。 */
    public static <E> Ref<E> of(Source<E> source) { return new Ref<>(source); }

    /** CTE、派生表或常量行的一列；{@code ? super E} 允许传入基类和接口上的 getter。表的列直接用表接口的方法，调用本方法会报错。 */
    public <T> Col<T> col(Getter<? super E, T> getter) {
        if (source.entity().isInterface()) {
            throw new IllegalArgumentException("Columns of " + source.entity().getSimpleName() + " are its methods, e.g. t.material()");
        }
        return new Col<>(this, Metadata.property(source.entity(), getter));
    }

    /** 描述行的类：表接口，或 CTE、派生表的结构类。 */
    public Class<E> entity() { return source.entity(); }

    /** 这次出现所对应的来源。 */
    public Source<E> source() { return source; }

    /** lambda 收到的对象：表是它的表接口，其他来源是这个 Ref。 */
    @SuppressWarnings("unchecked")
    public <P> P param() { return (P) param; }

    /** 本次出现在当前语句里的别名，首次调用时分配。 */
    public String alias(SqlWriter w) { return w.aliasFor(this, source.preferredAlias()); }

    /** 写出带别名的列；属性带 {@code @ReadAs} 模板时按模板写。 */
    void renderColumn(SqlWriter w, PropertyMeta property) {
        Object naming = w.attribute(NamingStrategy.class);
        String column = source.column(property, naming == null ? NamingStrategy.AS_IS : (NamingStrategy) naming);
        Renderable qualified = c -> c.alias(alias(c)).sql(".").identifier(column);
        if (property.read() == null) qualified.render(w);
        else Nodes.renderTemplate(w, property.read(), List.of(qualified));
    }

    /** 写出 FROM 或 JOIN 里的来源及其别名。 */
    public void renderSource(SqlWriter w) {
        source.renderSource(w);
        w.sql(" ").alias(alias(w));
    }

    /** 调试用，形如 {@code Ref(Lips)}。 */
    @Override public String toString() { return "Ref(" + source.entity().getSimpleName() + ")"; }
}

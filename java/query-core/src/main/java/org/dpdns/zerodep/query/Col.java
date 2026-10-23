package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.type.SqlType;

/** A column of one table occurrence; its name in results is the column method's (or row property's) name. */
public final class Col<T> extends Expr<T> {
    private final Ref<?> ref;
    private final PropertyMeta property;

    @SuppressWarnings("unchecked")
    Col(Ref<?> ref, PropertyMeta property) {
        super((SqlType<T>) property.type());
        this.ref = ref;
        this.property = property;
    }

    @Override public void render(SqlWriter w) { ref.renderColumn(w, property); }

    @Override public String label() { return property.name(); }

    @Override public String toString() { return ref + "." + property.name(); }
}

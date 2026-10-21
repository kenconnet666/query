package org.dpdns.zerodep.query;

import java.util.AbstractList;
import java.util.List;

/** Several expressions in order, from {@link Sql#keys}; also a list, as {@code groupingSets} takes. */
public final class KeyList extends AbstractList<Expr<?>> implements Keys {
    private final List<Expr<?>> items;

    KeyList(List<Expr<?>> items) { this.items = List.copyOf(items); }

    static List<Expr<?>> of(Keys keys) { return keys instanceof Expr<?> e ? List.of(e) : (KeyList) keys; }

    @Override public Expr<?> get(int index) { return items.get(index); }

    @Override public int size() { return items.size(); }
}

package org.dpdns.zerodep.query.dialect;

/** A named common table expression; its body renders through {@link Renderable#render}. */
public interface CteDefinition extends Renderable {
    String name();
}

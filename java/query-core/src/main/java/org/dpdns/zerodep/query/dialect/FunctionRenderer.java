package org.dpdns.zerodep.query.dialect;

import java.util.List;

/** Renders one function call; registered per function and dialect. */
@FunctionalInterface
public interface FunctionRenderer {
    void render(SqlWriter w, List<? extends Renderable> args);
}

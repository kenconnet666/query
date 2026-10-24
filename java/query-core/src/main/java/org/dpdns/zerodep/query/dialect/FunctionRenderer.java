package org.dpdns.zerodep.query.dialect;

import java.util.List;

/** 渲染一次函数调用；按函数和方言分别注册。 */
@FunctionalInterface
public interface FunctionRenderer {
    /** 把带有 {@code args} 参数的函数调用写入 {@code w}。 */
    void render(SqlWriter w, List<? extends Renderable> args);
}

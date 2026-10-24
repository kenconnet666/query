package org.dpdns.zerodep.query.dialect;

/** 能把自己写成 SQL 的任何东西：表达式、条件、数据源和语句片段都是。 */
@FunctionalInterface
public interface Renderable {
    /** 把自己的 SQL 文本和参数写入 {@code w}。 */
    void render(SqlWriter w);
}

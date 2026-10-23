package org.dpdns.zerodep.query.dialect;

/** 一个具名的公共表表达式（CTE）；它的主体通过 {@link Renderable#render} 写出。 */
public interface CteDefinition extends Renderable {
    /** CTE 的名字，同一条语句里不同的 CTE 不能重名。 */
    String name();
}

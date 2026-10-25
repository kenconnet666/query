package org.dpdns.zerodep.query.polardb;

import org.dpdns.zerodep.query.mysql.MysqlDialect;

/**
 * PolarDB for MySQL。写法与 MySQL 8 方言完全相同：MySQL 方言的全部实库场景在开源的 PolarDB-X 引擎
 * （MySQL 8.0.42 内核，表名不区分大小写）上原样通过（决定 0012）。PolarDB 专属的闪回查询和库存提示在 {@link Polardb} 里，
 * 只能在本方言上渲染；MySQL 方言的专属函数在本方言上照常可用。
 */
public class PolardbDialect extends MysqlDialect {
    /** 无状态单例，{@link PolardbDatabase} 使用它。 */
    public static final PolardbDialect INSTANCE = new PolardbDialect();

    /** 兼容 PolarDB 的方言（以后的分布式版之类）可以再继承。 */
    protected PolardbDialect() { }

    /** 方言名，用于异常信息和监听事件。 */
    @Override public String name() { return "PolarDB"; }

    /** PolarDB MySQL 8.0 的小版本可能早于 8.0.14（没有 LATERAL），一对多的 first(...) 仍写成 ROW_NUMBER 排名。 */
    @Override public boolean supportsLateral() { return false; }
}

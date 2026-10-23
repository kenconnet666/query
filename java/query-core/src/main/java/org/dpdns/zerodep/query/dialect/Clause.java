package org.dpdns.zerodep.query.dialect;

/**
 * SELECT 语句里只属于某个方言的片段，例如 HANA 的 {@code WITH HINT(...)}、PostgreSQL 的
 * {@code DISTINCT ON (...)} 或 MySQL 的优化器提示。在其他方言上渲染会被拒绝。
 */
public interface Clause extends Renderable {
    /** 片段在语句中的位置。 */
    enum Position {
        /** 紧跟在 SELECT 关键字之后，例如 MySQL 的优化器提示注释。 */
        HINT,
        /** 替代 DISTINCT 的位置，例如 DISTINCT ON (...)。 */
        DISTINCT,
        /** 在 ORDER BY 和 LIMIT 之后。 */
        END
    }

    /** 片段要写入的位置。 */
    Position position();

    /** 该片段所属的方言；null 表示在所有方言上都有效。 */
    Class<? extends Dialect> dialect();
}

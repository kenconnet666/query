package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.column.ColInteger;
import org.dpdns.zerodep.query.table.column.ColText;

/** MySQL 专属能力的测试表：JSON 文档和带 FULLTEXT 索引的标题、正文。 */
final class MysqlTables {
    private MysqlTables() { }

    /** 文章：JSON 文档，FULLTEXT ft_article(TITLE, BODY)。 */
    @Table(schema = "QUERY_IT", value = "MY_ARTICLE")
    interface Article extends Entity {
        /** 编号。 */
        @Column("ID") ColInteger<Integer> id();

        /** JSON 文档，按文本读写。 */
        @Column("DOC") ColText<String> doc();

        /** 标题。 */
        @Column("TITLE") ColText<String> title();

        /** 正文。 */
        @Column("BODY") ColText<String> body();
    }
}

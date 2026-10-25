package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.column.Col;
import org.dpdns.zerodep.query.table.column.ColInteger;
import org.dpdns.zerodep.query.table.column.ColText;

/** PostgreSQL 专属类型的测试表：文本数组、JSONB 和全文检索用的正文。 */
final class PgTables {
    private PgTables() { }

    /** 带标签数组、JSONB 文档和正文的行。 */
    @Table(schema = "QUERY_IT", value = "PG_TAGGED")
    interface Tagged extends Entity {
        /** 编号。 */
        @Column("ID") ColInteger<Integer> id();

        /** 标签，text[]。 */
        @Column("TAGS") Col<String[]> tags();

        /** JSONB 文档，按文本读写。 */
        @Column("DOC") ColText<String> doc();

        /** 正文。 */
        @Column("BODY") ColText<String> body();
    }
}

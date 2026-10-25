package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.table.column.ColInteger;
import org.dpdns.zerodep.query.table.column.ColText;

/**
 * 组织单元：每行带父单元的键，树形数据（Trees.build、withAncestors、懒加载的 hasChildren）的对照场景，与 TypeScript 的 org.ts 一一对应。
 * query-it 的样例：1 研发（根）下有 2 前端、3 后端，3 后端下有 4 平台；5 采购是另一个根。
 */
public final class Org {
    /** 测试表所在的 schema。 */
    public static final String SCHEMA = "QUERY_IT";

    /** 只放表，不能实例化。 */
    private Org() { }

    /** 组织单元。 */
    @Table(schema = SCHEMA, value = "ORG_UNIT")
    public interface Unit extends Entity {
        @Id @Column("ID") ColInteger<Integer> id();

        @Column("PARENT_ID") ColInteger<Integer> parentId();

        @Column("NAME") ColText<String> name();

        /** 下一级单元。 */
        default Many<Unit> children() { return many(Unit.class, c -> c.parentId().eq(id())); }
    }
}

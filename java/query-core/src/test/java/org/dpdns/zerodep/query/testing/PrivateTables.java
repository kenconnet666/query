package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;

import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.upper;

/** 与库不在同一个包、且不公开的表接口：它的 default 方法也要能被调用。 */
public final class PrivateTables {
    private PrivateTables() { }

    /** 包内可见的表接口，带一个计算列。 */
    @Table(schema = Erp.SCHEMA, value = "MARA")
    interface Hidden extends Entity {
        /** 物料号。 */
        @Column("MATNR") Col<String> material();

        /** 大写的物料号。 */
        default Expr<String> upperMaterial() { return upper(material()); }
    }

    /** 用不公开的表接口及其 default 方法构造查询并渲染。 */
    public static String render(Dialect dialect) {
        return from(Hidden.class).select(h -> h.upperMaterial()).toSql(dialect).sql();
    }
}

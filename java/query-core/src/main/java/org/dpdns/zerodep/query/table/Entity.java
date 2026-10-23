package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.select.Cols;

/**
 * 所有表接口的基接口。表是带 {@code @Table} 注解的接口，返回 {@code Col<T>} 的抽象方法是它的列，
 * default 方法放可复用的逻辑，例如计算列。查询里的每次表出现，库都会生成一个该接口的实现，
 * 所以 {@code l.material()} 就是这次出现的那一列：
 * <pre>{@code
 * @Table(schema = "ERP", value = "LIPS")
 * public interface Lips extends Entity {
 *     @Column("MATNR") Col<String> material();
 *     @Column("LFIMG") Col<BigDecimal> quantity();
 *     default Expr<BigDecimal> doubled() { return times(quantity(), lit(2)); }
 * }
 * }</pre>
 * 公共列放进不带 {@code @Table} 的基接口，由各表继承；表里重新声明某个列方法并带上自己的 {@code @Column}，就改了这一列的列名。
 */
public interface Entity {
    /** 这张表的全部列，列名为方法名，用于 {@code select(l -> cols(l.all(), ...))}。 */
    Cols all();
}

package org.dpdns.zerodep.query.table;

import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.select.Cols;

import java.util.function.Function;

/**
 * 所有表接口的基接口。表是带 {@code @Table} 注解的接口，返回 {@code Col<T>} 的抽象方法是它的列，
 * default 方法放可复用的逻辑：计算列和关系。查询里的每次表出现，库都会生成一个该接口的实现，
 * 所以 {@code l.material()} 就是这次出现的那一列：
 * <pre>{@code
 * @Table(schema = "ERP", value = "LIPS")
 * public interface Lips extends Entity {
 *     @Column("MATNR") Col<String> material();
 *     @Column("LFIMG") Col<BigDecimal> quantity();
 *     default Expr<BigDecimal> doubled() { return times(quantity(), lit(2)); }                  // 计算列
 *     default Mara mara() { return one(Mara.class, m -> m.material().eq(material())); }         // 多对一
 *     default Many<Lips> sameMaterial() { return many(Lips.class, o -> o.material().eq(material())); } // 一对多
 * }
 * }</pre>
 * 公共列放进不带 {@code @Table} 的基接口，由各表继承；表里重新声明某个列方法并带上自己的 {@code @Column}，就改了这一列的列名。
 * default 方法在同一次表出现上的结果会被记住，所以 {@code l.mara()} 调用多次也只连接一次。
 */
public interface Entity {
    /** 这张表的全部列，列名为方法名，用于 {@code select(l -> cols(l.columns(), ...))}。不叫 all，以免遮住条件函数 all(...)。 */
    Cols columns();

    /**
     * 多对一或一对一关系：用 on 把目标表 LEFT JOIN 进来，返回目标表，可以继续取列或继续导航。
     * 写在表接口的 default 方法里（如 {@code default Mara mara()}），同一个关系在一条查询里只连接一次；
     * 没有被查询用到的关系不会出现在 SQL 里。
     */
    <R extends Entity> R one(Class<R> target, Function<R, Cond> on);

    /**
     * 一对多关系：目标表里满足 on 的那些行。不做连接，只能通过集合运算使用：
     * {@code any}、{@code none}（EXISTS）、{@code count}、{@code sum}、{@code max}、{@code min}（相关子查询），
     * 以及 {@code first(order)}（每组取第一行，再继续取列）。
     */
    <R extends Entity> Many<R> many(Class<R> target, Function<R, Cond> on);
}

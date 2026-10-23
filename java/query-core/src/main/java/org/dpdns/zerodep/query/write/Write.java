package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;

import java.util.List;
import java.util.function.Supplier;

/**
 * 写语句：{@link Insert}、{@link Update} 或 {@link Delete}。和查询一样是不可变的值，{@code db.execute(write)} 执行，
 * {@code db.render(write)} 查看将要执行的语句和参数。
 */
public interface Write {
    /**
     * 渲染出要执行的语句，每条用 writers 给的新写入器；多行插入每行一条（SQL 相同，按批执行），
     * 没有要做的事（例如没有行、SET 项都被 skipEmpty 省略）时为空。
     */
    List<Rendered> render(Supplier<SqlWriter> writers);

    /** 期望影响的总行数，null 表示不检查；见 {@code expect(n)} 和 {@code byId(row)}。 */
    Long expected();

    /** 只按方言渲染，不带数据库实例的全局过滤器，用于测试。 */
    default List<Rendered> toSql(Dialect dialect) { return render(() -> new SqlWriter(dialect)); }
}

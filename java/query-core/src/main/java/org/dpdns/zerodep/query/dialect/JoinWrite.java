package org.dpdns.zerodep.query.dialect;

import java.util.List;

/**
 * 联表的 UPDATE 或 DELETE（方言门面的 {@code updateJoin}、{@code deleteJoin}）交给方言写出的内容。三个库的写法不同，
 * 一行匹配到多行时的行为也不同（PostgreSQL、MySQL 取其中一行，HANA 的 MERGE 报错），所以只由方言包提供。
 *
 * @param target        写出被改的表和别名，例如 {@code QUERY_IT.CARD CARD}
 * @param targetAlias   写出被改表的别名，MySQL 的 {@code DELETE 别名 FROM ...} 要用
 * @param table         写出被改的表，不带别名
 * @param source        写出连接的表和别名
 * @param on            连接条件，已带上连接表的全局过滤器和逻辑删除条件
 * @param where         被改表的过滤条件加上 where 的条件；都没有时为 null
 * @param columns       写出 SET 的列名（不带别名），与 targetColumns、values 一一对应；DELETE 时为空
 * @param targetColumns 写出带别名的被改列，MySQL 写在 SET 左边
 * @param values        新值，可以引用两张表的列
 * @param keys          写出被改表的主键列名（不带别名），HANA 的 MERGE 按它对上行
 * @param keyColumns    写出带别名的主键列，HANA 写在 MERGE 的 USING 查询里
 */
public record JoinWrite(Renderable target, Renderable targetAlias, Renderable table, Renderable source, Renderable on, Renderable where,
                        List<Renderable> columns, List<Renderable> targetColumns, List<Renderable> values, List<Renderable> keys,
                        List<Renderable> keyColumns) {
    /** 复制各列表。 */
    public JoinWrite {
        columns = List.copyOf(columns);
        targetColumns = List.copyOf(targetColumns);
        values = List.copyOf(values);
        keys = List.copyOf(keys);
        keyColumns = List.copyOf(keyColumns);
    }

    /** 写出 {@code on} 再接上 {@code AND where}（有 where 时），PostgreSQL 的 WHERE 和 HANA 的 EXISTS 子查询用。 */
    public void renderOnAndWhere(SqlWriter w) {
        w.render(on);
        if (where != null) w.sql(" AND ").render(where);
    }
}

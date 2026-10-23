package org.dpdns.zerodep.query.dialect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插入或更新一行（{@code insertInto(...).values(row).onConflict(...)}）交给方言写出的内容；各方言的写法差别很大：
 * HANA 用 MERGE，PostgreSQL 用 ON CONFLICT，MySQL 用 ON DUPLICATE KEY UPDATE。
 *
 * @param table   写出 {@code schema.表名}
 * @param columns 插入的列名（未加引号），与 values 一一对应
 * @param values  插入的值
 * @param keys    判断冲突的列名，都在 columns 里；MySQL 不用它，由表的主键和唯一索引决定
 * @param updates 冲突时更新为插入值的列名；与 assignments 都为空时冲突了什么也不做
 * @param assignments 冲突时另外更新的列及其值（例如审计填充的修改时间），按列名
 */
public record Upsert(Renderable table, List<String> columns, List<Renderable> values, List<String> keys, List<String> updates,
                     Map<String, Renderable> assignments) {
    /** 复制各列表。 */
    public Upsert {
        columns = List.copyOf(columns);
        values = List.copyOf(values);
        keys = List.copyOf(keys);
        updates = List.copyOf(updates);
        assignments = Collections.unmodifiableMap(new LinkedHashMap<>(assignments));
    }

    /** 冲突时是否什么也不做。 */
    public boolean doNothing() { return updates.isEmpty() && assignments.isEmpty(); }
}

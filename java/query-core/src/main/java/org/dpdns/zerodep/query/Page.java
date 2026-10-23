package org.dpdns.zerodep.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一页数据和总行数，页码从 1 开始。
 *
 * @param rows  本页的行
 * @param total 不分页时满足条件的总行数
 * @param page  页码，从 1 开始
 * @param size  每页的行数
 */
public record Page<R>(List<R> rows, long total, int page, int size) {
    /** 复制并冻结 rows。 */
    public Page {
        rows = Collections.unmodifiableList(new ArrayList<>(rows)); // 单列查询可能返回 null 行，所以不能用 List.copyOf
    }

    /** 总页数，向上取整；每页行数为 0 时是 0。 */
    public long totalPages() { return size == 0 ? 0 : (total + size - 1) / size; }
}

package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.wire.Wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 写成线上的 typed JSON（0015）交给前端：{@code {rows, total, page, size, totalPages, $types}}，与 vue-tsx-pro 的 {@code TablePage} 对应。
     * 行里的 Long、BigDecimal、日期按 {@link Wire#encode} 写成文字；total 与 totalPages 是普通的 JSON 数字（不会超出 JavaScript 的安全整数）。
     */
    public Map<String, Object> toWire() {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("rows", rows);
        page.put("total", number(total));
        page.put("page", this.page);
        page.put("size", size);
        page.put("totalPages", number(totalPages()));
        @SuppressWarnings("unchecked")
        Map<String, Object> wire = (Map<String, Object>) Wire.encode(page);
        return wire;
    }

    /** 行数写成 Integer，这样编码时是 JSON 数字而不是 bigint 的文字；超出 int 时用 Double，仍在安全整数以内。 */
    private static Number number(long n) { return n <= Integer.MAX_VALUE ? (Number) (int) n : (Number) (double) n; }
}
